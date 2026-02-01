package com.libreshockwave.vm.trace;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.Scope;
import com.libreshockwave.vm.TraceListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Helper for building trace information and annotations.
 */
public class TracingHelper {

    private final Function<Integer, String> nameResolver;

    public TracingHelper(Function<Integer, String> nameResolver) {
        this.nameResolver = nameResolver;
    }

    /**
     * Build instruction info for tracing.
     */
    public TraceListener.InstructionInfo buildInstructionInfo(Scope scope, ScriptChunk.Handler.Instruction instr) {
        String annotation = buildAnnotation(scope, instr);
        List<Datum> stackSnapshot = new ArrayList<>();
        // Capture up to 10 stack items
        for (int i = 0; i < Math.min(10, scope.stackSize()); i++) {
            stackSnapshot.add(scope.peek(i));
        }
        return new TraceListener.InstructionInfo(
            scope.getBytecodeIndex(),
            instr.offset(),
            instr.opcode().name(),
            instr.argument(),
            annotation,
            scope.stackSize(),
            stackSnapshot
        );
    }

    /**
     * Build handler info for tracing.
     */
    public TraceListener.HandlerInfo buildHandlerInfo(
            ScriptChunk script,
            ScriptChunk.Handler handler,
            List<Datum> args,
            Datum receiver,
            Map<String, Datum> globals) {
        String handlerName = nameResolver.apply(handler.nameId());
        String scriptName = script.getScriptName();
        String scriptType = script.getScriptType() != null ? script.getScriptType().name() : "UNKNOWN";
        return new TraceListener.HandlerInfo(
            handlerName,
            script.id(),
            scriptName,
            scriptType,
            args,
            receiver,
            new HashMap<>(globals),
            script.literals(),
            handler.localCount(),
            handler.argCount()
        );
    }

    /**
     * Build annotation string for an instruction.
     */
    public String buildAnnotation(Scope scope, ScriptChunk.Handler.Instruction instr) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        return switch (op) {
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> "<" + arg + ">";
            case PUSH_FLOAT32 -> "<" + Float.intBitsToFloat(arg) + ">";
            case PUSH_CONS -> {
                List<ScriptChunk.LiteralEntry> literals = scope.getScript().literals();
                if (arg >= 0 && arg < literals.size()) {
                    yield "<" + literals.get(arg).value() + ">";
                }
                yield "<literal#" + arg + ">";
            }
            case PUSH_SYMB -> "<#" + nameResolver.apply(arg) + ">";
            case GET_LOCAL, SET_LOCAL -> "<local" + arg + ">";
            case GET_PARAM -> "<param" + arg + ">";
            case GET_GLOBAL, SET_GLOBAL, GET_GLOBAL2, SET_GLOBAL2 -> "<" + nameResolver.apply(arg) + ">";
            case GET_PROP, SET_PROP -> "<me." + nameResolver.apply(arg) + ">";
            case LOCAL_CALL, EXT_CALL, OBJ_CALL -> "<" + nameResolver.apply(arg) + "()>";
            case JMP, JMP_IF_Z -> "<offset " + arg + " -> " + (instr.offset() + arg) + ">";
            case END_REPEAT -> "<back " + arg + " -> " + (instr.offset() - arg) + ">";
            default -> "";
        };
    }

    /**
     * Print instruction trace to console (dirplayer-rs format).
     */
    public void traceInstruction(TraceListener.InstructionInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--> [%3d] %-16s", info.offset(), info.opcode()));
        if (info.argument() != 0) {
            sb.append(String.format(" %d", info.argument()));
        }
        while (sb.length() < 38) {
            sb.append('.');
        }
        if (!info.annotation().isEmpty()) {
            sb.append(' ').append(info.annotation());
        }
        System.out.println(sb);
    }

    /**
     * Print handler entry trace to console.
     */
    public void traceHandlerEnter(TraceListener.HandlerInfo info) {
        String scriptIdent = formatScriptIdentifier(info);
        System.out.println("== Script: " + scriptIdent + " Handler: " + info.handlerName());
    }

    /**
     * Format script identifier for display.
     * Shows script name if available, otherwise falls back to type + id.
     */
    private String formatScriptIdentifier(TraceListener.HandlerInfo info) {
        String name = info.scriptName();
        if (name != null && !name.isEmpty()) {
            return "\"" + name + "\" (" + info.scriptType() + ")";
        }
        return info.scriptType() + " #" + info.scriptId();
    }

    /**
     * Print handler exit trace to console.
     */
    public void traceHandlerExit(TraceListener.HandlerInfo info, Datum returnValue) {
        if (!(returnValue instanceof Datum.Void)) {
            System.out.println("== " + info.handlerName() + " returned " + returnValue);
        }
    }
}
