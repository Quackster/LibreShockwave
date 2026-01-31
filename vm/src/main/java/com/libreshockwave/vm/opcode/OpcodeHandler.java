package com.libreshockwave.vm.opcode;

/**
 * Functional interface for opcode handlers.
 * Each handler receives an execution context and returns whether to advance the bytecode index.
 */
@FunctionalInterface
public interface OpcodeHandler {

    /**
     * Execute the opcode.
     * @param ctx The execution context
     * @return true if the bytecode index should be advanced, false if the handler already handled it (e.g., jumps)
     */
    boolean execute(ExecutionContext ctx);
}
