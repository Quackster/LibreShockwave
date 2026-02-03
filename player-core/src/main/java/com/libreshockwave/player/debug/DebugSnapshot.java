package com.libreshockwave.player.debug;

import com.libreshockwave.vm.Datum;

import java.util.List;
import java.util.Map;

/**
 * Immutable capture of debugger state for UI display.
 * Created when the VM pauses at a breakpoint or step.
 */
public record DebugSnapshot(
    // Current instruction location
    int scriptId,
    String scriptName,
    String handlerName,
    int instructionOffset,
    int instructionIndex,
    String opcode,
    int argument,
    String annotation,

    // Handler bytecode (all instructions)
    List<InstructionDisplay> allInstructions,

    // Runtime state
    List<Datum> stack,
    Map<String, Datum> locals,
    Map<String, Datum> globals,
    List<Datum> arguments,
    Datum receiver,

    // Call stack (list of frames, most recent last)
    List<DebugController.CallFrame> callStack,

    // Watch expression results (evaluated when paused)
    List<WatchExpression> watchResults
) {
    /**
     * Constructor for backward compatibility (without watch results).
     */
    public DebugSnapshot(
        int scriptId,
        String scriptName,
        String handlerName,
        int instructionOffset,
        int instructionIndex,
        String opcode,
        int argument,
        String annotation,
        List<InstructionDisplay> allInstructions,
        List<Datum> stack,
        Map<String, Datum> locals,
        Map<String, Datum> globals,
        List<Datum> arguments,
        Datum receiver,
        List<DebugController.CallFrame> callStack
    ) {
        this(scriptId, scriptName, handlerName, instructionOffset, instructionIndex,
             opcode, argument, annotation, allInstructions, stack, locals, globals,
             arguments, receiver, callStack, List.of());
    }
    /**
     * Display information for a single instruction.
     */
    public record InstructionDisplay(
        int offset,
        int index,
        String opcode,
        int argument,
        String annotation,
        boolean hasBreakpoint
    ) {}
}
