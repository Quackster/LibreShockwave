package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.*;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;
import com.libreshockwave.net.NetTask;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.util.*;

/**
 * Lingo bytecode virtual machine.
 * Executes Director Lingo scripts.
 */
public class LingoVM {

    private final DirectorFile file;
    private final Map<String, Datum> globals;
    private final Deque<Datum> stack;
    private final Deque<Scope> callStack;
    private final Map<String, BuiltinHandler> builtins;

    private boolean halted;
    private int maxInstructions = 100000;
    private NetManager netManager;
    private CastManager castManager;

    // Debug mode - when enabled, logs execution details
    private boolean debugMode = false;
    private int debugIndent = 0;
    private DebugOutputCallback debugOutputCallback;

    @FunctionalInterface
    public interface DebugOutputCallback {
        void onDebugOutput(String message);
    }

    public void setDebugOutputCallback(DebugOutputCallback callback) {
        this.debugOutputCallback = callback;
    }

    @FunctionalInterface
    public interface BuiltinHandler {
        Datum call(LingoVM vm, List<Datum> args);
    }

    public LingoVM(DirectorFile file) {
        this.file = file;
        this.globals = new HashMap<>();
        this.stack = new ArrayDeque<>();
        this.callStack = new ArrayDeque<>();
        this.builtins = new HashMap<>();
        this.halted = false;

        registerBuiltins();
    }

    // Public API

    public DirectorFile getFile() {
        return file;
    }

    public Datum getGlobal(String name) {
        return globals.getOrDefault(name, Datum.voidValue());
    }

    public void setGlobal(String name, Datum value) {
        globals.put(name, value);
    }

    public Map<String, Datum> getAllGlobals() {
        return Collections.unmodifiableMap(globals);
    }

    public void halt() {
        halted = true;
    }

    public void setMaxInstructions(int max) {
        this.maxInstructions = max;
    }

    public void setNetManager(NetManager netManager) {
        this.netManager = netManager;
    }

    public NetManager getNetManager() {
        return netManager;
    }

    public void setCastManager(CastManager castManager) {
        this.castManager = castManager;
    }

    public CastManager getCastManager() {
        return castManager;
    }

    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    private void debugLog(String message) {
        if (!debugMode) return;
        String indent = "  ".repeat(debugIndent);
        String output = "[VM] " + indent + message;
        if (debugOutputCallback != null) {
            debugOutputCallback.onDebugOutput(output);
        } else {
            System.out.println(output);
        }
    }

    private String formatStack() {
        if (stack.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (Datum d : stack) {
            if (count > 0) sb.append(", ");
            if (count >= 8) {
                sb.append("... (").append(stack.size() - count).append(" more)");
                break;
            }
            sb.append(formatDatum(d));
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    private String formatDatum(Datum d) {
        if (d == null) return "null";
        if (d.isVoid()) return "VOID";
        if (d.isInt()) return String.valueOf(d.intValue());
        if (d.isFloat()) return String.format("%.2f", d.floatValue());
        if (d.isString()) {
            String s = d.stringValue();
            if (s.length() > 20) s = s.substring(0, 20) + "...";
            return "\"" + s + "\"";
        }
        if (d.isSymbol()) return "#" + d.stringValue();
        if (d instanceof Datum.DList l) return "list(" + l.count() + ")";
        if (d instanceof Datum.PropList p) return "propList(" + p.count() + ")";
        if (d instanceof Datum.ArgList a) return "args(" + a.args().size() + ")";
        if (d instanceof Datum.ArgListNoRet a) return "argsNoRet(" + a.args().size() + ")";
        return d.getClass().getSimpleName();
    }

    public void registerBuiltin(String name, BuiltinHandler handler) {
        builtins.put(name.toLowerCase(), handler);
    }

    // Script execution

    public Datum call(String handlerName, Datum... args) {
        return call(handlerName, Arrays.asList(args));
    }

    public Datum call(String handlerName, List<Datum> args) {
        // Check builtins first (more efficient and works without a file)
        BuiltinHandler builtin = builtins.get(handlerName.toLowerCase());
        if (builtin != null) {
            return builtin.call(this, args);
        }

        // Find the handler in scripts
        ScriptChunk.Handler handler = findHandler(handlerName);
        if (handler == null) {
            throw LingoException.undefinedHandler(handlerName);
        }

        // Find the script containing this handler
        ScriptChunk script = findScriptForHandler(handler);

        return execute(script, handler, args.toArray(new Datum[0]));
    }

    public Datum execute(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        // Debug: Log handler entry (before pushing to call stack to get caller info)
        if (debugMode) {
            String handlerName = getName(handler.nameId());
            String scriptType = script.scriptType() != null ? script.scriptType().name() : "UNKNOWN";
            String scriptInfo = scriptType + " script#" + script.id();

            // Get caller info from call stack (before we push the new scope)
            String callerInfo = "";
            if (!callStack.isEmpty()) {
                Scope callerScope = callStack.peek();
                String callerName = getName(callerScope.getHandler().nameId());
                int callerIP = callerScope.getInstructionPointer();
                callerInfo = " [called from " + callerName + " at IP:" + callerIP + "]";
            }

            debugLog("=== CALL " + handlerName + " in " + scriptInfo + callerInfo + " ===");
            debugLog("Args: " + (args.length == 0 ? "(none)" : formatArgsArray(args)));
            debugLog("Opcodes (" + handler.instructions().size() + "):");
            for (int i = 0; i < handler.instructions().size() && i < 50; i++) {
                ScriptChunk.Handler.Instruction instr = handler.instructions().get(i);
                debugLog("  [" + i + "] " + formatInstruction(instr));
            }
            if (handler.instructions().size() > 50) {
                debugLog("  ... (" + (handler.instructions().size() - 50) + " more)");
            }
            debugLog("--- Executing ---");
            debugIndent++;
        }

        Scope scope = new Scope(script, handler, args);
        callStack.push(scope);

        int instructionCount = 0;
        halted = false;

        try {
            while (!scope.isAtEnd() && !halted && instructionCount < maxInstructions) {
                ScriptChunk.Handler.Instruction instr = scope.getCurrentInstruction();

                if (debugMode) {
                    debugLog(String.format("[%03d] %-20s | Stack: %s",
                        scope.getInstructionPointer(),
                        formatInstruction(instr),
                        formatStack()));
                }

                executeInstruction(scope, instr);
                scope.advanceIP();
                instructionCount++;
            }

            if (instructionCount >= maxInstructions) {
                throw new LingoException("Execution limit exceeded: " + maxInstructions + " instructions");
            }

            Datum result = scope.getReturnValue();
            if (debugMode) {
                debugIndent--;
                debugLog("=== RETURN " + formatDatum(result) + " ===");
            }
            return result;
        } finally {
            callStack.pop();
            if (debugMode && debugIndent > 0) debugIndent--;
        }
    }

    private String formatArgsArray(Datum[] args) {
        if (args.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatDatum(args[i]));
        }
        return sb.toString();
    }

    private String formatInstruction(ScriptChunk.Handler.Instruction instr) {
        Opcode op = instr.opcode();
        int arg = instr.argument();
        String argStr = "";

        // Format argument based on opcode type
        if (op == Opcode.PUSH_SYMB || op == Opcode.EXT_CALL || op == Opcode.LOCAL_CALL ||
            op == Opcode.GET_PROP || op == Opcode.SET_PROP || op == Opcode.GET_GLOBAL ||
            op == Opcode.SET_GLOBAL || op == Opcode.GET_MOVIE_PROP || op == Opcode.SET_MOVIE_PROP ||
            op == Opcode.THE_BUILTIN || op == Opcode.OBJ_CALL || op == Opcode.NEW_OBJ) {
            // Argument is a name ID
            argStr = " '" + getName((int) arg) + "'";
        } else if (arg != 0) {
            argStr = " " + arg;
        }

        return op.name() + argStr;
    }

    private void executeInstruction(Scope scope, ScriptChunk.Handler.Instruction instr) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        switch (op) {
            // Stack operations
            case PUSH_ZERO -> push(Datum.of(0));
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> push(Datum.of(arg));
            case PUSH_FLOAT32 -> push(Datum.of(Float.intBitsToFloat(arg)));

            case PUSH_CONS -> {
                // Push constant from literal table
                ScriptChunk script = scope.getScript();
                if (arg < script.literals().size()) {
                    Object val = script.literals().get(arg).value();
                    if (val instanceof String s) {
                        push(Datum.of(s));
                    } else if (val instanceof Integer i) {
                        push(Datum.of(i));
                    } else if (val instanceof Double d) {
                        push(Datum.of(d.floatValue()));
                    } else {
                        push(Datum.voidValue());
                    }
                } else {
                    push(Datum.voidValue());
                }
            }

            case PUSH_SYMB -> {
                String name = getName(arg);
                push(Datum.symbol(name));
            }

            case POP -> {
                for (int i = 0; i < arg; i++) {
                    if (!stack.isEmpty()) stack.pop();
                }
            }

            case SWAP -> {
                if (stack.size() >= 2) {
                    Datum a = stack.pop();
                    Datum b = stack.pop();
                    stack.push(a);
                    stack.push(b);
                }
            }

            case PEEK -> {
                if (!stack.isEmpty()) {
                    push(stack.peek());
                }
            }

            // Arithmetic
            case ADD -> {
                Datum b = pop();
                Datum a = pop();
                push(add(a, b));
            }

            case SUB -> {
                Datum b = pop();
                Datum a = pop();
                push(subtract(a, b));
            }

            case MUL -> {
                Datum b = pop();
                Datum a = pop();
                push(multiply(a, b));
            }

            case DIV -> {
                Datum b = pop();
                Datum a = pop();
                push(divide(a, b));
            }

            case MOD -> {
                Datum b = pop();
                Datum a = pop();
                push(modulo(a, b));
            }

            case INV -> {
                Datum a = pop();
                push(negate(a));
            }

            // Comparison
            case LT -> {
                Datum b = pop();
                Datum a = pop();
                push(compare(a, b) < 0 ? Datum.TRUE : Datum.FALSE);
            }

            case LT_EQ -> {
                Datum b = pop();
                Datum a = pop();
                push(compare(a, b) <= 0 ? Datum.TRUE : Datum.FALSE);
            }

            case GT -> {
                Datum b = pop();
                Datum a = pop();
                push(compare(a, b) > 0 ? Datum.TRUE : Datum.FALSE);
            }

            case GT_EQ -> {
                Datum b = pop();
                Datum a = pop();
                push(compare(a, b) >= 0 ? Datum.TRUE : Datum.FALSE);
            }

            case EQ -> {
                Datum b = pop();
                Datum a = pop();
                push(equals(a, b) ? Datum.TRUE : Datum.FALSE);
            }

            case NT_EQ -> {
                Datum b = pop();
                Datum a = pop();
                push(!equals(a, b) ? Datum.TRUE : Datum.FALSE);
            }

            // Logical
            case AND -> {
                Datum b = pop();
                Datum a = pop();
                push(a.boolValue() && b.boolValue() ? Datum.TRUE : Datum.FALSE);
            }

            case OR -> {
                Datum b = pop();
                Datum a = pop();
                push(a.boolValue() || b.boolValue() ? Datum.TRUE : Datum.FALSE);
            }

            case NOT -> {
                Datum a = pop();
                push(!a.boolValue() ? Datum.TRUE : Datum.FALSE);
            }

            // String operations
            case JOIN_STR -> {
                Datum b = pop();
                Datum a = pop();
                push(Datum.of(a.stringValue() + b.stringValue()));
            }

            case JOIN_PAD_STR -> {
                Datum b = pop();
                Datum a = pop();
                push(Datum.of(a.stringValue() + " " + b.stringValue()));
            }

            case CONTAINS_STR -> {
                Datum b = pop();
                Datum a = pop();
                boolean contains = a.stringValue().toLowerCase()
                    .contains(b.stringValue().toLowerCase());
                push(contains ? Datum.TRUE : Datum.FALSE);
            }

            // Variables
            case GET_LOCAL -> push(scope.getLocal(arg));
            case SET_LOCAL -> scope.setLocal(arg, pop());

            case GET_PARAM -> push(scope.getArg(arg));
            case SET_PARAM -> scope.setArg(arg, pop());

            case GET_GLOBAL, GET_GLOBAL2 -> {
                String name = getName(arg);
                push(getGlobal(name));
            }

            case SET_GLOBAL, SET_GLOBAL2 -> {
                String name = getName(arg);
                setGlobal(name, pop());
            }

            // Control flow
            case JMP -> {
                // Jump relative to current position: target = current_offset + arg
                int currentOffset = instr.offset();
                int targetOffset = currentOffset + arg;
                int targetIndex = findInstructionAtOffset(scope.getHandler(), targetOffset);
                scope.setInstructionPointer(targetIndex - 1); // -1 because we advance after
            }

            case JMP_IF_Z -> {
                Datum condition = pop();
                if (!condition.boolValue()) {
                    // Jump relative to current position: target = current_offset + arg
                    int currentOffset = instr.offset();
                    int targetOffset = currentOffset + arg;
                    int targetIndex = findInstructionAtOffset(scope.getHandler(), targetOffset);
                    scope.setInstructionPointer(targetIndex - 1);
                }
            }

            case END_REPEAT -> {
                // Jump back: target = current_offset - arg
                int currentOffset = instr.offset();
                int targetOffset = currentOffset - arg;
                int targetIndex = findInstructionAtOffset(scope.getHandler(), targetOffset);
                scope.setInstructionPointer(targetIndex - 1);
            }

            case RET -> {
                // Return from handler
                if (!stack.isEmpty()) {
                    scope.setReturnValue(pop());
                }
                scope.setInstructionPointer(scope.getHandler().instructions().size());
            }

            case RET_FACTORY -> {
                // Return from factory (parent script constructor)
                scope.setReturnValue(Datum.voidValue());
                scope.setInstructionPointer(scope.getHandler().instructions().size());
            }

            // Lists
            case PUSH_LIST -> {
                int count = arg;
                List<Datum> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(0, pop()); // Reverse order from stack
                }
                Datum.DList list = Datum.list();
                for (Datum item : items) {
                    list.add(item);
                }
                push(list);
            }

            case PUSH_PROP_LIST -> {
                int count = arg;
                Datum.PropList propList = Datum.propList();
                for (int i = 0; i < count; i++) {
                    Datum value = pop();
                    Datum key = pop();
                    propList.put(key, value);
                }
                push(propList);
            }

            // Argument lists for function calls
            case PUSH_ARG_LIST -> {
                int count = arg;
                List<Datum> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(0, pop());
                }
                push(new Datum.ArgList(items));
            }

            case PUSH_ARG_LIST_NO_RET -> {
                int count = arg;
                List<Datum> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(0, pop());
                }
                push(new Datum.ArgListNoRet(items));
            }

            // Function calls
            case EXT_CALL -> {
                String handlerName = getName(arg);
                Datum argListDatum = pop();
                List<Datum> args = extractArgList(argListDatum);

                // Check builtin first
                BuiltinHandler builtin = builtins.get(handlerName.toLowerCase());
                if (builtin != null) {
                    push(builtin.call(this, args));
                } else {
                    // Find and call handler
                    Datum result = call(handlerName, args);
                    push(result);
                }
            }

            case LOCAL_CALL -> {
                String handlerName = getName(arg);
                Datum argListDatum = pop();
                List<Datum> args = extractArgList(argListDatum);

                // Call handler in current script
                ScriptChunk script = scope.getScript();
                ScriptChunk.Handler targetHandler = null;
                for (ScriptChunk.Handler h : script.handlers()) {
                    if (h.nameId() == arg) {
                        targetHandler = h;
                        break;
                    }
                }

                if (targetHandler != null) {
                    Datum result = execute(script, targetHandler, args.toArray(new Datum[0]));
                    push(result);
                } else {
                    push(Datum.voidValue());
                }
            }

            // Property access
            case GET_PROP -> {
                String propName = getName(arg);
                Datum obj = pop();
                push(getProperty(obj, propName));
            }

            case SET_PROP -> {
                String propName = getName(arg);
                Datum value = pop();
                Datum obj = pop();
                setProperty(obj, propName, value);
            }

            case GET_OBJ_PROP -> {
                String propName = getName(arg);
                Datum obj = pop();
                push(getProperty(obj, propName));
            }

            case SET_OBJ_PROP -> {
                String propName = getName(arg);
                Datum value = pop();
                Datum obj = pop();
                setProperty(obj, propName, value);
            }

            case GET_CHAINED_PROP -> {
                String propName = getName(arg);
                Datum obj = pop();
                push(getProperty(obj, propName));
            }

            case GET_TOP_LEVEL_PROP -> {
                String propName = getName(arg);
                push(getGlobal(propName));
            }

            // Movie properties
            case GET_MOVIE_PROP -> {
                String propName = getName(arg);
                push(getMovieProperty(propName));
            }

            case SET_MOVIE_PROP -> {
                String propName = getName(arg);
                Datum value = pop();
                setMovieProperty(propName, value);
            }

            // Object calls
            case OBJ_CALL, OBJ_CALL_V4 -> {
                String methodName = getName(arg);
                Datum argListDatum = pop();
                List<Datum> args = extractArgList(argListDatum);
                Datum obj = args.isEmpty() ? Datum.voidValue() : args.remove(0);
                push(callMethod(obj, methodName, args));
            }

            // String chunk operations
            case GET_CHUNK -> {
                // Stack: string, chunkType, start, end
                Datum end = pop();
                Datum start = pop();
                Datum chunkType = pop();
                Datum string = pop();
                push(getStringChunk(string.stringValue(), chunkType, start.intValue(), end.intValue()));
            }

            case PUT_CHUNK -> {
                // Put into chunk
                int chunkType = arg;
                Datum value = pop();
                Datum end = pop();
                Datum start = pop();
                Datum target = pop();
                // Implementation depends on target type
                push(Datum.voidValue());
            }

            case DELETE_CHUNK -> {
                // Delete chunk from string/field
                int chunkType = arg;
                Datum end = pop();
                Datum start = pop();
                Datum target = pop();
                push(Datum.voidValue());
            }

            case HILITE_CHUNK -> {
                // Highlight a chunk (used for text fields)
                // No-op in headless execution
            }

            // Tell blocks
            case START_TELL -> {
                // Start a tell block - target object is on stack
                Datum target = pop();
                scope.pushTellTarget(target);
            }

            case END_TELL -> {
                // End tell block
                scope.popTellTarget();
            }

            case TELL_CALL -> {
                String handlerName = getName(arg);
                Datum argListDatum = pop();
                List<Datum> args = extractArgList(argListDatum);
                Datum target = scope.getTellTarget();
                if (target != null) {
                    push(callMethod(target, handlerName, args));
                } else {
                    push(call(handlerName, args));
                }
            }

            // Sprite/field operations
            case ONTO_SPR -> {
                // sprite intersection test
                Datum spr2 = pop();
                Datum spr1 = pop();
                push(Datum.FALSE); // Simplified - would check sprite bounds
            }

            case INTO_SPR -> {
                // sprite containment test
                Datum spr2 = pop();
                Datum spr1 = pop();
                push(Datum.FALSE);
            }

            case GET_FIELD -> {
                // Get field reference
                Datum fieldRef = pop();
                push(fieldRef);
            }

            // The/builtin entity access
            case THE_BUILTIN -> {
                String entityName = getName(arg);
                push(getTheEntity(entityName));
            }

            case GET -> {
                // Generic get operation
                String propName = getName(arg);
                push(getGlobal(propName));
            }

            case SET -> {
                // Generic set operation
                String propName = getName(arg);
                setGlobal(propName, pop());
            }

            case PUT -> {
                // Put statement (output)
                int outputType = arg;
                Datum value = pop();
                System.out.println(value.stringValue());
            }

            // Object creation
            case NEW_OBJ -> {
                String className = getName(arg);
                Datum argListDatum = pop();
                List<Datum> args = extractArgList(argListDatum);
                push(createObject(className, args));
            }

            // Variable references
            case PUSH_VAR_REF -> {
                String varName = getName(arg);
                push(new Datum.VarRef(varName));
            }

            case PUSH_CHUNK_VAR_REF -> {
                // Push reference to a chunk of a variable
                String varName = getName(arg);
                push(new Datum.VarRef(varName));
            }

            // Contains (alternate form)
            case CONTAINS_0_STR -> {
                Datum b = pop();
                Datum a = pop();
                boolean contains = a.stringValue().toLowerCase()
                    .contains(b.stringValue().toLowerCase());
                push(contains ? Datum.TRUE : Datum.FALSE);
            }

            // JavaScript interop (stub)
            case CALL_JAVASCRIPT -> {
                // JavaScript calls not supported in pure Java VM
                push(Datum.voidValue());
            }

            // Default: unimplemented opcode
            default -> {
                // Log warning but continue
                System.err.println("Unimplemented opcode: " + op + " (0x" +
                    Integer.toHexString(op.getCode()) + ")");
            }
        }
    }

    // Property access helpers

    private Datum getProperty(Datum obj, String propName) {
        if (obj instanceof Datum.PropList propList) {
            return propList.get(Datum.symbol(propName));
        } else if (obj instanceof Datum.ScriptInstanceRef instance) {
            return instance.getProperty(propName);
        } else if (obj instanceof Datum.CastMemberRef memberRef) {
            return getCastMemberProperty(memberRef, propName);
        } else if (obj instanceof Datum.SpriteRef spriteRef) {
            return getSpriteProperty(spriteRef.channel(), propName);
        }
        return Datum.voidValue();
    }

    private void setProperty(Datum obj, String propName, Datum value) {
        if (obj instanceof Datum.PropList propList) {
            propList.put(Datum.symbol(propName), value);
        } else if (obj instanceof Datum.ScriptInstanceRef instance) {
            instance.setProperty(propName, value);
        }
    }

    private Datum getCastMemberProperty(Datum.CastMemberRef memberRef, String propName) {
        // Return cast member properties
        return switch (propName.toLowerCase()) {
            case "number", "membernum" -> Datum.of(memberRef.memberNum());
            case "castlib", "castlibnum" -> Datum.of(memberRef.castLib());
            default -> Datum.voidValue();
        };
    }

    private Datum getSpriteProperty(int channel, String propName) {
        // Would query actual sprite state
        return switch (propName.toLowerCase()) {
            case "spritenum", "channel" -> Datum.of(channel);
            case "visible" -> Datum.TRUE;
            case "loc" -> new Datum.IntPoint(0, 0);
            case "rect" -> new Datum.IntRect(0, 0, 0, 0);
            case "locH", "left" -> Datum.of(0);
            case "locV", "top" -> Datum.of(0);
            case "width" -> Datum.of(0);
            case "height" -> Datum.of(0);
            case "ink" -> Datum.of(0);
            case "blend" -> Datum.of(100);
            default -> Datum.voidValue();
        };
    }

    private Datum getMovieProperty(String propName) {
        return switch (propName.toLowerCase()) {
            case "stagewidth" -> Datum.of(file.getStageWidth());
            case "stageheight" -> Datum.of(file.getStageHeight());
            case "framelabel" -> Datum.of("");
            case "frame" -> Datum.of(1);
            case "lastframe" -> Datum.of(1);
            case "tempo" -> Datum.of(file.getTempo());
            case "colordepth" -> Datum.of(32);
            default -> Datum.voidValue();
        };
    }

    private void setMovieProperty(String propName, Datum value) {
        // Movie properties are generally read-only or require player support
    }

    private Datum callMethod(Datum obj, String methodName, List<Datum> args) {
        if (obj instanceof Datum.ScriptInstanceRef instance) {
            // Call method on script instance
            return Datum.voidValue();
        } else if (obj instanceof Datum.DList list) {
            return callListMethod(list, methodName, args);
        } else if (obj instanceof Datum.PropList propList) {
            return callPropListMethod(propList, methodName, args);
        }

        // Try as builtin
        BuiltinHandler builtin = builtins.get(methodName.toLowerCase());
        if (builtin != null) {
            List<Datum> allArgs = new ArrayList<>();
            allArgs.add(obj);
            allArgs.addAll(args);
            return builtin.call(this, allArgs);
        }

        return Datum.voidValue();
    }

    private Datum callListMethod(Datum.DList list, String method, List<Datum> args) {
        return switch (method.toLowerCase()) {
            case "count" -> Datum.of(list.count());
            case "getat" -> args.isEmpty() ? Datum.voidValue() : list.getAt(args.get(0).intValue());
            case "setat" -> {
                if (args.size() >= 2) {
                    list.setAt(args.get(0).intValue(), args.get(1));
                }
                yield Datum.voidValue();
            }
            case "add", "append" -> {
                if (!args.isEmpty()) list.add(args.get(0));
                yield Datum.voidValue();
            }
            case "deleteat" -> {
                if (!args.isEmpty() && args.get(0).intValue() >= 1 &&
                    args.get(0).intValue() <= list.count()) {
                    list.items().remove(args.get(0).intValue() - 1);
                }
                yield Datum.voidValue();
            }
            case "getlast" -> list.count() > 0 ? list.items().get(list.count() - 1) : Datum.voidValue();
            case "sort" -> {
                list.items().sort((a, b) -> {
                    if (a.isNumber() && b.isNumber()) {
                        return Float.compare(a.floatValue(), b.floatValue());
                    }
                    return a.stringValue().compareToIgnoreCase(b.stringValue());
                });
                yield Datum.voidValue();
            }
            default -> Datum.voidValue();
        };
    }

    private Datum callPropListMethod(Datum.PropList propList, String method, List<Datum> args) {
        return switch (method.toLowerCase()) {
            case "count" -> Datum.of(propList.count());
            case "getprop", "getaprop" -> args.isEmpty() ? Datum.voidValue() : propList.get(args.get(0));
            case "setprop", "setaprop", "addprop" -> {
                if (args.size() >= 2) {
                    propList.put(args.get(0), args.get(1));
                }
                yield Datum.voidValue();
            }
            case "deleteprop" -> {
                if (!args.isEmpty()) {
                    propList.properties().remove(args.get(0));
                }
                yield Datum.voidValue();
            }
            case "findpos" -> {
                if (args.isEmpty()) yield Datum.of(0);
                int pos = 1;
                for (Datum key : propList.properties().keySet()) {
                    if (key.equals(args.get(0))) {
                        yield Datum.of(pos);
                    }
                    pos++;
                }
                yield Datum.of(0);
            }
            default -> Datum.voidValue();
        };
    }

    private Datum getStringChunk(String str, Datum chunkType, int start, int end) {
        StringChunkType type = StringChunkType.CHAR;
        if (chunkType.isSymbol()) {
            type = switch (chunkType.stringValue().toLowerCase()) {
                case "char" -> StringChunkType.CHAR;
                case "word" -> StringChunkType.WORD;
                case "line" -> StringChunkType.LINE;
                case "item" -> StringChunkType.ITEM;
                default -> StringChunkType.CHAR;
            };
        } else if (chunkType.isInt()) {
            type = switch (chunkType.intValue()) {
                case 1 -> StringChunkType.CHAR;
                case 2 -> StringChunkType.WORD;
                case 3 -> StringChunkType.ITEM;
                case 4 -> StringChunkType.LINE;
                default -> StringChunkType.CHAR;
            };
        }

        return Datum.of(com.libreshockwave.handlers.StringHandlers.extractChunk(
            str, type, start, end, ','));
    }

    private Datum getTheEntity(String entityName) {
        return switch (entityName.toLowerCase()) {
            case "mouse" -> Datum.of(0);
            case "mouseh" -> Datum.of(0);
            case "mousev" -> Datum.of(0);
            case "mousedown" -> Datum.FALSE;
            case "mouseup" -> Datum.TRUE;
            case "key" -> Datum.of("");
            case "keycode" -> Datum.of(0);
            case "shiftdown" -> Datum.FALSE;
            case "controldown", "commanddown" -> Datum.FALSE;
            case "optiondown", "altdown" -> Datum.FALSE;
            case "ticks" -> Datum.of((int) (System.currentTimeMillis() / 16));
            case "time" -> Datum.of(java.time.LocalTime.now().toString());
            case "date" -> Datum.of(java.time.LocalDate.now().toString());
            case "milliseconds" -> Datum.of((int) (System.currentTimeMillis() % Integer.MAX_VALUE));
            case "platform" -> Datum.of("Java");
            case "environment" -> Datum.symbol("java");
            case "colordepth" -> Datum.of(32);
            case "runmode" -> Datum.symbol("projector");
            case "version" -> Datum.of("12.0");
            default -> Datum.voidValue();
        };
    }

    private Datum createObject(String className, List<Datum> args) {
        // Create script instance
        return new Datum.ScriptInstanceRef(className, new HashMap<>());
    }

    // Stack operations

    private void push(Datum value) {
        stack.push(value);
    }

    private Datum pop() {
        if (stack.isEmpty()) {
            return Datum.voidValue();
        }
        return stack.pop();
    }

    private Datum peek() {
        if (stack.isEmpty()) {
            return Datum.voidValue();
        }
        return stack.peek();
    }

    // Arithmetic operations

    private Datum add(Datum a, Datum b) {
        if (a.isFloat() || b.isFloat()) {
            return Datum.of(a.floatValue() + b.floatValue());
        }
        return Datum.of(a.intValue() + b.intValue());
    }

    private Datum subtract(Datum a, Datum b) {
        if (a.isFloat() || b.isFloat()) {
            return Datum.of(a.floatValue() - b.floatValue());
        }
        return Datum.of(a.intValue() - b.intValue());
    }

    private Datum multiply(Datum a, Datum b) {
        if (a.isFloat() || b.isFloat()) {
            return Datum.of(a.floatValue() * b.floatValue());
        }
        return Datum.of(a.intValue() * b.intValue());
    }

    private Datum divide(Datum a, Datum b) {
        float bVal = b.floatValue();
        if (bVal == 0) {
            throw new LingoException("Division by zero");
        }
        if (a.isInt() && b.isInt() && a.intValue() % b.intValue() == 0) {
            return Datum.of(a.intValue() / b.intValue());
        }
        return Datum.of(a.floatValue() / bVal);
    }

    private Datum modulo(Datum a, Datum b) {
        int bVal = b.intValue();
        if (bVal == 0) {
            throw new LingoException("Modulo by zero");
        }
        return Datum.of(a.intValue() % bVal);
    }

    private Datum negate(Datum a) {
        if (a.isFloat()) {
            return Datum.of(-a.floatValue());
        }
        return Datum.of(-a.intValue());
    }

    private int compare(Datum a, Datum b) {
        if (a.isNumber() && b.isNumber()) {
            return Float.compare(a.floatValue(), b.floatValue());
        }
        return a.stringValue().compareToIgnoreCase(b.stringValue());
    }

    private boolean equals(Datum a, Datum b) {
        if (a.isNumber() && b.isNumber()) {
            return a.floatValue() == b.floatValue();
        }
        return a.stringValue().equalsIgnoreCase(b.stringValue());
    }

    // Helper methods

    private String getName(int nameId) {
        ScriptNamesChunk names = file.getScriptNames();
        if (names != null) {
            return names.getName(nameId);
        }
        return "<name:" + nameId + ">";
    }

    /**
     * Result of finding a handler - contains both the script and handler.
     */
    private record HandlerLocation(ScriptChunk script, ScriptChunk.Handler handler, ScriptNamesChunk names) {}

    private HandlerLocation findHandlerWithScript(String name) {
        // First search in the main movie's scripts
        if (file != null) {
            ScriptNamesChunk names = file.getScriptNames();
            if (names != null) {
                int nameId = names.findName(name);
                if (nameId >= 0) {
                    for (ScriptChunk script : file.getScripts()) {
                        for (ScriptChunk.Handler handler : script.handlers()) {
                            if (handler.nameId() == nameId) {
                                return new HandlerLocation(script, handler, names);
                            }
                        }
                    }
                }
            }
        }

        // Then search in external cast scripts
        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    ScriptNamesChunk castNames = cast.getScriptNames();
                    if (castNames != null) {
                        int nameId = castNames.findName(name);
                        if (nameId >= 0) {
                            for (ScriptChunk script : cast.getAllScripts()) {
                                for (ScriptChunk.Handler handler : script.handlers()) {
                                    if (handler.nameId() == nameId) {
                                        debugLog("Found handler '" + name + "' in cast #" + cast.getNumber() + " '" + cast.getName() + "'");
                                        return new HandlerLocation(script, handler, castNames);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private ScriptChunk.Handler findHandler(String name) {
        HandlerLocation loc = findHandlerWithScript(name);
        return loc != null ? loc.handler : null;
    }

    private ScriptChunk findScriptForHandler(ScriptChunk.Handler target) {
        // Search in movie scripts
        if (file != null) {
            for (ScriptChunk script : file.getScripts()) {
                if (script.handlers().contains(target)) {
                    return script;
                }
            }
        }

        // Search in cast scripts
        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    for (ScriptChunk script : cast.getAllScripts()) {
                        if (script.handlers().contains(target)) {
                            return script;
                        }
                    }
                }
            }
        }

        return null;
    }

    private int findInstructionAtOffset(ScriptChunk.Handler handler, int offset) {
        for (int i = 0; i < handler.instructions().size(); i++) {
            if (handler.instructions().get(i).offset() == offset) {
                return i;
            }
        }
        return handler.instructions().size();
    }

    private List<Datum> extractArgList(Datum argList) {
        if (argList instanceof Datum.ArgList al) {
            return al.args();
        } else if (argList instanceof Datum.ArgListNoRet al) {
            return al.args();
        }
        return List.of();
    }

    // Built-in handlers

    private void registerBuiltins() {
        // Math functions
        registerBuiltin("abs", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            Datum a = args.get(0);
            if (a.isFloat()) return Datum.of(Math.abs(a.floatValue()));
            return Datum.of(Math.abs(a.intValue()));
        });

        registerBuiltin("sqrt", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0f);
            return Datum.of((float) Math.sqrt(args.get(0).floatValue()));
        });

        registerBuiltin("sin", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0f);
            return Datum.of((float) Math.sin(args.get(0).floatValue()));
        });

        registerBuiltin("cos", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0f);
            return Datum.of((float) Math.cos(args.get(0).floatValue()));
        });

        registerBuiltin("tan", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0f);
            return Datum.of((float) Math.tan(args.get(0).floatValue()));
        });

        registerBuiltin("atan", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0f);
            return Datum.of((float) Math.atan(args.get(0).floatValue()));
        });

        registerBuiltin("power", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0.0f);
            return Datum.of((float) Math.pow(args.get(0).floatValue(), args.get(1).floatValue()));
        });

        registerBuiltin("random", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            int max = args.get(0).intValue();
            return Datum.of((int) (Math.random() * max) + 1);
        });

        registerBuiltin("pi", (vm, args) -> Datum.of((float) Math.PI));

        // Type conversion
        registerBuiltin("integer", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            return Datum.of(args.get(0).intValue());
        });

        registerBuiltin("float", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0f);
            return Datum.of(args.get(0).floatValue());
        });

        registerBuiltin("string", (vm, args) -> {
            if (args.isEmpty()) return Datum.of("");
            return Datum.of(args.get(0).stringValue());
        });

        registerBuiltin("symbol", (vm, args) -> {
            if (args.isEmpty()) return Datum.symbol("");
            return Datum.symbol(args.get(0).stringValue());
        });

        // List operations
        registerBuiltin("count", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            Datum a = args.get(0);
            if (a instanceof Datum.DList list) {
                return Datum.of(list.count());
            } else if (a instanceof Datum.PropList propList) {
                return Datum.of(propList.count());
            } else if (a instanceof Datum.Str s) {
                return Datum.of(s.value().length());
            }
            return Datum.of(0);
        });

        registerBuiltin("getAt", (vm, args) -> {
            if (args.size() < 2) return Datum.voidValue();
            Datum list = args.get(0);
            int index = args.get(1).intValue();
            if (list instanceof Datum.DList l) {
                return l.getAt(index);
            }
            return Datum.voidValue();
        });

        registerBuiltin("setAt", (vm, args) -> {
            if (args.size() < 3) return Datum.voidValue();
            Datum list = args.get(0);
            int index = args.get(1).intValue();
            Datum value = args.get(2);
            if (list instanceof Datum.DList l) {
                l.setAt(index, value);
            }
            return Datum.voidValue();
        });

        // String operations
        registerBuiltin("length", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            return Datum.of(args.get(0).stringValue().length());
        });

        registerBuiltin("chars", (vm, args) -> {
            if (args.size() < 3) return Datum.of("");
            String s = args.get(0).stringValue();
            int start = args.get(1).intValue();
            int end = args.get(2).intValue();
            if (start < 1) start = 1;
            if (end > s.length()) end = s.length();
            return Datum.of(s.substring(start - 1, end));
        });

        // Output
        registerBuiltin("put", (vm, args) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(args.get(i).stringValue());
            }
            System.out.println(sb);
            return Datum.voidValue();
        });

        // System
        registerBuiltin("halt", (vm, args) -> {
            vm.halt();
            return Datum.voidValue();
        });

        // Type checking
        registerBuiltin("ilk", (vm, args) -> {
            if (args.isEmpty()) return Datum.symbol("void");
            Datum a = args.get(0);
            if (args.size() >= 2) {
                // ilk(x, #type) - returns true if x is of type
                String checkType = args.get(1).stringValue().toLowerCase();
                return switch (checkType) {
                    case "integer" -> a.isInt() ? Datum.TRUE : Datum.FALSE;
                    case "float" -> a.isFloat() ? Datum.TRUE : Datum.FALSE;
                    case "string" -> a.isString() ? Datum.TRUE : Datum.FALSE;
                    case "symbol" -> a.isSymbol() ? Datum.TRUE : Datum.FALSE;
                    case "list" -> a instanceof Datum.DList ? Datum.TRUE : Datum.FALSE;
                    case "proplist" -> a instanceof Datum.PropList ? Datum.TRUE : Datum.FALSE;
                    case "point" -> a instanceof Datum.IntPoint ? Datum.TRUE : Datum.FALSE;
                    case "rect" -> a instanceof Datum.IntRect ? Datum.TRUE : Datum.FALSE;
                    case "void" -> a.isVoid() ? Datum.TRUE : Datum.FALSE;
                    default -> Datum.FALSE;
                };
            }
            // Return type symbol
            return switch (a) {
                case Datum.Int i -> Datum.symbol("integer");
                case Datum.DFloat f -> Datum.symbol("float");
                case Datum.Str s -> Datum.symbol("string");
                case Datum.Symbol sym -> Datum.symbol("symbol");
                case Datum.DList l -> Datum.symbol("list");
                case Datum.PropList p -> Datum.symbol("propList");
                case Datum.IntPoint p -> Datum.symbol("point");
                case Datum.IntRect r -> Datum.symbol("rect");
                case Datum.Void v -> Datum.symbol("void");
                default -> Datum.symbol("object");
            };
        });

        registerBuiltin("voidP", (vm, args) -> args.isEmpty() ? Datum.TRUE :
            args.get(0).isVoid() ? Datum.TRUE : Datum.FALSE);
        registerBuiltin("integerP", (vm, args) -> args.isEmpty() ? Datum.FALSE :
            args.get(0).isInt() ? Datum.TRUE : Datum.FALSE);
        registerBuiltin("floatP", (vm, args) -> args.isEmpty() ? Datum.FALSE :
            args.get(0).isFloat() ? Datum.TRUE : Datum.FALSE);
        registerBuiltin("stringP", (vm, args) -> args.isEmpty() ? Datum.FALSE :
            args.get(0).isString() ? Datum.TRUE : Datum.FALSE);
        registerBuiltin("symbolP", (vm, args) -> args.isEmpty() ? Datum.FALSE :
            args.get(0).isSymbol() ? Datum.TRUE : Datum.FALSE);
        registerBuiltin("listP", (vm, args) -> args.isEmpty() ? Datum.FALSE :
            args.get(0) instanceof Datum.DList ? Datum.TRUE : Datum.FALSE);
        registerBuiltin("objectP", (vm, args) -> args.isEmpty() ? Datum.FALSE :
            args.get(0) instanceof Datum.ScriptInstanceRef ? Datum.TRUE : Datum.FALSE);

        registerBuiltin("value", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            String s = args.get(0).stringValue().trim();
            try {
                if (s.contains(".")) {
                    return Datum.of(Float.parseFloat(s));
                }
                return Datum.of(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                return Datum.voidValue();
            }
        });

        // Point and Rect constructors
        registerBuiltin("point", (vm, args) -> {
            if (args.size() < 2) return new Datum.IntPoint(0, 0);
            return new Datum.IntPoint(args.get(0).intValue(), args.get(1).intValue());
        });

        registerBuiltin("rect", (vm, args) -> {
            if (args.size() < 4) return new Datum.IntRect(0, 0, 0, 0);
            return new Datum.IntRect(
                args.get(0).intValue(), args.get(1).intValue(),
                args.get(2).intValue(), args.get(3).intValue()
            );
        });

        // Cast/member/sprite references
        registerBuiltin("castLib", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            int num = args.get(0).intValue();
            return new Datum.CastLibRef(num);
        });

        registerBuiltin("member", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            if (args.size() >= 2) {
                // member(name/num, castLib)
                return new Datum.CastMemberRef(args.get(0).intValue(), args.get(1).intValue());
            }
            return new Datum.CastMemberRef(args.get(0).intValue(), 1);
        });

        registerBuiltin("sprite", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            return new Datum.SpriteRef(args.get(0).intValue());
        });

        // Navigation
        registerBuiltin("go", (vm, args) -> {
            // go(frame) or go(frame, movie)
            if (!args.isEmpty()) {
                System.out.println("[go] frame=" + args.get(0).stringValue());
            }
            return Datum.voidValue();
        });

        registerBuiltin("play", (vm, args) -> {
            System.out.println("[play] " + (args.isEmpty() ? "" : args.get(0).stringValue()));
            return Datum.voidValue();
        });

        registerBuiltin("updateStage", (vm, args) -> {
            // Would trigger stage redraw
            return Datum.voidValue();
        });

        registerBuiltin("puppetTempo", (vm, args) -> {
            if (!args.isEmpty()) {
                System.out.println("[puppetTempo] " + args.get(0).intValue() + " fps");
            }
            return Datum.voidValue();
        });

        registerBuiltin("moveToFront", (vm, args) -> {
            // Window management - no-op in headless
            return Datum.voidValue();
        });

        // Network handlers (using NetManager when available)
        registerBuiltin("netDone", (vm, args) -> {
            if (vm.netManager == null) return Datum.TRUE;
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            return vm.netManager.isTaskDone(taskId) ? Datum.TRUE : Datum.FALSE;
        });

        registerBuiltin("preloadNetThing", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            String url = args.get(0).stringValue();
            if (vm.netManager == null) {
                System.out.println("[preloadNetThing] " + url + " (no NetManager)");
                return Datum.of(1);
            }
            int taskId = vm.netManager.preloadNetThing(url);
            System.out.println("[preloadNetThing] " + url + " -> task " + taskId);
            return Datum.of(taskId);
        });

        registerBuiltin("getNetText", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            String url = args.get(0).stringValue();
            if (vm.netManager == null) return Datum.of(1);
            int taskId = vm.netManager.preloadNetThing(url);
            return Datum.of(taskId);
        });

        registerBuiltin("netTextResult", (vm, args) -> {
            if (vm.netManager == null) return Datum.of("");
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            return vm.netManager.getTaskResult(taskId)
                .filter(NetResult::isSuccess)
                .map(r -> Datum.of(new String(r.getData())))
                .orElse(Datum.of(""));
        });

        registerBuiltin("netStatus", (vm, args) -> {
            if (vm.netManager == null) return Datum.of("Complete");
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            boolean done = vm.netManager.isTaskDone(taskId);
            return Datum.of(done ? "Complete" : "InProgress");
        });

        registerBuiltin("netError", (vm, args) -> {
            if (vm.netManager == null) return Datum.of("OK");
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            return vm.netManager.getTaskResult(taskId)
                .map(r -> r.isSuccess() ? Datum.of("OK") : Datum.of(r.getErrorCode()))
                .orElse(Datum.of(0));
        });

        registerBuiltin("postNetText", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            String url = args.get(0).stringValue();
            String postData = args.size() > 1 ? args.get(1).stringValue() : "";
            if (vm.netManager == null) return Datum.of(1);
            int taskId = vm.netManager.postNetText(url, postData);
            return Datum.of(taskId);
        });

        registerBuiltin("getStreamStatus", (vm, args) -> {
            if (vm.netManager == null || args.isEmpty()) return Datum.propList();
            int taskId = args.get(0).intValue();
            Optional<NetTask> taskOpt = vm.netManager.getTask(taskId);
            if (taskOpt.isEmpty()) return Datum.propList();
            NetTask task = taskOpt.get();

            boolean isDone = vm.netManager.isTaskDone(taskId);
            boolean isOk = isDone && vm.netManager.getTaskResult(taskId)
                .map(NetResult::isSuccess).orElse(false);

            Datum.PropList result = Datum.propList();
            result.put(Datum.symbol("URL"), Datum.of(task.url()));
            result.put(Datum.symbol("state"), Datum.of(isDone ? "Complete" : "InProgress"));
            result.put(Datum.symbol("bytesSoFar"), Datum.of(isOk ? 100 : 0));
            result.put(Datum.symbol("bytesTotal"), Datum.of(100));
            result.put(Datum.symbol("error"), Datum.of(isOk ? "OK" : "Error"));
            return result;
        });

        registerBuiltin("gotoNetPage", (vm, args) -> {
            if (!args.isEmpty()) {
                System.out.println("[gotoNetPage] " + args.get(0).stringValue());
            }
            return Datum.voidValue();
        });

        registerBuiltin("gotoNetMovie", (vm, args) -> {
            if (!args.isEmpty()) {
                System.out.println("[gotoNetMovie] " + args.get(0).stringValue());
            }
            return Datum.voidValue();
        });

        // Sound
        registerBuiltin("sound", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            return new Datum.SoundRef(args.get(0).intValue());
        });

        registerBuiltin("puppetSound", (vm, args) -> {
            // puppetSound(channel, member) or puppetSound(channel, 0) to stop
            return Datum.voidValue();
        });

        // Alert/dialogs
        registerBuiltin("alert", (vm, args) -> {
            if (!args.isEmpty()) {
                System.out.println("[ALERT] " + args.get(0).stringValue());
            }
            return Datum.voidValue();
        });

        registerBuiltin("beep", (vm, args) -> {
            System.out.println("[BEEP]");
            return Datum.voidValue();
        });

        // Cursor
        registerBuiltin("cursor", (vm, args) -> {
            // Set cursor type
            return Datum.voidValue();
        });

        // List constructors
        registerBuiltin("list", (vm, args) -> {
            Datum.DList list = Datum.list();
            for (Datum arg : args) {
                list.add(arg);
            }
            return list;
        });

        // Additional list operations
        registerBuiltin("add", (vm, args) -> {
            if (args.size() < 2) return Datum.voidValue();
            if (args.get(0) instanceof Datum.DList list) {
                list.add(args.get(1));
            }
            return Datum.voidValue();
        });

        registerBuiltin("append", (vm, args) -> {
            if (args.size() < 2) return Datum.voidValue();
            if (args.get(0) instanceof Datum.DList list) {
                list.add(args.get(1));
            }
            return Datum.voidValue();
        });

        registerBuiltin("addAt", (vm, args) -> {
            if (args.size() < 3) return Datum.voidValue();
            if (args.get(0) instanceof Datum.DList list) {
                int index = args.get(1).intValue();
                if (index >= 1 && index <= list.count() + 1) {
                    list.items().add(index - 1, args.get(2));
                }
            }
            return Datum.voidValue();
        });

        registerBuiltin("deleteAt", (vm, args) -> {
            if (args.size() < 2) return Datum.voidValue();
            if (args.get(0) instanceof Datum.DList list) {
                int index = args.get(1).intValue();
                if (index >= 1 && index <= list.count()) {
                    list.items().remove(index - 1);
                }
            }
            return Datum.voidValue();
        });

        registerBuiltin("getOne", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            if (args.get(0) instanceof Datum.DList list) {
                Datum search = args.get(1);
                for (int i = 0; i < list.count(); i++) {
                    if (list.items().get(i).equals(search)) {
                        return Datum.of(i + 1);
                    }
                }
            }
            return Datum.of(0);
        });

        registerBuiltin("getPos", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            if (args.get(0) instanceof Datum.DList list) {
                Datum search = args.get(1);
                for (int i = 0; i < list.count(); i++) {
                    if (list.items().get(i).equals(search)) {
                        return Datum.of(i + 1);
                    }
                }
            }
            return Datum.of(0);
        });

        registerBuiltin("getLast", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            if (args.get(0) instanceof Datum.DList list) {
                if (list.count() > 0) {
                    return list.items().get(list.count() - 1);
                }
            }
            return Datum.voidValue();
        });

        registerBuiltin("sort", (vm, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            if (args.get(0) instanceof Datum.DList list) {
                list.items().sort((a, b) -> {
                    if (a.isNumber() && b.isNumber()) {
                        return Float.compare(a.floatValue(), b.floatValue());
                    }
                    return a.stringValue().compareToIgnoreCase(b.stringValue());
                });
            }
            return Datum.voidValue();
        });

        // PropList operations
        registerBuiltin("getProp", (vm, args) -> {
            if (args.size() < 2) return Datum.voidValue();
            if (args.get(0) instanceof Datum.PropList propList) {
                return propList.get(args.get(1));
            }
            return Datum.voidValue();
        });

        registerBuiltin("setProp", (vm, args) -> {
            if (args.size() < 3) return Datum.voidValue();
            if (args.get(0) instanceof Datum.PropList propList) {
                propList.put(args.get(1), args.get(2));
            }
            return Datum.voidValue();
        });

        registerBuiltin("addProp", (vm, args) -> {
            if (args.size() < 3) return Datum.voidValue();
            if (args.get(0) instanceof Datum.PropList propList) {
                propList.put(args.get(1), args.get(2));
            }
            return Datum.voidValue();
        });

        registerBuiltin("deleteProp", (vm, args) -> {
            if (args.size() < 2) return Datum.voidValue();
            if (args.get(0) instanceof Datum.PropList propList) {
                propList.properties().remove(args.get(1));
            }
            return Datum.voidValue();
        });

        registerBuiltin("findPos", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            if (args.get(0) instanceof Datum.PropList propList) {
                int pos = 1;
                for (Datum key : propList.properties().keySet()) {
                    if (key.equals(args.get(1))) {
                        return Datum.of(pos);
                    }
                    pos++;
                }
            }
            return Datum.of(0);
        });

        // String utilities
        registerBuiltin("offset", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            String needle = args.get(0).stringValue();
            String haystack = args.get(1).stringValue();
            int idx = haystack.indexOf(needle);
            return Datum.of(idx + 1); // 1-based, 0 if not found
        });

        registerBuiltin("charToNum", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            String s = args.get(0).stringValue();
            if (s.isEmpty()) return Datum.of(0);
            return Datum.of((int) s.charAt(0));
        });

        registerBuiltin("numToChar", (vm, args) -> {
            if (args.isEmpty()) return Datum.of("");
            int n = args.get(0).intValue();
            return Datum.of(String.valueOf((char) n));
        });

        // Min/max
        registerBuiltin("min", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            float minVal = args.get(0).floatValue();
            for (int i = 1; i < args.size(); i++) {
                minVal = Math.min(minVal, args.get(i).floatValue());
            }
            if (args.stream().allMatch(Datum::isInt)) {
                return Datum.of((int) minVal);
            }
            return Datum.of(minVal);
        });

        registerBuiltin("max", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            float maxVal = args.get(0).floatValue();
            for (int i = 1; i < args.size(); i++) {
                maxVal = Math.max(maxVal, args.get(i).floatValue());
            }
            if (args.stream().allMatch(Datum::isInt)) {
                return Datum.of((int) maxVal);
            }
            return Datum.of(maxVal);
        });

        // Bitwise operations
        registerBuiltin("bitAnd", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            return Datum.of(args.get(0).intValue() & args.get(1).intValue());
        });

        registerBuiltin("bitOr", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            return Datum.of(args.get(0).intValue() | args.get(1).intValue());
        });

        registerBuiltin("bitXor", (vm, args) -> {
            if (args.size() < 2) return Datum.of(0);
            return Datum.of(args.get(0).intValue() ^ args.get(1).intValue());
        });

        registerBuiltin("bitNot", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            return Datum.of(~args.get(0).intValue());
        });
    }
}
