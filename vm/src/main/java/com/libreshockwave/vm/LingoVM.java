package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.Opcode;

import java.util.*;
import java.util.function.BiFunction;

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
    private final Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins;

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
        this.builtins = new HashMap<>();
        registerBuiltins();
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
        if (builtins.containsKey(handlerName)) {
            return builtins.get(handlerName).apply(this, args);
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
            String handlerName = resolveName(handler.nameId());
            String scriptType = script.scriptType() != null ? script.scriptType().name() : "UNKNOWN";
            handlerInfo = new TraceListener.HandlerInfo(
                handlerName,
                script.id(),
                scriptType,
                args,
                receiver,
                new HashMap<>(globals),
                script.literals(),
                handler.localCount(),
                handler.argCount()
            );

            if (traceEnabled) {
                traceHandlerEnter(handlerInfo);
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
                traceHandlerExit(handlerInfo, result);
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
            TraceListener.InstructionInfo instrInfo = buildInstructionInfo(scope, instr);
            if (traceEnabled) {
                traceInstruction(instrInfo);
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
                // Return factory - just return void for now
                scope.setReturnValue(Datum.VOID);
            }

            // Push constants
            case PUSH_ZERO -> scope.push(Datum.ZERO);
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> scope.push(Datum.of(arg));
            case PUSH_FLOAT32 -> scope.push(Datum.of(Float.intBitsToFloat(arg)));
            case PUSH_CONS -> {
                // Push constant from literals table
                List<ScriptChunk.LiteralEntry> literals = scope.getScript().literals();
                if (arg >= 0 && arg < literals.size()) {
                    ScriptChunk.LiteralEntry lit = literals.get(arg);
                    Datum value = switch (lit.type()) {
                        case 1 -> Datum.of((String) lit.value()); // String
                        case 4 -> Datum.of((Integer) lit.value()); // Int
                        case 9 -> Datum.of((Double) lit.value()); // Float
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
                // Parameters are generally read-only in Lingo but we'll support it
                // This would require mutable arguments which we don't have
                scope.pop(); // Just discard
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
                    return; // Don't advance
                }
            }
            case JMP_IF_Z -> {
                Datum cond = scope.pop();
                if (!cond.isTruthy()) {
                    int target = instr.offset() + arg;
                    int targetIndex = scope.getHandler().getInstructionIndex(target);
                    if (targetIndex >= 0) {
                        scope.setBytecodeIndex(targetIndex);
                        return; // Don't advance
                    }
                }
            }
            case END_REPEAT -> {
                int target = instr.offset() - arg;
                int targetIndex = scope.getHandler().getInstructionIndex(target);
                if (targetIndex >= 0) {
                    scope.setBytecodeIndex(targetIndex);
                    return; // Don't advance
                }
            }

            // List operations
            case PUSH_LIST -> {
                // Pop count items and create list
                int count = arg;
                List<Datum> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(0, scope.pop());
                }
                scope.push(Datum.list(items));
            }
            case PUSH_PROP_LIST -> {
                // Pop count pairs (value, key) and create prop list
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
                // These are used before function calls - we handle them specially
                // For now just push a marker with the count
                scope.push(new Datum.Int(arg));
            }

            // Function calls
            case LOCAL_CALL -> {
                // Call a handler in the same script
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
                // Call an external handler by name
                String handlerName = resolveName(arg);
                Datum argCountDatum = scope.pop();
                int argCount = argCountDatum.toInt();
                List<Datum> args = popArgs(scope, argCount);

                // Try builtin first
                if (builtins.containsKey(handlerName)) {
                    Datum result = builtins.get(handlerName).apply(this, args);
                    scope.push(result);
                } else {
                    // Try to find handler in scripts
                    HandlerRef ref = findHandler(handlerName);
                    if (ref != null) {
                        Datum result = executeHandler(ref.script(), ref.handler(), args, null);
                        scope.push(result);
                    } else {
                        // Unknown handler - return void
                        scope.push(Datum.VOID);
                    }
                }
            }
            case OBJ_CALL -> {
                // Method call on object
                String methodName = resolveName(arg);
                Datum argCountDatum = scope.pop();
                int argCount = argCountDatum.toInt();
                List<Datum> args = popArgs(scope, argCount);
                Datum target = args.isEmpty() ? Datum.VOID : args.remove(0);

                // For now, just return void for object calls
                // Full implementation would dispatch to the object's script
                scope.push(Datum.VOID);
            }

            // Property access (simplified)
            case GET_PROP -> {
                String propName = resolveName(arg);
                // For behaviors, get from receiver's properties
                if (scope.getReceiver() instanceof Datum.ScriptInstance si) {
                    scope.push(si.properties().getOrDefault(propName, Datum.VOID));
                } else {
                    scope.push(Datum.VOID);
                }
            }
            case SET_PROP -> {
                String propName = resolveName(arg);
                Datum value = scope.pop();
                // For behaviors, set on receiver's properties
                if (scope.getReceiver() instanceof Datum.ScriptInstance si) {
                    si.properties().put(propName, value);
                    if (traceListener != null) {
                        traceListener.onVariableSet("property", "me." + propName, value);
                    }
                }
            }

            // Movie properties (stubs)
            case GET_MOVIE_PROP -> {
                // Return stub values for common movie properties
                scope.push(Datum.VOID);
            }
            case SET_MOVIE_PROP -> {
                scope.pop(); // Discard value
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
                // Get a builtin "the" property
                scope.push(Datum.VOID);
            }

            // Unimplemented opcodes
            default -> {
                // Log unimplemented opcode but continue
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

    // Trace helpers

    private TraceListener.InstructionInfo buildInstructionInfo(Scope scope, ScriptChunk.Handler.Instruction instr) {
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

    private String buildAnnotation(Scope scope, ScriptChunk.Handler.Instruction instr) {
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
            case PUSH_SYMB -> "<#" + resolveName(arg) + ">";
            case GET_LOCAL, SET_LOCAL -> "<local" + arg + ">";
            case GET_PARAM -> "<param" + arg + ">";
            case GET_GLOBAL, SET_GLOBAL, GET_GLOBAL2, SET_GLOBAL2 -> "<" + resolveName(arg) + ">";
            case GET_PROP, SET_PROP -> "<me." + resolveName(arg) + ">";
            case LOCAL_CALL, EXT_CALL, OBJ_CALL -> "<" + resolveName(arg) + "()>";
            case JMP, JMP_IF_Z -> "<offset " + arg + " -> " + (instr.offset() + arg) + ">";
            case END_REPEAT -> "<back " + arg + " -> " + (instr.offset() - arg) + ">";
            default -> "";
        };
    }

    private void traceInstruction(TraceListener.InstructionInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  [%3d] %-16s", info.offset(), info.opcode()));
        if (info.argument() != 0) {
            sb.append(String.format(" %d", info.argument()));
        }
        // Pad with dots
        while (sb.length() < 35) {
            sb.append('.');
        }
        if (!info.annotation().isEmpty()) {
            sb.append(' ').append(info.annotation());
        }
        sb.append(String.format(" (stack=%d)", info.stackSize()));
        System.out.println(sb);
    }

    private void traceHandlerEnter(TraceListener.HandlerInfo info) {
        System.out.println("=== ENTER: " + info.handlerName() + " ===");
        System.out.println("  Script: #" + info.scriptId() + " (" + info.scriptType() + ")");
        System.out.println("  Args: " + info.arguments());
        if (info.receiver() != null && !(info.receiver() instanceof Datum.Void)) {
            System.out.println("  Receiver: " + info.receiver());
        }
        if (!info.globals().isEmpty()) {
            System.out.println("  Globals: " + info.globals());
        }
        if (!info.literals().isEmpty()) {
            System.out.println("  Literals: " + formatLiterals(info.literals()));
        }
        System.out.println("  Locals: " + info.localCount() + ", ArgCount: " + info.argCount());
    }

    private void traceHandlerExit(TraceListener.HandlerInfo info, Datum returnValue) {
        System.out.println("=== EXIT: " + info.handlerName() + " => " + returnValue + " ===");
    }

    private String formatLiterals(List<ScriptChunk.LiteralEntry> literals) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(10, literals.size()); i++) {
            if (i > 0) sb.append(", ");
            ScriptChunk.LiteralEntry lit = literals.get(i);
            sb.append(i).append(":").append(lit.value());
        }
        if (literals.size() > 10) {
            sb.append(", ... (").append(literals.size()).append(" total)");
        }
        sb.append("]");
        return sb.toString();
    }

    // Builtin handlers

    private void registerBuiltins() {
        // Math functions
        builtins.put("abs", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            Datum a = args.get(0);
            if (a.isFloat()) return Datum.of(Math.abs(a.toDouble()));
            return Datum.of(Math.abs(a.toInt()));
        });

        builtins.put("sqrt", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            return Datum.of(Math.sqrt(args.get(0).toDouble()));
        });

        builtins.put("sin", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            return Datum.of(Math.sin(Math.toRadians(args.get(0).toDouble())));
        });

        builtins.put("cos", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            return Datum.of(Math.cos(Math.toRadians(args.get(0).toDouble())));
        });

        builtins.put("random", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(1);
            int max = args.get(0).toInt();
            if (max <= 0) return Datum.of(1);
            return Datum.of((int) (Math.random() * max) + 1);
        });

        builtins.put("integer", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            return Datum.of(args.get(0).toInt());
        });

        builtins.put("float", (vm, args) -> {
            if (args.isEmpty()) return Datum.of(0.0);
            return Datum.of(args.get(0).toDouble());
        });

        // String functions
        builtins.put("string", (vm, args) -> {
            if (args.isEmpty()) return Datum.EMPTY_STRING;
            return Datum.of(args.get(0).toStr());
        });

        builtins.put("length", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            Datum a = args.get(0);
            if (a instanceof Datum.Str s) {
                return Datum.of(s.value().length());
            } else if (a instanceof Datum.List l) {
                return Datum.of(l.items().size());
            } else if (a instanceof Datum.PropList p) {
                return Datum.of(p.properties().size());
            }
            return Datum.ZERO;
        });

        builtins.put("chars", (vm, args) -> {
            if (args.size() < 3) return Datum.EMPTY_STRING;
            String str = args.get(0).toStr();
            int start = args.get(1).toInt() - 1; // Lingo is 1-indexed
            int end = args.get(2).toInt();
            if (start < 0) start = 0;
            if (end > str.length()) end = str.length();
            if (start >= end) return Datum.EMPTY_STRING;
            return Datum.of(str.substring(start, end));
        });

        builtins.put("charToNum", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            String s = args.get(0).toStr();
            if (s.isEmpty()) return Datum.ZERO;
            return Datum.of((int) s.charAt(0));
        });

        builtins.put("numToChar", (vm, args) -> {
            if (args.isEmpty()) return Datum.EMPTY_STRING;
            int code = args.get(0).toInt();
            return Datum.of(String.valueOf((char) code));
        });

        // List functions
        builtins.put("count", (vm, args) -> {
            if (args.isEmpty()) return Datum.ZERO;
            Datum a = args.get(0);
            if (a instanceof Datum.List l) {
                return Datum.of(l.items().size());
            } else if (a instanceof Datum.PropList p) {
                return Datum.of(p.properties().size());
            }
            return Datum.ZERO;
        });

        builtins.put("getAt", (vm, args) -> {
            if (args.size() < 2) return Datum.VOID;
            Datum list = args.get(0);
            int index = args.get(1).toInt() - 1; // Lingo is 1-indexed
            if (list instanceof Datum.List l) {
                if (index >= 0 && index < l.items().size()) {
                    return l.items().get(index);
                }
            }
            return Datum.VOID;
        });

        // Output (stub)
        builtins.put("put", (vm, args) -> {
            // Just log to console
            for (Datum arg : args) {
                System.out.print(arg.toStr() + " ");
            }
            System.out.println();
            return Datum.VOID;
        });

        builtins.put("alert", (vm, args) -> {
            String msg = args.isEmpty() ? "" : args.get(0).toStr();
            System.out.println("[ALERT] " + msg);
            return Datum.VOID;
        });

        // Constructors
        builtins.put("point", (vm, args) -> {
            int x = args.size() > 0 ? args.get(0).toInt() : 0;
            int y = args.size() > 1 ? args.get(1).toInt() : 0;
            return new Datum.Point(x, y);
        });

        builtins.put("rect", (vm, args) -> {
            int left = args.size() > 0 ? args.get(0).toInt() : 0;
            int top = args.size() > 1 ? args.get(1).toInt() : 0;
            int right = args.size() > 2 ? args.get(2).toInt() : 0;
            int bottom = args.size() > 3 ? args.get(3).toInt() : 0;
            return new Datum.Rect(left, top, right, bottom);
        });

        builtins.put("color", (vm, args) -> {
            int r = args.size() > 0 ? args.get(0).toInt() : 0;
            int g = args.size() > 1 ? args.get(1).toInt() : 0;
            int b = args.size() > 2 ? args.get(2).toInt() : 0;
            return new Datum.Color(r, g, b);
        });

        // Event propagation
        builtins.put("pass", (vm, args) -> {
            if (vm.passCallback != null) {
                vm.passCallback.run();
            }
            return Datum.VOID;
        });
    }

    /**
     * Reference to a handler within a script.
     */
    public record HandlerRef(ScriptChunk script, ScriptChunk.Handler handler) {}
}
