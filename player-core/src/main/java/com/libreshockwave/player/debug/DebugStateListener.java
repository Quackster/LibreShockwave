package com.libreshockwave.player.debug;

/**
 * Interface for UI to receive debug events.
 * All callbacks are invoked on the EDT (Swing Event Dispatch Thread).
 */
public interface DebugStateListener {

    /**
     * Called when the VM pauses at a breakpoint or step.
     * @param snapshot The current debug state
     */
    void onPaused(DebugSnapshot snapshot);

    /**
     * Called when the VM resumes execution after being paused.
     */
    void onResumed();

    /**
     * Called when breakpoints are added, removed, or toggled.
     */
    void onBreakpointsChanged();

    /**
     * Called when a log point is hit. The VM does not pause for log points.
     * @param bp The log point breakpoint
     * @param message The interpolated log message
     */
    default void onLogPointHit(Breakpoint bp, String message) {
        // Default implementation does nothing
    }

    /**
     * Called when watch expressions are updated (added, removed, or modified).
     */
    default void onWatchExpressionsChanged() {
        // Default implementation does nothing
    }
}
