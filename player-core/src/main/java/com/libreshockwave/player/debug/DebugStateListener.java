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
}
