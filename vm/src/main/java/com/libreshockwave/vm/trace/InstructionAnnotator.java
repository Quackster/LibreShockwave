package com.libreshockwave.vm.trace;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;

import java.util.List;

/**
 * Builds human-readable annotations for Lingo bytecode instructions.
 * Shared utility used by both the debugger UI and tracing system.
 */
public final class InstructionAnnotator {

    private InstructionAnnotator() {
        // Utility class
    }

    /**
     * Build annotation string for an instruction.
     *
     * @param script       the script containing the instruction
     * @param handler      the handler containing the instruction (used for local/param name resolution)
     * @param instr        the instruction to annotate
     * @param resolveNames if true, resolve local/param variable names from handler metadata
     * @return human-readable annotation string, or empty string if no annotation applies
     */
    public static String annotate(ScriptChunk script, ScriptChunk.Handler handler,
                                   ScriptChunk.Handler.Instruction instr, boolean resolveNames) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        return switch (op) {
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> "<" + arg + ">";
            case PUSH_FLOAT32 -> "<" + Float.intBitsToFloat(arg) + ">";
            case PUSH_CONS -> {
                List<ScriptChunk.LiteralEntry> literals = script.literals();
                if (arg >= 0 && arg < literals.size()) {
                    yield "<" + literals.get(arg).value() + ">";
                }
                yield "<literal#" + arg + ">";
            }
            case PUSH_SYMB -> "<#" + script.resolveName(arg) + ">";
            case GET_LOCAL, SET_LOCAL -> {
                if (resolveNames && handler != null && arg >= 0 && arg < handler.localNameIds().size()) {
                    yield "<" + script.resolveName(handler.localNameIds().get(arg)) + ">";
                }
                yield "<local" + arg + ">";
            }
            case GET_PARAM -> {
                if (resolveNames && handler != null && arg >= 0 && arg < handler.argNameIds().size()) {
                    yield "<" + script.resolveName(handler.argNameIds().get(arg)) + ">";
                }
                yield "<param" + arg + ">";
            }
            case GET_GLOBAL, SET_GLOBAL, GET_GLOBAL2, SET_GLOBAL2 -> "<" + script.resolveName(arg) + ">";
            case GET_PROP, SET_PROP -> "<me." + script.resolveName(arg) + ">";
            case LOCAL_CALL -> {
                var handlers = script.handlers();
                if (arg >= 0 && arg < handlers.size()) {
                    yield "<" + script.getHandlerName(handlers.get(arg)) + "()>";
                }
                yield "<handler#" + arg + "()>";
            }
            case EXT_CALL, OBJ_CALL -> "<" + script.resolveName(arg) + "()>";
            case JMP, JMP_IF_Z -> "<offset " + arg + " -> " + (instr.offset() + arg) + ">";
            case END_REPEAT -> "<back " + arg + " -> " + (instr.offset() - arg) + ">";
            default -> "";
        };
    }

    /**
     * Build annotation string without local/param name resolution.
     * Convenience method for tracing where handler context may not be available.
     *
     * @param script the script containing the instruction
     * @param instr  the instruction to annotate
     * @return human-readable annotation string
     */
    public static String annotate(ScriptChunk script, ScriptChunk.Handler.Instruction instr) {
        return annotate(script, null, instr, false);
    }
}
