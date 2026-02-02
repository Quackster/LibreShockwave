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
    Datum receiver
) {
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
