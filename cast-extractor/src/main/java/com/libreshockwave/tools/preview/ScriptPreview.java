package com.libreshockwave.tools.preview;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.tools.format.InstructionFormatter;
import com.libreshockwave.tools.model.CastMemberInfo;
import com.libreshockwave.tools.scanning.MemberResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates script preview content with bytecode disassembly.
 */
public class ScriptPreview {

    /**
     * Formats script content for display.
     */
    public String format(DirectorFile dirFile, CastMemberInfo memberInfo) {
        ScriptChunk script = MemberResolver.findScriptForMember(dirFile, memberInfo.member());
        ScriptNamesChunk names = dirFile.getScriptNames();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SCRIPT: ").append(memberInfo.name()).append(" ===\n\n");
        sb.append("Member ID: ").append(memberInfo.memberNum()).append("\n");

        if (script == null) {
            sb.append("\n[No bytecode found for this script member]\n");
        } else {
            sb.append("Script Type: ").append(MemberResolver.getScriptTypeName(script.scriptType())).append("\n");
            sb.append("Behavior Flags: 0x").append(Integer.toHexString(script.behaviorFlags())).append("\n\n");

            // Properties
            if (!script.properties().isEmpty()) {
                sb.append("--- PROPERTIES ---\n");
                for (ScriptChunk.PropertyEntry prop : script.properties()) {
                    String propName = names != null ? names.getName(prop.nameId()) : "#" + prop.nameId();
                    sb.append("  property ").append(propName).append("\n");
                }
                sb.append("\n");
            }

            // Globals
            if (!script.globals().isEmpty()) {
                sb.append("--- GLOBALS ---\n");
                for (ScriptChunk.GlobalEntry global : script.globals()) {
                    String globalName = names != null ? names.getName(global.nameId()) : "#" + global.nameId();
                    sb.append("  global ").append(globalName).append("\n");
                }
                sb.append("\n");
            }

            // Handlers
            sb.append("--- HANDLERS (").append(script.handlers().size()).append(") ---\n\n");
            for (ScriptChunk.Handler handler : script.handlers()) {
                formatHandler(sb, handler, script, names);
            }

            // Literals
            if (!script.literals().isEmpty()) {
                sb.append("--- LITERALS (").append(script.literals().size()).append(") ---\n");
                int idx = 0;
                for (ScriptChunk.LiteralEntry lit : script.literals()) {
                    String typeStr = switch (lit.type()) {
                        case 1 -> "string";
                        case 4 -> "int";
                        case 9 -> "float";
                        default -> "type" + lit.type();
                    };
                    String valueStr = lit.value() instanceof String ?
                            "\"" + lit.value() + "\"" : String.valueOf(lit.value());
                    sb.append("  [").append(idx++).append("] ").append(typeStr).append(": ").append(valueStr).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private void formatHandler(StringBuilder sb, ScriptChunk.Handler handler,
                               ScriptChunk script, ScriptNamesChunk names) {
        String handlerName = names != null ? names.getName(handler.nameId()) : "#" + handler.nameId();

        // Build argument list
        List<String> argNames = new ArrayList<>();
        for (int argId : handler.argNameIds()) {
            argNames.add(names != null ? names.getName(argId) : "#" + argId);
        }
        String argsStr = String.join(", ", argNames);

        sb.append("on ").append(handlerName);
        if (!argsStr.isEmpty()) {
            sb.append(" ").append(argsStr);
        }
        sb.append("\n");

        // Local variables
        if (!handler.localNameIds().isEmpty()) {
            List<String> localNames = new ArrayList<>();
            for (int localId : handler.localNameIds()) {
                localNames.add(names != null ? names.getName(localId) : "#" + localId);
            }
            sb.append("  -- locals: ").append(String.join(", ", localNames)).append("\n");
        }

        // Bytecode instructions
        sb.append("  -- bytecode (").append(handler.bytecodeLength()).append(" bytes, ")
                .append(handler.instructions().size()).append(" instructions):\n");

        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            sb.append("    ").append(InstructionFormatter.format(instr, script, names)).append("\n");
        }

        sb.append("end\n\n");
    }
}
