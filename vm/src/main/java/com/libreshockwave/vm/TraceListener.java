package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;

import java.util.List;
import java.util.Map;

/**
 * Listener for VM execution trace events.
 * Implement this to receive detailed execution information.
 */
public interface TraceListener {

    /**
     * Called when a handler is entered.
     */
    default void onHandlerEnter(HandlerInfo info) {}

    /**
     * Called when a handler exits.
     */
    default void onHandlerExit(HandlerInfo info, Datum returnValue) {}

    /**
     * Called before each instruction is executed.
     */
    default void onInstruction(InstructionInfo info) {}

    /**
     * Called after a variable assignment.
     */
    default void onVariableSet(String type, String name, Datum value) {}

    /**
     * Called when an error occurs.
     */
    default void onError(String message, Exception error) {}

    /**
     * Called for general debug messages.
     * Format follows dirplayer-rs conventions.
     */
    default void onDebugMessage(String message) {}

    /**
     * Handler execution information.
     */
    record HandlerInfo(
        String handlerName,
        int scriptId,
        String scriptName,
        String scriptType,
        List<Datum> arguments,
        Datum receiver,
        Map<String, Datum> globals,
        List<ScriptChunk.LiteralEntry> literals,
        int localCount,
        int argCount
    ) {}

    /**
     * Instruction execution information.
     */
    record InstructionInfo(
        int bytecodeIndex,
        int offset,
        String opcode,
        int argument,
        String annotation,
        int stackSize,
        List<Datum> stackSnapshot
    ) {}
}
