package com.libreshockwave.runtime;

/**
 * Result of executing a bytecode handler.
 * Controls main interpreter loop behavior.
 * Matches dirplayer-rs HandlerExecutionResult enum.
 */
public enum HandlerExecutionResult {
    /**
     * Move to the next bytecode instruction.
     * Default result for most operations.
     */
    ADVANCE,

    /**
     * Bytecode index was modified by the handler (for branches/jumps).
     * The main loop should NOT advance the instruction pointer.
     */
    JUMP,

    /**
     * Exit the current handler (return statement).
     * The main loop should stop executing.
     */
    STOP,

    /**
     * An error occurred during execution.
     * The main loop should handle the error appropriately.
     */
    ERROR
}
