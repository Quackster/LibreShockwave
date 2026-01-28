package com.libreshockwave.lingo;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;

import java.util.*;

/**
 * Lingo bytecode decompiler.
 * Converts bytecode instructions to Lingo source text.
 * Based on ProjectorRays implementation.
 */
public class LingoDecompiler {

    private static final String LINE_ENDING = "\r"; // Lingo line ending

    private final DirectorFile file;
    private final ScriptNamesChunk names;
    private final boolean dotSyntax;
    private final int version;

    // Stack for expression evaluation
    private final Deque<Node> stack = new ArrayDeque<>();

    public LingoDecompiler(DirectorFile file, ScriptNamesChunk names) {
        this.file = file;
        this.names = names;
        this.dotSyntax = false; // Use classic Lingo syntax
        this.version = file.getConfig() != null ? file.getConfig().directorVersion() : 1000;
    }

    /**
     * Decompile a script chunk to Lingo source text.
     */
    public String decompile(ScriptChunk script) {
        StringBuilder sb = new StringBuilder();

        // Add property declarations
        if (!script.properties().isEmpty()) {
            sb.append("property ");
            for (int i = 0; i < script.properties().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(getName(script.properties().get(i).nameId()));
            }
            sb.append(LINE_ENDING);
        }

        // Add global declarations
        if (!script.globals().isEmpty()) {
            sb.append("global ");
            for (int i = 0; i < script.globals().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(getName(script.globals().get(i).nameId()));
            }
            sb.append(LINE_ENDING);
        }

        // Decompile each handler
        for (int i = 0; i < script.handlers().size(); i++) {
            if (sb.length() > 0) {
                sb.append(LINE_ENDING);
            }
            sb.append(decompileHandler(script, script.handlers().get(i)));
        }

        return sb.toString();
    }

    /**
     * Decompile a single handler.
     */
    private String decompileHandler(ScriptChunk script, ScriptChunk.Handler handler) {
        StringBuilder sb = new StringBuilder();

        // Handler declaration
        sb.append("on ").append(getName(handler.nameId()));

        // Arguments
        if (!handler.argNameIds().isEmpty()) {
            sb.append(" ");
            for (int i = 0; i < handler.argNameIds().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(getName(handler.argNameIds().get(i)));
            }
        }
        sb.append(LINE_ENDING);

        // Reset stack for this handler
        stack.clear();

        // Process instructions
        List<ScriptChunk.Handler.Instruction> instructions = handler.instructions();
        int indent = 1;

        // Build control flow info
        Map<Integer, ControlFlowInfo> controlFlow = analyzeControlFlow(instructions);

        for (int i = 0; i < instructions.size(); i++) {
            ScriptChunk.Handler.Instruction instr = instructions.get(i);

            // Check if we need to close a block
            ControlFlowInfo cfInfo = controlFlow.get(instr.offset());
            if (cfInfo != null && cfInfo.isBlockEnd) {
                indent--;
                sb.append(getIndent(indent));
                sb.append(cfInfo.endKeyword);
                sb.append(LINE_ENDING);
            }

            // Check if this is the last return and skip it
            if (i == instructions.size() - 1 &&
                (instr.opcode() == Opcode.RET || instr.opcode() == Opcode.RET_FACTORY)) {
                break;
            }

            String line = translateInstruction(script, handler, instr, instructions, i);
            if (line != null && !line.isEmpty()) {
                // Check for block start
                boolean isBlockStart = false;
                String blockStartLine = line;

                if (line.startsWith("if ") || line.startsWith("repeat ")) {
                    isBlockStart = true;
                }

                sb.append(getIndent(indent));
                sb.append(blockStartLine);
                sb.append(LINE_ENDING);

                if (isBlockStart) {
                    indent++;
                }
            }

            // Check if we need to insert an else
            if (cfInfo != null && cfInfo.hasElse && cfInfo.elseOffset == instr.offset()) {
                indent--;
                sb.append(getIndent(indent));
                sb.append("else");
                sb.append(LINE_ENDING);
                indent++;
            }
        }

        // Close any remaining blocks
        while (indent > 1) {
            indent--;
            sb.append(getIndent(indent));
            sb.append("end");
            sb.append(LINE_ENDING);
        }

        sb.append("end");

        return sb.toString();
    }

    /**
     * Analyze control flow to identify loops and conditionals.
     */
    private Map<Integer, ControlFlowInfo> analyzeControlFlow(List<ScriptChunk.Handler.Instruction> instructions) {
        Map<Integer, ControlFlowInfo> result = new HashMap<>();

        for (int i = 0; i < instructions.size(); i++) {
            ScriptChunk.Handler.Instruction instr = instructions.get(i);

            if (instr.opcode() == Opcode.JMP_IF_Z) {
                // This could be an if statement or a loop
                int targetOffset = instr.offset() + instr.argument();

                // Look backwards to see if there's an endrepeat pointing to before this jmpifz
                // That would indicate this is a repeat while
                boolean isLoop = false;
                for (int j = i + 1; j < instructions.size(); j++) {
                    if (instructions.get(j).offset() >= targetOffset) break;
                    if (instructions.get(j).opcode() == Opcode.END_REPEAT) {
                        int loopStart = instructions.get(j).offset() - instructions.get(j).argument();
                        if (loopStart <= instr.offset()) {
                            isLoop = true;
                            break;
                        }
                    }
                }

                ControlFlowInfo info = new ControlFlowInfo();
                info.isLoop = isLoop;
                info.endOffset = targetOffset;
                info.endKeyword = isLoop ? "end repeat" : "end if";
                result.put(targetOffset, info);
            }
        }

        return result;
    }

    /**
     * Translate a single instruction to Lingo source.
     */
    private String translateInstruction(ScriptChunk script, ScriptChunk.Handler handler,
                                         ScriptChunk.Handler.Instruction instr,
                                         List<ScriptChunk.Handler.Instruction> allInstructions,
                                         int instrIndex) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        switch (op) {
            case RET:
            case RET_FACTORY:
                return "exit";

            case PUSH_ZERO:
                stack.push(new LiteralNode("0"));
                return null;

            case PUSH_INT8:
            case PUSH_INT16:
            case PUSH_INT32:
                stack.push(new LiteralNode(String.valueOf(arg)));
                return null;

            case PUSH_FLOAT32:
                float f = Float.intBitsToFloat(arg);
                stack.push(new LiteralNode(floatToString(f)));
                return null;

            case PUSH_SYMB:
                stack.push(new LiteralNode("#" + getName(arg)));
                return null;

            case PUSH_CONS:
                Object literal = getLiteral(script, arg);
                stack.push(new LiteralNode(literalToString(literal)));
                return null;

            case PUSH_ARG_LIST:
            case PUSH_ARG_LIST_NO_RET:
                List<Node> args = new ArrayList<>();
                for (int i = 0; i < arg; i++) {
                    if (!stack.isEmpty()) {
                        args.add(0, stack.pop());
                    }
                }
                stack.push(new ArgListNode(args));
                return null;

            case PUSH_LIST:
                Node listArg = stack.isEmpty() ? new LiteralNode("[]") : stack.pop();
                if (listArg instanceof ArgListNode) {
                    StringBuilder lb = new StringBuilder("[");
                    for (int i = 0; i < ((ArgListNode) listArg).args.size(); i++) {
                        if (i > 0) lb.append(", ");
                        lb.append(((ArgListNode) listArg).args.get(i).toString());
                    }
                    lb.append("]");
                    stack.push(new LiteralNode(lb.toString()));
                } else {
                    stack.push(new LiteralNode("[" + listArg + "]"));
                }
                return null;

            case PUSH_PROP_LIST:
                Node propListArg = stack.isEmpty() ? new LiteralNode("[:]") : stack.pop();
                if (propListArg instanceof ArgListNode) {
                    StringBuilder pb = new StringBuilder("[");
                    List<Node> propArgs = ((ArgListNode) propListArg).args;
                    for (int i = 0; i < propArgs.size(); i += 2) {
                        if (i > 0) pb.append(", ");
                        pb.append(propArgs.get(i)).append(": ");
                        if (i + 1 < propArgs.size()) {
                            pb.append(propArgs.get(i + 1));
                        }
                    }
                    pb.append("]");
                    stack.push(new LiteralNode(pb.toString()));
                } else {
                    stack.push(new LiteralNode("[:]"));
                }
                return null;

            case GET_GLOBAL:
            case GET_GLOBAL2:
            case GET_TOP_LEVEL_PROP:
                stack.push(new VarNode(getName(arg)));
                return null;

            case GET_PROP:
                stack.push(new VarNode(getName(arg)));
                return null;

            case GET_PARAM:
                stack.push(new VarNode(getArgName(handler, arg)));
                return null;

            case GET_LOCAL:
                stack.push(new VarNode(getLocalName(handler, arg)));
                return null;

            case SET_GLOBAL:
            case SET_GLOBAL2:
                Node gval = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                return getName(arg) + " = " + gval;

            case SET_PROP:
                Node pval = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                return getName(arg) + " = " + pval;

            case SET_PARAM:
                Node aval = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                return getArgName(handler, arg) + " = " + aval;

            case SET_LOCAL:
                Node lval = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                return getLocalName(handler, arg) + " = " + lval;

            case GET_OBJ_PROP:
            case GET_CHAINED_PROP:
                Node obj = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                stack.push(new PropAccessNode(obj, getName(arg)));
                return null;

            case SET_OBJ_PROP:
                Node oval = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                Node oobj = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                return oobj + "." + getName(arg) + " = " + oval;

            case ADD:
                return binaryOp(" + ");
            case SUB:
                return binaryOp(" - ");
            case MUL:
                return binaryOp(" * ");
            case DIV:
                return binaryOp(" / ");
            case MOD:
                return binaryOp(" mod ");
            case EQ:
                return binaryOp(" = ");
            case NT_EQ:
                return binaryOp(" <> ");
            case LT:
                return binaryOp(" < ");
            case LT_EQ:
                return binaryOp(" <= ");
            case GT:
                return binaryOp(" > ");
            case GT_EQ:
                return binaryOp(" >= ");
            case AND:
                return binaryOp(" and ");
            case OR:
                return binaryOp(" or ");
            case JOIN_STR:
                return binaryOp(" & ");
            case JOIN_PAD_STR:
                return binaryOp(" && ");
            case CONTAINS_STR:
            case CONTAINS_0_STR:
                return binaryOp(" contains ");

            case INV:
                Node invArg = stack.isEmpty() ? new LiteralNode("0") : stack.pop();
                stack.push(new LiteralNode("-" + invArg));
                return null;

            case NOT:
                Node notArg = stack.isEmpty() ? new LiteralNode("0") : stack.pop();
                stack.push(new LiteralNode("not " + notArg));
                return null;

            case EXT_CALL:
            case TELL_CALL:
                String funcName = getName(arg);
                Node argList = stack.isEmpty() ? new ArgListNode(new ArrayList<>()) : stack.pop();
                return formatCall(funcName, argList);

            case LOCAL_CALL:
                if (arg >= 0 && arg < script.handlers().size()) {
                    String handlerName = getName(script.handlers().get(arg).nameId());
                    Node lcArgList = stack.isEmpty() ? new ArgListNode(new ArrayList<>()) : stack.pop();
                    return formatCall(handlerName, lcArgList);
                }
                return "-- local call " + arg;

            case OBJ_CALL:
                String method = getName(arg);
                Node objArgList = stack.isEmpty() ? new ArgListNode(new ArrayList<>()) : stack.pop();
                return formatObjCall(method, objArgList);

            case JMP:
                // Usually skip for control flow, could be exit repeat or next repeat
                return null;

            case JMP_IF_Z:
                Node cond = stack.isEmpty() ? new LiteralNode("TRUE") : stack.pop();
                return "if " + cond + " then";

            case END_REPEAT:
                return null; // Handled by control flow analysis

            case POP:
                for (int i = 0; i < arg && !stack.isEmpty(); i++) {
                    stack.pop();
                }
                return null;

            case THE_BUILTIN:
                if (!stack.isEmpty()) stack.pop(); // Pop empty arglist
                stack.push(new LiteralNode("the " + getName(arg)));
                return null;

            case GET_MOVIE_PROP:
                stack.push(new LiteralNode("the " + getName(arg)));
                return null;

            case SET_MOVIE_PROP:
                Node mpval = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                return "set the " + getName(arg) + " to " + mpval;

            case NEW_OBJ:
                String objType = getName(arg);
                Node newArgs = stack.isEmpty() ? new ArgListNode(new ArrayList<>()) : stack.pop();
                stack.push(new LiteralNode("new(" + objType + formatArgList(newArgs) + ")"));
                return null;

            case PUT:
                int putType = (arg >> 4) & 0xF;
                int varType = arg & 0xF;
                Node putVal = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
                Node putVar = readVar(handler, varType);
                String putKw = putType == 1 ? "after" : putType == 2 ? "before" : "into";
                return "put " + putVal + " " + putKw + " " + putVar;

            case SWAP:
                if (stack.size() >= 2) {
                    Node a = stack.pop();
                    Node b = stack.pop();
                    stack.push(a);
                    stack.push(b);
                }
                return null;

            case PEEK:
                if (!stack.isEmpty()) {
                    stack.push(stack.peek());
                }
                return null;

            default:
                // Unknown opcode - push a placeholder
                return "-- " + op.getMnemonic() + " " + arg;
        }
    }

    private String binaryOp(String operator) {
        Node b = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
        Node a = stack.isEmpty() ? new LiteralNode("VOID") : stack.pop();
        stack.push(new LiteralNode("(" + a + operator + b + ")"));
        return null;
    }

    private Node readVar(ScriptChunk.Handler handler, int varType) {
        switch (varType) {
            case 1: // global
            case 2:
                Node gid = stack.isEmpty() ? new LiteralNode("0") : stack.pop();
                return new LiteralNode(getName(Integer.parseInt(gid.toString())));
            case 3: // property
                Node pid = stack.isEmpty() ? new LiteralNode("0") : stack.pop();
                return new LiteralNode(getName(Integer.parseInt(pid.toString())));
            case 4: // arg
                Node aid = stack.isEmpty() ? new LiteralNode("0") : stack.pop();
                int argIdx = Integer.parseInt(aid.toString()) / variableMultiplier();
                return new LiteralNode(getArgName(handler, argIdx));
            case 5: // local
                Node lid = stack.isEmpty() ? new LiteralNode("0") : stack.pop();
                int localIdx = Integer.parseInt(lid.toString()) / variableMultiplier();
                return new LiteralNode(getLocalName(handler, localIdx));
            default:
                return new LiteralNode("UNKNOWN_VAR");
        }
    }

    private int variableMultiplier() {
        if (file.isCapitalX()) return 1;
        if (version >= 500) return 8;
        return 6;
    }

    private String formatCall(String name, Node argList) {
        String args = formatArgList(argList);
        if (args.isEmpty()) {
            return name;
        } else {
            return name + args;
        }
    }

    private String formatObjCall(String method, Node argList) {
        if (argList instanceof ArgListNode) {
            List<Node> args = ((ArgListNode) argList).args;
            if (!args.isEmpty()) {
                Node receiver = args.get(0);
                StringBuilder sb = new StringBuilder();
                sb.append(receiver).append(".").append(method);
                if (args.size() > 1) {
                    sb.append("(");
                    for (int i = 1; i < args.size(); i++) {
                        if (i > 1) sb.append(", ");
                        sb.append(args.get(i));
                    }
                    sb.append(")");
                }
                return sb.toString();
            }
        }
        return method + "()";
    }

    private String formatArgList(Node argList) {
        if (argList instanceof ArgListNode) {
            List<Node> args = ((ArgListNode) argList).args;
            if (args.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i));
            }
            sb.append(")");
            return sb.toString();
        }
        return "(" + argList + ")";
    }

    private String getName(int id) {
        if (names != null) {
            String name = names.getName(id);
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return "name_" + id;
    }

    private String getArgName(ScriptChunk.Handler handler, int index) {
        int multiplier = variableMultiplier();
        int idx = index / multiplier;
        if (idx >= 0 && idx < handler.argNameIds().size()) {
            return getName(handler.argNameIds().get(idx));
        }
        return "arg_" + idx;
    }

    private String getLocalName(ScriptChunk.Handler handler, int index) {
        int multiplier = variableMultiplier();
        int idx = index / multiplier;
        if (idx >= 0 && idx < handler.localNameIds().size()) {
            return getName(handler.localNameIds().get(idx));
        }
        return "local_" + idx;
    }

    private Object getLiteral(ScriptChunk script, int index) {
        int idx = index / variableMultiplier();
        if (idx >= 0 && idx < script.literals().size()) {
            return script.literals().get(idx).value();
        }
        return null;
    }

    private String getLiteralString(ScriptChunk script, int index) {
        Object lit = getLiteral(script, index);
        if (lit instanceof String) {
            return (String) lit;
        }
        return "";
    }

    private String literalToString(Object literal) {
        if (literal == null) return "VOID";
        if (literal instanceof String) {
            return "\"" + escapeString((String) literal) + "\"";
        }
        if (literal instanceof Number) {
            if (literal instanceof Double || literal instanceof Float) {
                return floatToString(((Number) literal).doubleValue());
            }
            return literal.toString();
        }
        if (literal instanceof byte[]) {
            return "<binary data>";
        }
        return literal.toString();
    }

    private String escapeString(String s) {
        return s.replace("\"", "\\\"");
    }

    private String floatToString(double f) {
        if (f == Math.floor(f) && !Double.isInfinite(f)) {
            return String.format("%.1f", f);
        }
        return String.valueOf(f);
    }

    private String getIndent(int level) {
        return "  ".repeat(level);
    }

    // AST node classes
    private interface Node {
        String toString();
    }

    private record LiteralNode(String value) implements Node {
        @Override
        public String toString() {
            return value;
        }
    }

    private record VarNode(String name) implements Node {
        @Override
        public String toString() {
            return name;
        }
    }

    private record PropAccessNode(Node obj, String prop) implements Node {
        @Override
        public String toString() {
            return obj + "." + prop;
        }
    }

    private record ArgListNode(List<Node> args) implements Node {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i));
            }
            return sb.toString();
        }
    }

    private static class ControlFlowInfo {
        boolean isBlockEnd;
        boolean isLoop;
        boolean hasElse;
        int endOffset;
        int elseOffset;
        String endKeyword;
    }
}
