package com.libreshockwave.player.debug;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

/**
 * Core debugging controller that implements TraceListener.
 * Blocks VM execution when paused by using a Semaphore in onInstruction().
 */
public class DebugController implements TraceListener {

    /**
     * Current debug state.
     */
    public enum DebugState {
        /** VM is running normally */
        RUNNING,
        /** VM is paused at a breakpoint or step */
        PAUSED,
        /** VM is executing a step command */
        STEPPING
    }

    /**
     * Step mode for single-stepping.
     */
    public enum StepMode {
        /** No stepping - run normally */
        NONE,
        /** Break on every instruction */
        STEP_INTO,
        /** Break when returning to same or lower call depth */
        STEP_OVER,
        /** Break when returning to lower call depth */
        STEP_OUT
    }

    /**
     * Represents a single frame in the call stack.
     */
    public record CallFrame(
        int scriptId,
        String scriptName,
        String handlerName,
        List<Datum> arguments,
        Datum receiver
    ) {}

    // Synchronization - blocks VM thread when paused
    private final Semaphore pauseSemaphore = new Semaphore(0);
    private final Object stateLock = new Object();

    // State
    private volatile DebugState state = DebugState.RUNNING;
    private volatile StepMode stepMode = StepMode.NONE;
    private volatile int callDepth = 0;
    private volatile int targetCallDepth = 0;

    // Request to pause on next instruction (when Pause button is clicked)
    private volatile boolean pauseRequested = false;

    // Breakpoints: scriptId -> set of instruction offsets
    private final Map<Integer, Set<Integer>> breakpoints = new ConcurrentHashMap<>();

    // Current handler context (for snapshot creation)
    private volatile HandlerInfo currentHandlerInfo;
    private volatile ScriptChunk currentScript;
    private volatile ScriptChunk.Handler currentHandler;
    private volatile InstructionInfo currentInstructionInfo;

    // Call stack tracking
    private final List<CallFrame> callStack = new ArrayList<>();

    // Most recent snapshot (for UI)
    private volatile DebugSnapshot currentSnapshot;

    // UI listeners (notified on EDT)
    private final List<DebugStateListener> listeners = new CopyOnWriteArrayList<>();

    // Globals accessor (set by Player)
    private volatile Map<String, Datum> globalsSnapshot = Collections.emptyMap();

    // Delegate trace listener (for UI updates)
    private volatile TraceListener delegateListener;

    public DebugController() {
    }

    /**
     * Set a delegate trace listener that receives all events.
     * This allows the UI to observe VM execution while the controller handles debugging.
     */
    public void setDelegateListener(TraceListener listener) {
        this.delegateListener = listener;
    }

    // TraceListener implementation

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        callDepth++;
        currentHandlerInfo = info;

        // Track call stack
        synchronized (callStack) {
            callStack.add(new CallFrame(
                info.scriptId(),
                info.scriptDisplayName(),
                info.handlerName(),
                new ArrayList<>(info.arguments()),
                info.receiver()
            ));
        }

        // Forward to delegate
        TraceListener delegate = delegateListener;
        if (delegate != null) {
            delegate.onHandlerEnter(info);
        }
    }

    @Override
    public void onHandlerExit(HandlerInfo info, Datum returnValue) {
        callDepth--;

        // Pop from call stack
        synchronized (callStack) {
            if (!callStack.isEmpty()) {
                callStack.remove(callStack.size() - 1);
            }
        }

        // Check for step-out completion
        synchronized (stateLock) {
            if (stepMode == StepMode.STEP_OUT && callDepth <= targetCallDepth) {
                // Will pause on next instruction after returning
                stepMode = StepMode.STEP_INTO;
            }
        }

        // Forward to delegate
        TraceListener delegate = delegateListener;
        if (delegate != null) {
            delegate.onHandlerExit(info, returnValue);
        }
    }

    @Override
    public void onInstruction(InstructionInfo info) {
        currentInstructionInfo = info;

        // Forward to delegate
        TraceListener delegate = delegateListener;
        if (delegate != null) {
            delegate.onInstruction(info);
        }

        // Check if we should break
        if (shouldBreak(info)) {
            pauseExecution(info);
        }
    }

    @Override
    public void onVariableSet(String type, String name, Datum value) {
        // Forward to delegate
        TraceListener delegate = delegateListener;
        if (delegate != null) {
            delegate.onVariableSet(type, name, value);
        }
    }

    @Override
    public void onError(String message, Exception error) {
        // Forward to delegate
        TraceListener delegate = delegateListener;
        if (delegate != null) {
            delegate.onError(message, error);
        }
    }

    @Override
    public void onDebugMessage(String message) {
        // Forward to delegate
        TraceListener delegate = delegateListener;
        if (delegate != null) {
            delegate.onDebugMessage(message);
        }
    }

    /**
     * Determine if we should break at this instruction.
     */
    private boolean shouldBreak(InstructionInfo info) {
        synchronized (stateLock) {
            // Check pause request
            if (pauseRequested) {
                pauseRequested = false;
                return true;
            }

            // Check breakpoints
            if (currentHandlerInfo != null && hasBreakpoint(currentHandlerInfo.scriptId(), info.offset())) {
                return true;
            }

            // Check stepping modes
            return switch (stepMode) {
                case STEP_INTO -> true;  // Always break
                case STEP_OVER -> callDepth <= targetCallDepth;
                case STEP_OUT -> false;  // Handled in onHandlerExit
                case NONE -> false;
            };
        }
    }

    /**
     * Pause VM execution at the current instruction.
     * This blocks the VM thread until resume/step is called.
     */
    private void pauseExecution(InstructionInfo info) {
        synchronized (stateLock) {
            state = DebugState.PAUSED;
            stepMode = StepMode.NONE;
        }

        // Capture snapshot
        currentSnapshot = captureSnapshot(info);

        // Notify UI on EDT
        notifyPaused(currentSnapshot);

        // Block VM thread until resumed
        try {
            pauseSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Capture current state as an immutable snapshot.
     */
    private DebugSnapshot captureSnapshot(InstructionInfo info) {
        HandlerInfo handlerInfo = currentHandlerInfo;
        if (handlerInfo == null) {
            return null;
        }

        // Build instruction list from handler
        List<DebugSnapshot.InstructionDisplay> instructions = new ArrayList<>();
        if (handlerInfo.literals() != null) {
            // We need to get instructions from somewhere
            // The handler info doesn't contain instructions directly
            // We'll build from the current instruction info for now
        }

        // Build locals map from current scope
        // Note: We only have info from HandlerInfo, not direct scope access
        Map<String, Datum> locals = new LinkedHashMap<>();
        // Locals would need to be captured from the scope

        return new DebugSnapshot(
            handlerInfo.scriptId(),
            handlerInfo.scriptDisplayName(),
            handlerInfo.handlerName(),
            info.offset(),
            info.bytecodeIndex(),
            info.opcode(),
            info.argument(),
            info.annotation(),
            instructions,  // Will be populated when we have script access
            new ArrayList<>(info.stackSnapshot()),
            locals,
            new LinkedHashMap<>(globalsSnapshot),
            new ArrayList<>(handlerInfo.arguments()),
            handlerInfo.receiver(),
            getCallStackSnapshot()
        );
    }

    // Control methods (called from UI on EDT)

    /**
     * Step into - execute one instruction and pause.
     */
    public void stepInto() {
        synchronized (stateLock) {
            if (state != DebugState.PAUSED) return;
            stepMode = StepMode.STEP_INTO;
            state = DebugState.STEPPING;
        }
        notifyResumed();
        pauseSemaphore.release();  // Unblock VM thread
    }

    /**
     * Step over - execute until returning to same call depth.
     */
    public void stepOver() {
        synchronized (stateLock) {
            if (state != DebugState.PAUSED) return;
            stepMode = StepMode.STEP_OVER;
            targetCallDepth = callDepth;
            state = DebugState.STEPPING;
        }
        notifyResumed();
        pauseSemaphore.release();
    }

    /**
     * Step out - execute until returning to caller.
     */
    public void stepOut() {
        synchronized (stateLock) {
            if (state != DebugState.PAUSED) return;
            stepMode = StepMode.STEP_OUT;
            targetCallDepth = callDepth - 1;
            state = DebugState.STEPPING;
        }
        notifyResumed();
        pauseSemaphore.release();
    }

    /**
     * Continue execution until next breakpoint.
     */
    public void continueExecution() {
        synchronized (stateLock) {
            if (state != DebugState.PAUSED) return;
            stepMode = StepMode.NONE;
            state = DebugState.RUNNING;
        }
        notifyResumed();
        pauseSemaphore.release();
    }

    /**
     * Request pause on next instruction.
     * Call this when the VM is running to pause it.
     */
    public void pause() {
        synchronized (stateLock) {
            if (state == DebugState.RUNNING || state == DebugState.STEPPING) {
                pauseRequested = true;
            }
        }
    }

    /**
     * Check if currently paused.
     */
    public boolean isPaused() {
        return state == DebugState.PAUSED;
    }

    /**
     * Get current debug state.
     */
    public DebugState getState() {
        return state;
    }

    /**
     * Get current snapshot (may be null if not paused).
     */
    public DebugSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    // Breakpoint management

    /**
     * Toggle a breakpoint at the given script and offset.
     * @return true if breakpoint was added, false if removed
     */
    public boolean toggleBreakpoint(int scriptId, int offset) {
        Set<Integer> offsets = breakpoints.computeIfAbsent(scriptId, k -> ConcurrentHashMap.newKeySet());
        boolean added;
        if (offsets.contains(offset)) {
            offsets.remove(offset);
            added = false;
        } else {
            offsets.add(offset);
            added = true;
        }
        notifyBreakpointsChanged();
        return added;
    }

    /**
     * Add a breakpoint.
     */
    public void addBreakpoint(int scriptId, int offset) {
        breakpoints.computeIfAbsent(scriptId, k -> ConcurrentHashMap.newKeySet()).add(offset);
        notifyBreakpointsChanged();
    }

    /**
     * Remove a breakpoint.
     */
    public void removeBreakpoint(int scriptId, int offset) {
        Set<Integer> offsets = breakpoints.get(scriptId);
        if (offsets != null) {
            offsets.remove(offset);
            notifyBreakpointsChanged();
        }
    }

    /**
     * Check if a breakpoint exists.
     */
    public boolean hasBreakpoint(int scriptId, int offset) {
        Set<Integer> offsets = breakpoints.get(scriptId);
        return offsets != null && offsets.contains(offset);
    }

    /**
     * Get all breakpoints.
     */
    public Map<Integer, Set<Integer>> getBreakpoints() {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : breakpoints.entrySet()) {
            result.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return result;
    }

    /**
     * Clear all breakpoints.
     */
    public void clearAllBreakpoints() {
        breakpoints.clear();
        notifyBreakpointsChanged();
    }

    // Listener management

    /**
     * Add a listener for debug state changes.
     */
    public void addListener(DebugStateListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a listener.
     */
    public void removeListener(DebugStateListener listener) {
        listeners.remove(listener);
    }

    private void notifyPaused(DebugSnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
            for (DebugStateListener listener : listeners) {
                listener.onPaused(snapshot);
            }
        });
    }

    private void notifyResumed() {
        SwingUtilities.invokeLater(() -> {
            for (DebugStateListener listener : listeners) {
                listener.onResumed();
            }
        });
    }

    private void notifyBreakpointsChanged() {
        SwingUtilities.invokeLater(() -> {
            for (DebugStateListener listener : listeners) {
                listener.onBreakpointsChanged();
            }
        });
    }

    // State injection (called by Player)

    /**
     * Update the globals snapshot for debugging.
     * Called by Player before/during execution.
     */
    public void setGlobalsSnapshot(Map<String, Datum> globals) {
        this.globalsSnapshot = globals != null ? new LinkedHashMap<>(globals) : Collections.emptyMap();
    }

    /**
     * Reset debug state (call when movie is stopped/reset).
     */
    public void reset() {
        synchronized (stateLock) {
            // If paused, release the semaphore to avoid deadlock
            if (state == DebugState.PAUSED) {
                state = DebugState.RUNNING;
                pauseSemaphore.release();
            }
            state = DebugState.RUNNING;
            stepMode = StepMode.NONE;
            callDepth = 0;
            targetCallDepth = 0;
            pauseRequested = false;
        }
        // Drain any stale semaphore permits to avoid unexpected behavior
        pauseSemaphore.drainPermits();
        synchronized (callStack) {
            callStack.clear();
        }
        currentHandlerInfo = null;
        currentInstructionInfo = null;
        currentSnapshot = null;
    }

    /**
     * Get current call depth.
     */
    public int getCallDepth() {
        return callDepth;
    }

    /**
     * Get current handler name (for display).
     */
    public String getCurrentHandlerName() {
        HandlerInfo info = currentHandlerInfo;
        return info != null ? info.handlerName() : null;
    }

    /**
     * Get a snapshot of the current call stack.
     * Returns a copy to avoid concurrency issues.
     */
    public List<CallFrame> getCallStackSnapshot() {
        synchronized (callStack) {
            return new ArrayList<>(callStack);
        }
    }
}
