package com.libreshockwave.player.wasm.debug;

import com.libreshockwave.player.debug.*;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;
import org.teavm.interop.Import;

import java.util.*;

/**
 * WASM-compatible debug controller using @Import for blocking.
 * Uses Atomics.wait() via JavaScript instead of Semaphore.acquire().
 */
public class WasmDebugController implements DebugControllerApi {

    private volatile DebugController.DebugState state = DebugController.DebugState.RUNNING;
    private volatile StepMode stepMode = StepMode.NONE;
    private volatile int callDepth = 0;
    private volatile int targetCallDepth = 0;
    private volatile boolean pauseRequested = false;

    private final BreakpointManager breakpointManager = new BreakpointManager();
    private final ExpressionEvaluator expressionEvaluator = new ExpressionEvaluator();
    private final List<WatchExpression> watchExpressions = new ArrayList<>();

    private volatile HandlerInfo currentHandlerInfo;
    private volatile InstructionInfo currentInstructionInfo;
    private final Deque<HandlerInfo> handlerInfoStack = new ArrayDeque<>();
    private final List<DebugController.CallFrame> callStack = new ArrayList<>();

    private volatile DebugSnapshot currentSnapshot;
    private volatile Map<String, Datum> globalsSnapshot = Collections.emptyMap();
    private volatile Map<String, Datum> localsSnapshot = Collections.emptyMap();

    private enum StepMode { NONE, STEP_INTO, STEP_OVER, STEP_OUT }

    // === TraceListener implementation ===

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        callDepth++;
        currentHandlerInfo = info;
        handlerInfoStack.push(info);
        callStack.add(new DebugController.CallFrame(
            info.scriptId(),
            info.scriptDisplayName(),
            info.handlerName(),
            new ArrayList<>(info.arguments()),
            info.receiver()
        ));
    }

    @Override
    public void onHandlerExit(HandlerInfo info, Datum returnValue) {
        callDepth--;
        if (!handlerInfoStack.isEmpty()) {
            handlerInfoStack.pop();
        }
        currentHandlerInfo = handlerInfoStack.isEmpty() ? null : handlerInfoStack.peek();
        if (!callStack.isEmpty()) {
            callStack.remove(callStack.size() - 1);
        }

        // Check for step-out completion
        if (stepMode == StepMode.STEP_OUT && callDepth <= targetCallDepth) {
            stepMode = StepMode.STEP_INTO;
        }
    }

    @Override
    public void onInstruction(InstructionInfo info) {
        currentInstructionInfo = info;

        if (checkBreak(info)) {
            pauseExecution(info);
        }
    }

    private boolean checkBreak(InstructionInfo info) {
        // Check pause request first
        if (pauseRequested) {
            pauseRequested = false;
            return true;
        }

        // When stepping over or out, suppress breakpoints in deeper call frames
        boolean suppressBreakpoints =
            (stepMode == StepMode.STEP_OVER || stepMode == StepMode.STEP_OUT)
                && callDepth > targetCallDepth;

        // Check breakpoints
        if (!suppressBreakpoints && currentHandlerInfo != null) {
            Breakpoint bp = breakpointManager.getBreakpoint(currentHandlerInfo.scriptId(), info.offset());
            if (bp != null && bp.enabled()) {
                return true;
            }
        }

        // Check stepping modes
        return switch (stepMode) {
            case STEP_INTO -> true;
            case STEP_OVER -> callDepth <= targetCallDepth;
            case STEP_OUT, NONE -> false;
        };
    }

    private void pauseExecution(InstructionInfo info) {
        state = DebugController.DebugState.PAUSED;
        stepMode = StepMode.NONE;

        // Capture snapshot
        currentSnapshot = captureSnapshot(info);

        // Serialize snapshot and notify JS
        if (currentSnapshot != null) {
            String json = WasmDebugSerializer.serializeSnapshot(currentSnapshot);
            byte[] jsonBytes = json.getBytes();
            notifyPausedBytes = jsonBytes;
            jsDebugNotifyPaused(jsonBytes.length);
        }

        // Block until resumed via Atomics.wait
        jsDebugWait();
    }

    // Temporary storage for the paused notification bytes (read by WasmPlayerApp)
    public byte[] notifyPausedBytes;

    private DebugSnapshot captureSnapshot(InstructionInfo info) {
        HandlerInfo handlerInfo = currentHandlerInfo;
        if (handlerInfo == null) return null;

        Map<String, Datum> locals = info.localsSnapshot() != null
            ? new LinkedHashMap<>(info.localsSnapshot())
            : new LinkedHashMap<>(localsSnapshot);

        Map<String, Datum> globals = info.globalsSnapshot() != null && !info.globalsSnapshot().isEmpty()
            ? new LinkedHashMap<>(info.globalsSnapshot())
            : new LinkedHashMap<>(globalsSnapshot);

        this.localsSnapshot = locals;
        this.globalsSnapshot = globals;

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
            Collections.emptyList(),
            new ArrayList<>(info.stackSnapshot()),
            locals,
            globals,
            new ArrayList<>(handlerInfo.arguments()),
            handlerInfo.receiver(),
            getCallStackSnapshot(),
            evaluatedWatches
        );
    }

    // === Debug controls ===

    @Override
    public void stepInto() {
        if (state != DebugController.DebugState.PAUSED) return;
        stepMode = StepMode.STEP_INTO;
        state = DebugController.DebugState.STEPPING;
        jsDebugResume();
    }

    @Override
    public void stepOver() {
        if (state != DebugController.DebugState.PAUSED) return;
        stepMode = StepMode.STEP_OVER;
        targetCallDepth = callDepth;
        state = DebugController.DebugState.STEPPING;
        jsDebugResume();
    }

    @Override
    public void stepOut() {
        if (state != DebugController.DebugState.PAUSED) return;
        stepMode = StepMode.STEP_OUT;
        targetCallDepth = callDepth - 1;
        state = DebugController.DebugState.STEPPING;
        jsDebugResume();
    }

    @Override
    public void continueExecution() {
        if (state != DebugController.DebugState.PAUSED) return;
        stepMode = StepMode.NONE;
        state = DebugController.DebugState.RUNNING;
        jsDebugResume();
    }

    @Override
    public void pause() {
        if (state == DebugController.DebugState.RUNNING || state == DebugController.DebugState.STEPPING) {
            pauseRequested = true;
        }
    }

    @Override
    public boolean isPaused() {
        return state == DebugController.DebugState.PAUSED;
    }

    @Override
    public DebugController.DebugState getState() {
        return state;
    }

    @Override
    public boolean isAwaitingStepContinuation() {
        return state == DebugController.DebugState.STEPPING && stepMode != StepMode.NONE;
    }

    @Override
    public DebugSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    // === State injection ===

    @Override
    public void setGlobalsSnapshot(Map<String, Datum> globals) {
        this.globalsSnapshot = globals != null ? new LinkedHashMap<>(globals) : Collections.emptyMap();
    }

    @Override
    public void setLocalsSnapshot(Map<String, Datum> locals) {
        this.localsSnapshot = locals != null ? new LinkedHashMap<>(locals) : Collections.emptyMap();
    }

    @Override
    public void reset() {
        if (state == DebugController.DebugState.PAUSED) {
            state = DebugController.DebugState.RUNNING;
            jsDebugResume();
        }
        state = DebugController.DebugState.RUNNING;
        stepMode = StepMode.NONE;
        callDepth = 0;
        targetCallDepth = 0;
        pauseRequested = false;
        callStack.clear();
        handlerInfoStack.clear();
        currentHandlerInfo = null;
        currentInstructionInfo = null;
        currentSnapshot = null;
    }

    // === Breakpoints ===

    @Override
    public boolean toggleBreakpoint(int scriptId, int offset) {
        Breakpoint result = breakpointManager.toggleBreakpoint(scriptId, offset);
        return result != null;
    }

    @Override
    public void clearAllBreakpoints() {
        breakpointManager.clearAll();
    }

    @Override
    public boolean hasBreakpoint(int scriptId, int offset) {
        return breakpointManager.hasBreakpoint(scriptId, offset);
    }

    @Override
    public Breakpoint getBreakpoint(int scriptId, int offset) {
        return breakpointManager.getBreakpoint(scriptId, offset);
    }

    @Override
    public BreakpointManager getBreakpointManager() {
        return breakpointManager;
    }

    @Override
    public String serializeBreakpoints() {
        return breakpointManager.serialize();
    }

    @Override
    public void deserializeBreakpoints(String data) {
        breakpointManager.deserialize(data);
    }

    // === Watch expressions ===

    @Override
    public WatchExpression addWatchExpression(String expression) {
        WatchExpression watch = WatchExpression.create(expression);
        watchExpressions.add(watch);
        return watch;
    }

    @Override
    public boolean removeWatchExpression(String id) {
        return watchExpressions.removeIf(w -> w.id().equals(id));
    }

    @Override
    public List<WatchExpression> getWatchExpressions() {
        return new ArrayList<>(watchExpressions);
    }

    @Override
    public List<WatchExpression> evaluateWatchExpressions() {
        HandlerInfo handlerInfo = currentHandlerInfo;
        Datum receiver = handlerInfo != null ? handlerInfo.receiver() : null;

        Map<String, Datum> params = new LinkedHashMap<>();
        if (handlerInfo != null && handlerInfo.arguments() != null) {
            List<Datum> args = handlerInfo.arguments();
            for (int i = 0; i < args.size(); i++) {
                params.put("arg" + i, args.get(i));
            }
        }

        ExpressionEvaluator.EvaluationContext ctx = new ExpressionEvaluator.EvaluationContext(
            localsSnapshot, params, globalsSnapshot, receiver
        );

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

    @Override
    public void clearWatchExpressions() {
        watchExpressions.clear();
    }

    // === Call stack ===

    @Override
    public List<DebugController.CallFrame> getCallStackSnapshot() {
        return new ArrayList<>(callStack);
    }

    // === WASM imports — provided by JavaScript worker ===

    @Import(name = "debugWait", module = "libreshockwave")
    private static native void jsDebugWait();

    @Import(name = "debugResume", module = "libreshockwave")
    private static native void jsDebugResume();

    @Import(name = "debugNotifyPaused", module = "libreshockwave")
    private static native void jsDebugNotifyPaused(int jsonLength);
}
