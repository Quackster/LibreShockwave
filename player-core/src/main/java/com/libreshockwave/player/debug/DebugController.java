package com.libreshockwave.player.debug;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Core debugging controller that implements TraceListener.
 * Blocks VM execution when paused by using a Semaphore in onInstruction().
 *
 * Supports:
 * - Simple breakpoints
 * - Breakpoint enable/disable
 * - Watch expressions
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

    // Breakpoint manager (replaces old Map<Integer, Set<Integer>>)
    private final BreakpointManager breakpointManager = new BreakpointManager();

    // Expression evaluator for watches
    private final ExpressionEvaluator expressionEvaluator = new ExpressionEvaluator();

    // Watch expressions
    private final List<WatchExpression> watchExpressions = new CopyOnWriteArrayList<>();

    // Current handler context (for snapshot creation)
    private volatile HandlerInfo currentHandlerInfo;
    private volatile InstructionInfo currentInstructionInfo;

    // Stack of handler info for restoring context after nested calls
    private final Deque<HandlerInfo> handlerInfoStack = new ArrayDeque<>();

    // Call stack tracking
    private final List<CallFrame> callStack = new ArrayList<>();

    // Most recent snapshot (for UI)
    private volatile DebugSnapshot currentSnapshot;

    // UI listeners (notified on EDT)
    private final List<DebugStateListener> listeners = new CopyOnWriteArrayList<>();

    // Globals accessor (set by Player)
    private volatile Map<String, Datum> globalsSnapshot = Collections.emptyMap();

    // Locals accessor (set by Player when pausing)
    private volatile Map<String, Datum> localsSnapshot = Collections.emptyMap();

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

    /**
     * Get the breakpoint manager.
     */
    public BreakpointManager getBreakpointManager() {
        return breakpointManager;
    }

    // TraceListener implementation

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        callDepth++;
        currentHandlerInfo = info;
        handlerInfoStack.push(info);

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

        // Restore currentHandlerInfo to the parent handler
        if (!handlerInfoStack.isEmpty()) {
            handlerInfoStack.pop();
        }
        currentHandlerInfo = handlerInfoStack.isEmpty() ? null : handlerInfoStack.peek();

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
        BreakResult result = checkBreak(info);
        if (result.shouldPause) {
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
     * Result of checking whether to break at an instruction.
     */
    private record BreakResult(boolean shouldPause, Breakpoint breakpoint) {
        static BreakResult noBreak() { return new BreakResult(false, null); }
        static BreakResult pause(Breakpoint bp) { return new BreakResult(true, bp); }
        static BreakResult pauseNoBreakpoint() { return new BreakResult(true, null); }
    }

    /**
     * Check if we should break at this instruction.
     */
    private BreakResult checkBreak(InstructionInfo info) {
        synchronized (stateLock) {
            // Check pause request first
            if (pauseRequested) {
                pauseRequested = false;
                return BreakResult.pauseNoBreakpoint();
            }

            // When stepping over or out, suppress breakpoints in deeper call frames
            // so we don't lose the step context by pausing inside a nested handler
            boolean suppressBreakpoints =
                (stepMode == StepMode.STEP_OVER || stepMode == StepMode.STEP_OUT)
                    && callDepth > targetCallDepth;

            // Check breakpoints (unless suppressed by step mode)
            if (!suppressBreakpoints && currentHandlerInfo != null) {
                Breakpoint bp = breakpointManager.getBreakpoint(currentHandlerInfo.scriptId(), info.offset());
                if (bp != null && bp.enabled()) {
                    return BreakResult.pause(bp);
                }
            }

            // Check stepping modes
            boolean shouldStep = switch (stepMode) {
                case STEP_INTO -> true;  // Always break
                case STEP_OVER -> callDepth <= targetCallDepth;
                case STEP_OUT -> false;  // Handled in onHandlerExit
                case NONE -> false;
            };

            return shouldStep ? BreakResult.pauseNoBreakpoint() : BreakResult.noBreak();
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

        // Capture snapshot with evaluated watches
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
        Map<String, Datum> locals = new LinkedHashMap<>(localsSnapshot);

        // Evaluate watch expressions
        List<WatchExpression> evaluatedWatches = evaluateWatchExpressions();

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
            getCallStackSnapshot(),
            evaluatedWatches
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
     * Check if stepping needs to continue into the next frame.
     * This returns true when we're in STEPPING state with an active step mode,
     * meaning a step command was issued but we haven't hit a pause yet
     * (e.g., because the handler/frame ended before another instruction could pause).
     */
    public boolean isAwaitingStepContinuation() {
        synchronized (stateLock) {
            return state == DebugState.STEPPING && stepMode != StepMode.NONE;
        }
    }

    /**
     * Get current snapshot (may be null if not paused).
     */
    public DebugSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    // Breakpoint management (delegates to BreakpointManager for compatibility)

    /**
     * Toggle a breakpoint at the given script and offset.
     * @return true if breakpoint was added, false if removed
     */
    public boolean toggleBreakpoint(int scriptId, int offset) {
        Breakpoint result = breakpointManager.toggleBreakpoint(scriptId, offset);
        notifyBreakpointsChanged();
        return result != null;
    }

    /**
     * Add a breakpoint.
     */
    public void addBreakpoint(int scriptId, int offset) {
        breakpointManager.addBreakpoint(scriptId, offset);
        notifyBreakpointsChanged();
    }

    /**
     * Remove a breakpoint.
     */
    public void removeBreakpoint(int scriptId, int offset) {
        breakpointManager.removeBreakpoint(scriptId, offset);
        notifyBreakpointsChanged();
    }

    /**
     * Check if a breakpoint exists.
     */
    public boolean hasBreakpoint(int scriptId, int offset) {
        return breakpointManager.hasBreakpoint(scriptId, offset);
    }

    /**
     * Get a specific breakpoint.
     */
    public Breakpoint getBreakpoint(int scriptId, int offset) {
        return breakpointManager.getBreakpoint(scriptId, offset);
    }

    /**
     * Update or add a breakpoint with full properties.
     */
    public void setBreakpoint(Breakpoint bp) {
        breakpointManager.setBreakpoint(bp);
        notifyBreakpointsChanged();
    }

    /**
     * Toggle enabled state of a breakpoint.
     * @return the updated breakpoint, or null if no breakpoint exists
     */
    public Breakpoint toggleBreakpointEnabled(int scriptId, int offset) {
        Breakpoint bp = breakpointManager.toggleEnabled(scriptId, offset);
        if (bp != null) {
            notifyBreakpointsChanged();
        }
        return bp;
    }

    /**
     * Get all breakpoints as a map (scriptId -> set of offsets).
     * For backward compatibility.
     */
    public Map<Integer, Set<Integer>> getBreakpoints() {
        return breakpointManager.toOffsetMap();
    }

    /**
     * Clear all breakpoints.
     */
    public void clearAllBreakpoints() {
        breakpointManager.clearAll();
        notifyBreakpointsChanged();
    }

    /**
     * Set all breakpoints from a map (used for loading saved breakpoints).
     * For backward compatibility with old format.
     */
    public void setBreakpoints(Map<Integer, Set<Integer>> newBreakpoints) {
        breakpointManager.setFromOffsetMap(newBreakpoints);
        notifyBreakpointsChanged();
    }

    /**
     * Serialize breakpoints to a string for persistence.
     * Uses new JSON format with backward compatibility.
     */
    public String serializeBreakpoints() {
        return breakpointManager.serialize();
    }

    /**
     * Deserialize breakpoints from a string.
     * Supports both new JSON format and legacy format.
     */
    public void deserializeBreakpoints(String data) {
        breakpointManager.deserialize(data);
        notifyBreakpointsChanged();
    }

    /**
     * Deserialize breakpoints from a string (static version for backward compatibility).
     * @deprecated Use instance method deserializeBreakpoints() instead.
     */
    @Deprecated
    public static Map<Integer, Set<Integer>> deserializeBreakpointsLegacy(String data) {
        BreakpointManager temp = new BreakpointManager();
        temp.deserialize(data);
        return temp.toOffsetMap();
    }

    // Watch expression management

    /**
     * Add a watch expression.
     * @return the created watch expression
     */
    public WatchExpression addWatchExpression(String expression) {
        WatchExpression watch = WatchExpression.create(expression);
        watchExpressions.add(watch);
        notifyWatchExpressionsChanged();
        return watch;
    }

    /**
     * Remove a watch expression by ID.
     * @return true if removed, false if not found
     */
    public boolean removeWatchExpression(String id) {
        boolean removed = watchExpressions.removeIf(w -> w.id().equals(id));
        if (removed) {
            notifyWatchExpressionsChanged();
        }
        return removed;
    }

    /**
     * Update a watch expression.
     * @return the updated watch, or null if not found
     */
    public WatchExpression updateWatchExpression(String id, String newExpression) {
        for (int i = 0; i < watchExpressions.size(); i++) {
            WatchExpression w = watchExpressions.get(i);
            if (w.id().equals(id)) {
                WatchExpression updated = w.withExpression(newExpression);
                watchExpressions.set(i, updated);
                notifyWatchExpressionsChanged();
                return updated;
            }
        }
        return null;
    }

    /**
     * Get all watch expressions.
     */
    public List<WatchExpression> getWatchExpressions() {
        return new ArrayList<>(watchExpressions);
    }

    /**
     * Build an evaluation context from current state.
     */
    private ExpressionEvaluator.EvaluationContext buildEvaluationContext() {
        HandlerInfo handlerInfo = currentHandlerInfo;
        Datum receiver = handlerInfo != null ? handlerInfo.receiver() : null;

        // Build params map from handler arguments
        Map<String, Datum> params = new LinkedHashMap<>();
        if (handlerInfo != null && handlerInfo.arguments() != null) {
            List<Datum> args = handlerInfo.arguments();
            for (int i = 0; i < args.size(); i++) {
                params.put("arg" + i, args.get(i));
            }
        }

        return new ExpressionEvaluator.EvaluationContext(
            localsSnapshot,
            params,
            globalsSnapshot,
            receiver
        );
    }

    /**
     * Evaluate all watch expressions with current context.
     * @return list of evaluated watches with values/errors populated
     */
    public List<WatchExpression> evaluateWatchExpressions() {
        ExpressionEvaluator.EvaluationContext ctx = buildEvaluationContext();
        List<WatchExpression> evaluated = new ArrayList<>();

        for (WatchExpression watch : watchExpressions) {
            ExpressionEvaluator.EvalResult result = expressionEvaluator.evaluate(watch.expression(), ctx);
            WatchExpression updated = switch (result) {
                case ExpressionEvaluator.EvalResult.Success s -> watch.withValue(s.value());
                case ExpressionEvaluator.EvalResult.Error e -> watch.withError(e.message());
            };
            evaluated.add(updated);
        }

        return evaluated;
    }

    /**
     * Clear all watch expressions.
     */
    public void clearWatchExpressions() {
        watchExpressions.clear();
        notifyWatchExpressionsChanged();
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

    private void notifyWatchExpressionsChanged() {
        SwingUtilities.invokeLater(() -> {
            for (DebugStateListener listener : listeners) {
                listener.onWatchExpressionsChanged();
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
     * Update the locals snapshot for debugging.
     * Called by Player when pausing.
     */
    public void setLocalsSnapshot(Map<String, Datum> locals) {
        this.localsSnapshot = locals != null ? new LinkedHashMap<>(locals) : Collections.emptyMap();
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
        handlerInfoStack.clear();
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
