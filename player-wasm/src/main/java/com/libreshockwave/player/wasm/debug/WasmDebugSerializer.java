package com.libreshockwave.player.wasm.debug;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.debug.*;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import java.util.List;
import java.util.Map;

/**
 * Serializes debug data structures to JSON strings for the WASM debug UI.
 * Uses simple string concatenation (no JSON library) for TeaVM compatibility.
 */
public final class WasmDebugSerializer {

    private WasmDebugSerializer() {}

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Serialize a DebugSnapshot to JSON.
     */
    public static String serializeSnapshot(DebugSnapshot snapshot) {
        if (snapshot == null) return "null";

        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');

        sb.append("\"scriptId\":").append(snapshot.scriptId());
        sb.append(",\"scriptName\":\"").append(escapeJson(snapshot.scriptName())).append('"');
        sb.append(",\"handlerName\":\"").append(escapeJson(snapshot.handlerName())).append('"');
        sb.append(",\"instructionOffset\":").append(snapshot.instructionOffset());
        sb.append(",\"instructionIndex\":").append(snapshot.instructionIndex());
        sb.append(",\"opcode\":\"").append(escapeJson(snapshot.opcode())).append('"');
        sb.append(",\"argument\":").append(snapshot.argument());
        sb.append(",\"annotation\":\"").append(escapeJson(snapshot.annotation())).append('"');

        // Stack
        sb.append(",\"stack\":");
        serializeStack(sb, snapshot.stack());

        // Locals
        sb.append(",\"locals\":");
        serializeVariables(sb, snapshot.locals());

        // Globals
        sb.append(",\"globals\":");
        serializeVariables(sb, snapshot.globals());

        // Call stack
        sb.append(",\"callStack\":");
        serializeCallStack(sb, snapshot.callStack());

        // Watches
        sb.append(",\"watches\":");
        serializeWatches(sb, snapshot.watchResults());

        sb.append('}');
        return sb.toString();
    }

    /**
     * Serialize a list of scripts to JSON.
     */
    public static String serializeScriptList(List<ScriptChunk> scripts, DirectorFile file) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('[');
        for (int i = 0; i < scripts.size(); i++) {
            if (i > 0) sb.append(',');
            ScriptChunk script = scripts.get(i);
            sb.append("{\"id\":").append(script.id());
            sb.append(",\"displayName\":\"").append(escapeJson(script.getDisplayName())).append('"');
            sb.append(",\"scriptType\":\"").append(escapeJson(
                script.getScriptType() != null ? script.getScriptType().name() : "UNKNOWN"
            )).append('"');

            // Handlers
            sb.append(",\"handlers\":[");
            List<ScriptChunk.Handler> handlers = script.handlers();
            if (handlers != null) {
                for (int j = 0; j < handlers.size(); j++) {
                    if (j > 0) sb.append(',');
                    ScriptChunk.Handler h = handlers.get(j);
                    String handlerName = script.getHandlerName(h);
                    sb.append("{\"index\":").append(j);
                    sb.append(",\"name\":\"").append(escapeJson(handlerName)).append('"');
                    sb.append(",\"argCount\":").append(h.argCount());
                    sb.append(",\"localCount\":").append(h.localCount());
                    sb.append(",\"instructionCount\":").append(
                        h.instructions() != null ? h.instructions().size() : 0
                    );
                    sb.append('}');
                }
            }
            sb.append("]}");
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Serialize handler bytecode as JSON array of instructions.
     */
    public static String serializeHandlerBytecode(
            ScriptChunk script, ScriptChunk.Handler handler, BreakpointManager bpManager) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('[');

        List<ScriptChunk.Handler.Instruction> instructions = handler.instructions();
        if (instructions != null) {
            for (int i = 0; i < instructions.size(); i++) {
                if (i > 0) sb.append(',');
                ScriptChunk.Handler.Instruction instr = instructions.get(i);
                sb.append("{\"index\":").append(i);
                sb.append(",\"offset\":").append(instr.offset());
                sb.append(",\"opcode\":\"").append(escapeJson(
                    instr.opcode() != null ? instr.opcode().getMnemonic() : "???"
                )).append('"');
                sb.append(",\"argument\":").append(instr.argument());

                // Annotation (resolve literals, names, etc.)
                String annotation = resolveAnnotation(script, handler, instr);
                if (annotation != null && !annotation.isEmpty()) {
                    sb.append(",\"annotation\":\"").append(escapeJson(annotation)).append('"');
                }

                // Breakpoint info
                boolean hasBp = bpManager != null &&
                    bpManager.hasBreakpoint(script.id(), instr.offset());
                sb.append(",\"hasBreakpoint\":").append(hasBp);

                if (hasBp) {
                    Breakpoint bp = bpManager.getBreakpoint(script.id(), instr.offset());
                    sb.append(",\"bpEnabled\":").append(bp != null && bp.enabled());
                }

                sb.append('}');
            }
        }

        sb.append(']');
        return sb.toString();
    }

    /**
     * Serialize handler details as JSON overview object.
     */
    public static String serializeHandlerDetails(ScriptChunk script, ScriptChunk.Handler handler) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');

        String handlerName = script.getHandlerName(handler);
        sb.append("\"name\":\"").append(escapeJson(handlerName)).append('"');
        sb.append(",\"scriptId\":").append(script.id());
        sb.append(",\"scriptName\":\"").append(escapeJson(script.getDisplayName())).append('"');
        sb.append(",\"argCount\":").append(handler.argCount());
        sb.append(",\"localCount\":").append(handler.localCount());
        sb.append(",\"globalsCount\":").append(handler.globalsCount());
        sb.append(",\"bytecodeLength\":").append(handler.bytecodeLength());
        sb.append(",\"instructionCount\":").append(
            handler.instructions() != null ? handler.instructions().size() : 0
        );

        // Arg names
        sb.append(",\"argNames\":[");
        if (handler.argNameIds() != null) {
            for (int i = 0; i < handler.argNameIds().size(); i++) {
                if (i > 0) sb.append(',');
                String name = resolveName(script, handler.argNameIds().get(i));
                sb.append('"').append(escapeJson(name)).append('"');
            }
        }
        sb.append(']');

        // Local names
        sb.append(",\"localNames\":[");
        if (handler.localNameIds() != null) {
            for (int i = 0; i < handler.localNameIds().size(); i++) {
                if (i > 0) sb.append(',');
                String name = resolveName(script, handler.localNameIds().get(i));
                sb.append('"').append(escapeJson(name)).append('"');
            }
        }
        sb.append(']');

        // Literals
        sb.append(",\"literals\":[");
        if (script.literals() != null) {
            for (int i = 0; i < script.literals().size(); i++) {
                if (i > 0) sb.append(',');
                ScriptChunk.LiteralEntry lit = script.literals().get(i);
                sb.append("{\"index\":").append(i);
                sb.append(",\"type\":").append(lit.type());
                sb.append(",\"value\":\"").append(escapeJson(
                    lit.value() != null ? lit.value().toString() : "null"
                )).append("\"}");
            }
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    /**
     * Serialize variables map to JSON array.
     */
    public static String serializeVariablesStandalone(Map<String, Datum> vars) {
        StringBuilder sb = new StringBuilder(512);
        serializeVariables(sb, vars);
        return sb.toString();
    }

    /**
     * Serialize watch expressions to JSON array.
     */
    public static String serializeWatchesStandalone(List<WatchExpression> watches) {
        StringBuilder sb = new StringBuilder(512);
        serializeWatches(sb, watches);
        return sb.toString();
    }

    // === Internal serialization helpers ===

    private static void serializeStack(StringBuilder sb, List<Datum> stack) {
        sb.append('[');
        if (stack != null) {
            for (int i = 0; i < stack.size(); i++) {
                if (i > 0) sb.append(',');
                Datum d = stack.get(i);
                sb.append("{\"index\":").append(i);
                sb.append(",\"type\":\"").append(escapeJson(DatumFormatter.getTypeName(d))).append('"');
                sb.append(",\"value\":\"").append(escapeJson(DatumFormatter.format(d))).append('"');
                sb.append(",\"detailed\":").append(DatumFormatter.formatDetailed(d, 0));
                sb.append('}');
            }
        }
        sb.append(']');
    }

    private static void serializeVariables(StringBuilder sb, Map<String, Datum> vars) {
        sb.append('[');
        if (vars != null) {
            int i = 0;
            for (Map.Entry<String, Datum> entry : vars.entrySet()) {
                if (i > 0) sb.append(',');
                sb.append("{\"name\":\"").append(escapeJson(entry.getKey())).append('"');
                sb.append(",\"type\":\"").append(escapeJson(DatumFormatter.getTypeName(entry.getValue()))).append('"');
                sb.append(",\"value\":\"").append(escapeJson(DatumFormatter.format(entry.getValue()))).append('"');
                sb.append(",\"detailed\":").append(DatumFormatter.formatDetailed(entry.getValue(), 0));
                sb.append('}');
                i++;
            }
        }
        sb.append(']');
    }

    private static void serializeCallStack(StringBuilder sb, List<DebugController.CallFrame> callStack) {
        sb.append('[');
        if (callStack != null) {
            for (int i = 0; i < callStack.size(); i++) {
                if (i > 0) sb.append(',');
                DebugController.CallFrame frame = callStack.get(i);
                sb.append("{\"scriptId\":").append(frame.scriptId());
                sb.append(",\"scriptName\":\"").append(escapeJson(frame.scriptName())).append('"');
                sb.append(",\"handlerName\":\"").append(escapeJson(frame.handlerName())).append('"');
                sb.append('}');
            }
        }
        sb.append(']');
    }

    private static void serializeWatches(StringBuilder sb, List<WatchExpression> watches) {
        sb.append('[');
        if (watches != null) {
            for (int i = 0; i < watches.size(); i++) {
                if (i > 0) sb.append(',');
                WatchExpression w = watches.get(i);
                sb.append("{\"id\":\"").append(escapeJson(w.id())).append('"');
                sb.append(",\"expression\":\"").append(escapeJson(w.expression())).append('"');
                sb.append(",\"type\":\"").append(escapeJson(w.getTypeName())).append('"');
                sb.append(",\"value\":\"").append(escapeJson(w.getResultDisplay())).append('"');
                sb.append(",\"hasError\":").append(w.hasError());
                sb.append('}');
            }
        }
        sb.append(']');
    }

    private static String resolveAnnotation(ScriptChunk script, ScriptChunk.Handler handler,
                                             ScriptChunk.Handler.Instruction instr) {
        if (instr.opcode() == null) return "";

        String mnemonic = instr.opcode().getMnemonic();
        int arg = instr.argument();

        // Resolve name-based opcodes
        if (mnemonic.contains("CALL") || mnemonic.equals("EXT_CALL") ||
            mnemonic.contains("GET") || mnemonic.contains("SET")) {
            String name = resolveName(script, arg);
            if (name != null && !name.startsWith("name#")) {
                return "\u2192 " + name;
            }
        }

        // Resolve literal references
        if (mnemonic.equals("PUSH_CONS")) {
            if (script.literals() != null && arg >= 0 && arg < script.literals().size()) {
                Object val = script.literals().get(arg).value();
                return val != null ? "= " + val : "";
            }
        }

        return "";
    }

    private static String resolveName(ScriptChunk script, int nameId) {
        if (script.file() != null && script.file().getScriptNames() != null) {
            return script.file().getScriptNames().getName(nameId);
        }
        return "name#" + nameId;
    }
}
