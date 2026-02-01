package com.libreshockwave.tools.format;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.format.ScriptFormatUtils;
import com.libreshockwave.lingo.Opcode;

/**
 * Formats bytecode instructions for display.
 */
public final class InstructionFormatter {

    private InstructionFormatter() {}

    /**
     * Formats a single bytecode instruction for display.
     */
    public static String format(ScriptChunk.Handler.Instruction instr, ScriptChunk script, ScriptNamesChunk names) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%04d] %-16s", instr.offset(), instr.opcode().getMnemonic()));

        if (instr.rawOpcode() >= 0x40) {
            sb.append(" ");
            String argDesc = formatArgument(instr, script, names);
            sb.append(argDesc);
        }

        return sb.toString();
    }

    /**
     * Formats the argument of an instruction with contextual information.
     */
    public static String formatArgument(ScriptChunk.Handler.Instruction instr, ScriptChunk script, ScriptNamesChunk names) {
        int arg = instr.argument();
        var opcode = instr.opcode();
        String opName = opcode.name();

        // PUSH_CONS - literal constant (string, int, float)
        if (opcode == Opcode.PUSH_CONS) {
            if (arg >= 0 && arg < script.literals().size()) {
                var lit = script.literals().get(arg);
                String typeStr = ScriptFormatUtils.getLiteralTypeNameShort(lit.type());
                String valueStr = ScriptFormatUtils.formatLiteralValue(lit.value(), 40);
                return arg + " <" + typeStr + "> " + valueStr;
            }
        }

        // PUSH_SYMB - symbol name
        if (opcode == Opcode.PUSH_SYMB) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " #" + names.getName(arg);
            }
        }

        // GET/SET variables - resolve names
        if (opName.startsWith("GET_") || opName.startsWith("SET_")) {
            if (opcode == Opcode.GET_GLOBAL ||
                opcode == Opcode.SET_GLOBAL ||
                opcode == Opcode.GET_GLOBAL2 ||
                opcode == Opcode.SET_GLOBAL2 ||
                opcode == Opcode.GET_PROP ||
                opcode == Opcode.SET_PROP ||
                opcode == Opcode.GET_OBJ_PROP ||
                opcode == Opcode.SET_OBJ_PROP ||
                opcode == Opcode.GET_MOVIE_PROP ||
                opcode == Opcode.SET_MOVIE_PROP ||
                opcode == Opcode.GET_TOP_LEVEL_PROP ||
                opcode == Opcode.GET_CHAINED_PROP) {
                if (names != null && arg >= 0 && arg < names.names().size()) {
                    return arg + " (" + names.getName(arg) + ")";
                }
            }
        }

        // Call opcodes - show function/handler name
        if (opcode == Opcode.LOCAL_CALL ||
            opcode == Opcode.EXT_CALL ||
            opcode == Opcode.OBJ_CALL ||
            opcode == Opcode.OBJ_CALL_V4 ||
            opcode == Opcode.TELL_CALL) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " [" + names.getName(arg) + "]";
            }
        }

        // PUT/GET - resolve name
        if (opcode == Opcode.PUT ||
            opcode == Opcode.GET ||
            opcode == Opcode.SET) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " (" + names.getName(arg) + ")";
            }
        }

        // THE_BUILTIN - show builtin name
        if (opcode == Opcode.THE_BUILTIN) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " the " + names.getName(arg);
            }
        }

        // NEW_OBJ - parent script name
        if (opcode == Opcode.NEW_OBJ) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " new(" + names.getName(arg) + ")";
            }
        }

        // PUSH_VAR_REF - variable reference
        if (opcode == Opcode.PUSH_VAR_REF) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " @" + names.getName(arg);
            }
        }

        // Jump offsets - show target
        if (opcode.isJump()) {
            return arg + " -> offset " + arg;
        }

        return String.valueOf(arg);
    }
}
