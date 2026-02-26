package com.libreshockwave.player.debug;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import java.util.List;
import java.util.Map;

/**
 * Interface for debug controllers that can pause/step/inspect VM execution.
 * Extracted from DebugController to allow alternative implementations
 * (e.g. WasmDebugController that uses Atomics.wait instead of Semaphore).
 */
public interface DebugControllerApi extends TraceListener {

    // State injection (called by Player during frame execution)
    void setGlobalsSnapshot(Map<String, Datum> globals);
    void setLocalsSnapshot(Map<String, Datum> locals);
    boolean isAwaitingStepContinuation();
    void reset();

    // State queries
    boolean isPaused();
    DebugController.DebugState getState();
    DebugSnapshot getCurrentSnapshot();

    // Debug controls
    void stepInto();
    void stepOver();
    void stepOut();
    void continueExecution();
    void pause();

    // Breakpoints
    boolean toggleBreakpoint(int scriptId, int offset);
    void clearAllBreakpoints();
    boolean hasBreakpoint(int scriptId, int offset);
    Breakpoint getBreakpoint(int scriptId, int offset);
    BreakpointManager getBreakpointManager();
    String serializeBreakpoints();
    void deserializeBreakpoints(String data);

    // Watch expressions
    WatchExpression addWatchExpression(String expression);
    boolean removeWatchExpression(String id);
    List<WatchExpression> getWatchExpressions();
    List<WatchExpression> evaluateWatchExpressions();
    void clearWatchExpressions();

    // Call stack
    List<DebugController.CallFrame> getCallStackSnapshot();
}
