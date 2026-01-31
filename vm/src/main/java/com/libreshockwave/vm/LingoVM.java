package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.trace.TracingHelper;

import java.util.*;

/**
 * Lingo Virtual Machine.
 * Executes bytecode from ScriptChunk handlers.
 * Similar to dirplayer-rs handler_manager.rs.
 */
public class LingoVM {

    private static final int MAX_CALL_STACK_DEPTH = 50;

    private final DirectorFile file;
    private final Map<String, Datum> globals;
    private final Deque<Scope> callStack;
    private final BuiltinRegistry builtins;
    private final TracingHelper tracingHelper;

    private boolean traceEnabled = false;
    private int stepLimit = 100_000;  // Maximum instructions per handler call

    // Event propagation callback (set by EventDispatcher)
    private Runnable passCallback;

    // Trace listener for debug UI
    private TraceListener traceListener;

    public LingoVM(DirectorFile file) {
        this.file = file;
        this.globals = new HashMap<>();
        this.callStack = new ArrayDeque<>();
        this.builtins = new BuiltinRegistry();
        this.tracingHelper = new TracingHelper(this::resolveName);
        registerPassBuiltin();
    }

    private void registerPassBuiltin() {
        // Register pass separately since it needs access to passCallback
        builtins.register("pass", (vm, args) -> {
            if (vm.passCallback != null) {
                vm.passCallback.run();
            }
            return Datum.VOID;
        });
    }

    // Configuration

    public void setTraceEnabled(boolean enabled) {
        this.traceEnabled = enabled;
    }

    public void setTraceListener(TraceListener listener) {
        this.traceListener = listener;
    }

    public TraceListener getTraceListener() {
        return traceListener;
    }

    public void setStepLimit(int limit) {
        this.stepLimit = limit;
    }

    /**
     * Set a callback to be invoked when a script calls pass().
     * Used by EventDispatcher to stop event propagation.
     */
    public void setPassCallback(Runnable callback) {
        this.passCallback = callback;
    }

    /**
     * Clear the pass callback.
     */
    public void clearPassCallback() {
        this.passCallback = null;
    }

    // Global variable access

    public Datum getGlobal(String name) {
        return globals.getOrDefault(name, Datum.VOID);
    }

    public void setGlobal(String name, Datum value) {
        globals.put(name, value);
    }

    public Map<String, Datum> getGlobals() {
        return Collections.unmodifiableMap(globals);
    }

    public void clearGlobals() {
        globals.clear();
    }

    // Call stack access

    public int getCallStackDepth() {
        return callStack.size();
    }

    public Scope getCurrentScope() {
        return callStack.peek();
    }

    // Handler execution

    /**
     * Find a handler by name in any script.
     * @param handlerName The handler name to find
     * @return The script and handler, or null if not found
     */
    public HandlerRef findHandler(String handlerName) {
        if (file == null) return null;
        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) return null;

        for (ScriptChunk script : file.getScripts()) {
            ScriptChunk.Handler handler = script.findHandler(handlerName, names);
            if (handler != null) {
                return new HandlerRef(script, handler);
            }
        }
        return null;
    }

    /**
     * Find a handler in a specific script.
     */
    public HandlerRef findHandler(ScriptChunk script, String handlerName) {
        if (file == null) return null;
        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) return null;

        ScriptChunk.Handler handler = script.findHandler(handlerName, names);
        if (handler != null) {
            return new HandlerRef(script, handler);
        }
        return null;
    }

    /**
     * Call a handler by name with arguments.
     * Checks built-in functions first, then script handlers.
     * @param handlerName The handler name
     * @param args Arguments to pass
     * @return The return value
     */
    public Datum callHandler(String handlerName, List<Datum> args) {
        // Check builtins first
        if (builtins.contains(handlerName)) {
            return builtins.invoke(handlerName, this, args);
        }

        // Then try script handlers
        HandlerRef ref = findHandler(handlerName);
        if (ref == null) {
            // Handler not found - this is normal for optional event handlers
            return Datum.VOID;
        }
        return executeHandler(ref.script(), ref.handler(), args, null);
    }

    /**
     * Call a handler with a receiver (for behaviors/parent scripts).
     */
    public Datum callHandler(String handlerName, List<Datum> args, Datum receiver) {
        HandlerRef ref = findHandler(handlerName);
        if (ref == null) {
            return Datum.VOID;
        }
        return executeHandler(ref.script(), ref.handler(), args, receiver);
    }

    /**
     * Execute a specific handler with arguments.
     */
    public Datum executeHandler(ScriptChunk script, ScriptChunk.Handler handler,
                                List<Datum> args, Datum receiver) {
        if (callStack.size() >= MAX_CALL_STACK_DEPTH) {
            throw new LingoException("Call stack overflow (max " + MAX_CALL_STACK_DEPTH + " frames)");
        }

        Scope scope = new Scope(script, handler, args, receiver);
        callStack.push(scope);

        // Notify trace listener of handler entry
        TraceListener.HandlerInfo handlerInfo = null;
        if (traceListener != null || traceEnabled) {
            handlerInfo = tracingHelper.buildHandlerInfo(script, handler, args, receiver, globals);

            if (traceEnabled) {
                tracingHelper.traceHandlerEnter(handlerInfo);
            }
            if (traceListener != null) {
                traceListener.onHandlerEnter(handlerInfo);
            }
        }

        try {
            int steps = 0;
            while (scope.hasMoreInstructions() && !scope.isReturned()) {
                if (steps++ >= stepLimit) {
                    throw new LingoException("Step limit exceeded (" + stepLimit + " instructions)");
                }
                executeInstruction(scope);
            }

            Datum result = scope.getReturnValue();

            // Notify trace listener of handler exit
            if (traceListener != null && handlerInfo != null) {
                traceListener.onHandlerExit(handlerInfo, result);
            }
            if (traceEnabled && handlerInfo != null) {
                tracingHelper.traceHandlerExit(handlerInfo, result);
            }

            return result;
        } catch (Exception e) {
            if (traceListener != null) {
                traceListener.onError("Error in " + resolveName(handler.nameId()), e);
            }
            throw e;
        } finally {
            callStack.pop();
        }
    }

    /**
     * Execute a single bytecode instruction.
     */
    private void executeInstruction(Scope scope) {
        ScriptChunk.Handler.Instruction instr = scope.getCurrentInstruction();
        if (instr == null) {
            scope.setReturned(true);
            return;
        }

        // Trace before execution
        if (traceEnabled || traceListener != null) {
            TraceListener.InstructionInfo instrInfo = tracingHelper.buildInstructionInfo(scope, instr);
            if (traceEnabled) {
                tracingHelper.traceInstruction(instrInfo);
            }
            if (traceListener != null) {
                traceListener.onInstruction(instrInfo);
            }
        }

        Opcode op = instr.opcode();
        int arg = instr.argument();

        switch (op) {
            // Return
            case RET -> {
                Datum value = scope.pop();
                scope.setReturnValue(value);
            }
            case RET_FACTORY -> {
                scope.setReturnValue(Datum.VOID);
            }

            // Push constants
            case PUSH_ZERO -> scope.push(Datum.ZERO);
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> scope.push(Datum.of(arg));
            case PUSH_FLOAT32 -> scope.push(Datum.of(Float.intBitsToFloat(arg)));
            case PUSH_CONS -> {
                List<ScriptChunk.LiteralEntry> literals = scope.getScript().literals();
                if (arg >= 0 && arg < literals.size()) {
                    ScriptChunk.LiteralEntry lit = literals.get(arg);
                    Datum value = switch (lit.type()) {
                        case 1 -> Datum.of((String) lit.value());
                        case 4 -> Datum.of((Integer) lit.value());
                        case 9 -> Datum.of((Double) lit.value());
                        default -> Datum.VOID;
                    };
                    scope.push(value);
                } else {
                    scope.push(Datum.VOID);
                }
            }
            case PUSH_SYMB -> {
                String name = resolveName(arg);
                scope.push(Datum.symbol(name));
            }

            // Arithmetic
            case ADD -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                if (a.isFloat() || b.isFloat()) {
                    scope.push(Datum.of(a.toDouble() + b.toDouble()));
                } else {
                    scope.push(Datum.of(a.toInt() + b.toInt()));
                }
            }
            case SUB -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                if (a.isFloat() || b.isFloat()) {
                    scope.push(Datum.of(a.toDouble() - b.toDouble()));
                } else {
                    scope.push(Datum.of(a.toInt() - b.toInt()));
                }
            }
            case MUL -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                if (a.isFloat() || b.isFloat()) {
                    scope.push(Datum.of(a.toDouble() * b.toDouble()));
                } else {
                    scope.push(Datum.of(a.toInt() * b.toInt()));
                }
            }
            case DIV -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                double bVal = b.toDouble();
                if (bVal == 0) {
                    throw new LingoException("Division by zero");
                }
                scope.push(Datum.of(a.toDouble() / bVal));
            }
            case MOD -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                int bVal = b.toInt();
                if (bVal == 0) {
                    throw new LingoException("Modulo by zero");
                }
                scope.push(Datum.of(a.toInt() % bVal));
            }
            case INV -> {
                Datum a = scope.pop();
                if (a.isFloat()) {
                    scope.push(Datum.of(-a.toDouble()));
                } else {
                    scope.push(Datum.of(-a.toInt()));
                }
            }

            // String operations
            case JOIN_STR -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(Datum.of(a.toStr() + b.toStr()));
            }
            case JOIN_PAD_STR -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(Datum.of(a.toStr() + " " + b.toStr()));
            }

            // Comparison
            case LT -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(a.toDouble() < b.toDouble() ? Datum.TRUE : Datum.FALSE);
            }
            case LT_EQ -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(a.toDouble() <= b.toDouble() ? Datum.TRUE : Datum.FALSE);
            }
            case GT -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(a.toDouble() > b.toDouble() ? Datum.TRUE : Datum.FALSE);
            }
            case GT_EQ -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(a.toDouble() >= b.toDouble() ? Datum.TRUE : Datum.FALSE);
            }
            case EQ -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(datumEquals(a, b) ? Datum.TRUE : Datum.FALSE);
            }
            case NT_EQ -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(!datumEquals(a, b) ? Datum.TRUE : Datum.FALSE);
            }

            // Logical
            case AND -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(a.isTruthy() && b.isTruthy() ? Datum.TRUE : Datum.FALSE);
            }
            case OR -> {
                Datum b = scope.pop();
                Datum a = scope.pop();
                scope.push(a.isTruthy() || b.isTruthy() ? Datum.TRUE : Datum.FALSE);
            }
            case NOT -> {
                Datum a = scope.pop();
                scope.push(a.isTruthy() ? Datum.FALSE : Datum.TRUE);
            }

            // String containment
            case CONTAINS_STR -> {
                Datum needle = scope.pop();
                Datum haystack = scope.pop();
                boolean contains = haystack.toStr().toLowerCase()
                    .contains(needle.toStr().toLowerCase());
                scope.push(contains ? Datum.TRUE : Datum.FALSE);
            }

            // Stack manipulation
            case SWAP -> scope.swap();
            case POP -> scope.pop();
            case PEEK -> {
                Datum value = scope.peek(arg);
                scope.push(value);
            }

            // Variable access
            case GET_LOCAL -> scope.push(scope.getLocal(arg));
            case SET_LOCAL -> {
                Datum value = scope.pop();
                scope.setLocal(arg, value);
                if (traceListener != null) {
                    traceListener.onVariableSet("local", "local" + arg, value);
                }
            }
            case GET_PARAM -> scope.push(scope.getParam(arg));
            case SET_PARAM -> {
                scope.pop();
            }
            case GET_GLOBAL, GET_GLOBAL2 -> {
                String name = resolveName(arg);
                scope.push(getGlobal(name));
            }
            case SET_GLOBAL, SET_GLOBAL2 -> {
                String name = resolveName(arg);
                Datum value = scope.pop();
                setGlobal(name, value);
                if (traceListener != null) {
                    traceListener.onVariableSet("global", name, value);
                }
            }

            // Control flow
            case JMP -> {
                int target = instr.offset() + arg;
                int targetIndex = scope.getHandler().getInstructionIndex(target);
                if (targetIndex >= 0) {
                    scope.setBytecodeIndex(targetIndex);
                    return;
                }
            }
            case JMP_IF_Z -> {
                Datum cond = scope.pop();
                if (!cond.isTruthy()) {
                    int target = instr.offset() + arg;
                    int targetIndex = scope.getHandler().getInstructionIndex(target);
                    if (targetIndex >= 0) {
                        scope.setBytecodeIndex(targetIndex);
                        return;
                    }
                }
            }
            case END_REPEAT -> {
                int target = instr.offset() - arg;
                int targetIndex = scope.getHandler().getInstructionIndex(target);
                if (targetIndex >= 0) {
                    scope.setBytecodeIndex(targetIndex);
                    return;
                }
            }

            // List operations
            case PUSH_LIST -> {
                int count = arg;
                List<Datum> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(0, scope.pop());
                }
                scope.push(Datum.list(items));
            }
            case PUSH_PROP_LIST -> {
                int count = arg;
                Map<String, Datum> props = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    Datum value = scope.pop();
                    Datum key = scope.pop();
                    String keyStr = key instanceof Datum.Symbol s ? s.name() : key.toStr();
                    props.put(keyStr, value);
                }
                scope.push(Datum.propList(props));
            }
            case PUSH_ARG_LIST, PUSH_ARG_LIST_NO_RET -> {
                scope.push(new Datum.Int(arg));
            }

            // Function calls
            case LOCAL_CALL -> {
                ScriptChunk.Handler targetHandler = findLocalHandler(scope.getScript(), arg);
                if (targetHandler != null) {
                    Datum argCountDatum = scope.pop();
                    int argCount = argCountDatum.toInt();
                    List<Datum> args = popArgs(scope, argCount);
                    Datum result = executeHandler(scope.getScript(), targetHandler, args, scope.getReceiver());
                    scope.push(result);
                } else {
                    scope.push(Datum.VOID);
                }
            }
            case EXT_CALL -> {
                String handlerName = resolveName(arg);
                Datum argCountDatum = scope.pop();
                int argCount = argCountDatum.toInt();
                List<Datum> args = popArgs(scope, argCount);

                if (builtins.contains(handlerName)) {
                    Datum result = builtins.invoke(handlerName, this, args);
                    scope.push(result);
                } else {
                    HandlerRef ref = findHandler(handlerName);
                    if (ref != null) {
                        Datum result = executeHandler(ref.script(), ref.handler(), args, null);
                        scope.push(result);
                    } else {
                        scope.push(Datum.VOID);
                    }
                }
            }
            case OBJ_CALL -> {
                String methodName = resolveName(arg);
                Datum argCountDatum = scope.pop();
                int argCount = argCountDatum.toInt();
                List<Datum> args = popArgs(scope, argCount);
                Datum target = args.isEmpty() ? Datum.VOID : args.remove(0);
                scope.push(Datum.VOID);
            }

            // Property access
            case GET_PROP -> {
                String propName = resolveName(arg);
                if (scope.getReceiver() instanceof Datum.ScriptInstance si) {
                    scope.push(si.properties().getOrDefault(propName, Datum.VOID));
                } else {
                    scope.push(Datum.VOID);
                }
            }
            case SET_PROP -> {
                String propName = resolveName(arg);
                Datum value = scope.pop();
                if (scope.getReceiver() instanceof Datum.ScriptInstance si) {
                    si.properties().put(propName, value);
                    if (traceListener != null) {
                        traceListener.onVariableSet("property", "me." + propName, value);
                    }
                }
            }

            // Movie properties (stubs)
            case GET_MOVIE_PROP -> {
                scope.push(Datum.VOID);
            }
            case SET_MOVIE_PROP -> {
                scope.pop();
            }

            // Object properties (stubs)
            case GET_OBJ_PROP -> {
                Datum obj = scope.pop();
                scope.push(Datum.VOID);
            }
            case SET_OBJ_PROP -> {
                Datum value = scope.pop();
                Datum obj = scope.pop();
            }

            // Builtins
            case THE_BUILTIN -> {
                scope.push(Datum.VOID);
            }

            // Unimplemented opcodes
            default -> {
                if (traceEnabled) {
                    System.err.println("Unimplemented opcode: " + op);
                }
            }
        }

        scope.advanceBytecodeIndex();
    }

    private ScriptChunk.Handler findLocalHandler(ScriptChunk script, int vectorPos) {
        for (ScriptChunk.Handler handler : script.handlers()) {
            if (handler.handlerVectorPos() == vectorPos) {
                return handler;
            }
        }
        return null;
    }

    private List<Datum> popArgs(Scope scope, int count) {
        List<Datum> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            args.add(0, scope.pop());
        }
        return args;
    }

    private String resolveName(int nameId) {
        ScriptNamesChunk names = file.getScriptNames();
        if (names != null) {
            return names.getName(nameId);
        }
        return "<unknown:" + nameId + ">";
    }

    private boolean datumEquals(Datum a, Datum b) {
        if (a.isNumber() && b.isNumber()) {
            return a.toDouble() == b.toDouble();
        }
        if (a.isString() && b.isString()) {
            return a.toStr().equalsIgnoreCase(b.toStr());
        }
        if (a.isSymbol() && b.isSymbol()) {
            return ((Datum.Symbol) a).name().equalsIgnoreCase(((Datum.Symbol) b).name());
        }
        return a.equals(b);
    }
}
