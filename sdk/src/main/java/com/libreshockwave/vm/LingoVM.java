package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.handlers.HandlerRegistry;
import com.libreshockwave.lingo.*;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.util.*;

/**
 * Lingo bytecode virtual machine.
 * Executes Director Lingo scripts.
 */
public class LingoVM {

    private final DirectorFile file;
    private final Map<String, Datum> globals = new HashMap<>();
    private final Deque<Datum> stack = new ArrayDeque<>();
    private final Deque<Scope> callStack = new ArrayDeque<>();
    private final Map<String, BuiltinHandler> builtins = new HashMap<>();

    private boolean halted;
    private int maxInstructions = 100000;
    private int maxCallDepth = 500;  // Prevent stack overflow from deep recursion
    private NetManager netManager;
    private CastManager castManager;
    private Datum lastHandlerResult = Datum.voidValue();

    // Movie state
    private final PropertyAccessor.MovieState movieState = new PropertyAccessor.MovieState();

    // Debug support
    private boolean debugMode = false;
    private int debugIndent = 0;
    private DebugOutputCallback debugOutputCallback;

    // Helper components
    private ScriptResolver scriptResolver;
    private PropertyAccessor propertyAccessor;
    private MethodDispatcher methodDispatcher;
    private DebugFormatter debugFormatter;

    // Callbacks
    private StageCallback stageCallback;
    private XtraInstanceCallback xtraInstanceCallback;
    private ActiveScriptInstancesCallback activeScriptInstancesCallback;

    @FunctionalInterface
    public interface DebugOutputCallback {
        void onDebugOutput(String message);
    }

    @FunctionalInterface
    public interface BuiltinHandler {
        Datum call(LingoVM vm, List<Datum> args);
    }

    public interface StageCallback {
        void moveToFront();
        void moveToBack();
        void close();
    }

    public interface XtraInstanceCallback {
        Datum callMethod(String xtraName, int instanceId, String methodName, List<Datum> args);
    }

    public interface ActiveScriptInstancesCallback {
        List<Datum.ScriptInstanceRef> getActiveScriptInstances();
    }

    public LingoVM(DirectorFile file) {
        this.file = file;
        initializeComponents();
        HandlerRegistry.registerAll(this);
    }

    private void initializeComponents() {
        this.scriptResolver = new ScriptResolver(file, castManager, callStack);
        this.propertyAccessor = new PropertyAccessor(this);
        this.methodDispatcher = new MethodDispatcher(this, scriptResolver, this::execute);
        this.debugFormatter = new DebugFormatter(this);
    }

    // Public API

    public DirectorFile getFile() { return file; }
    public Datum getGlobal(String name) { return globals.getOrDefault(name, Datum.voidValue()); }
    public void setGlobal(String name, Datum value) { globals.put(name, value); }
    public Map<String, Datum> getAllGlobals() { return Collections.unmodifiableMap(globals); }
    public void halt() { halted = true; }
    public void setMaxInstructions(int max) { this.maxInstructions = max; }
    public void setNetManager(NetManager netManager) { this.netManager = netManager; }
    public NetManager getNetManager() { return netManager; }
    public void setCastManager(CastManager castManager) {
        this.castManager = castManager;
        this.scriptResolver = new ScriptResolver(file, castManager, callStack);
        this.methodDispatcher = new MethodDispatcher(this, scriptResolver, this::execute);
    }
    public CastManager getCastManager() { return castManager; }
    public Datum getLastHandlerResult() { return lastHandlerResult; }
    public String getItemDelimiter() { return movieState.itemDelimiter; }
    public int getFloatPrecision() { return movieState.floatPrecision; }
    public boolean isExitLock() { return movieState.exitLock; }
    public boolean isUpdateLock() { return movieState.updateLock; }

    // Callback setters
    public void setDebugOutputCallback(DebugOutputCallback callback) { this.debugOutputCallback = callback; }
    public void setStageCallback(StageCallback callback) { this.stageCallback = callback; }
    public StageCallback getStageCallback() { return stageCallback; }
    public void setXtraInstanceCallback(XtraInstanceCallback callback) { this.xtraInstanceCallback = callback; }
    public void setActiveScriptInstancesCallback(ActiveScriptInstancesCallback callback) { this.activeScriptInstancesCallback = callback; }

    // Movie state setters
    public void setCurrentFrame(int frame) { movieState.currentFrame = frame; }
    public void setCurrentFrameLabel(String label) { movieState.currentFrameLabel = label; }
    public void setMousePosition(int x, int y) { movieState.mouseX = x; movieState.mouseY = y; }
    public void setRolloverSprite(int sprite) { movieState.rolloverSprite = sprite; }
    public void setClickOnSprite(int sprite) { movieState.clickOnSprite = sprite; }
    public void setDoubleClick(boolean doubleClick) { movieState.isDoubleClick = doubleClick; }
    public void setKeyboardState(int keyCode, String key, boolean shift, boolean control, boolean command, boolean alt) {
        movieState.keyCode = keyCode; movieState.lastKey = key;
        movieState.shiftDown = shift; movieState.controlDown = control;
        movieState.commandDown = command; movieState.altDown = alt;
    }

    // Debug support
    public void setDebugMode(boolean enabled) { this.debugMode = enabled; }
    public boolean isDebugMode() { return debugMode; }

    private void debugLog(String message) {
        if (!debugMode) return;
        String output = "[VM] " + "  ".repeat(debugIndent) + message;
        if (debugOutputCallback != null) debugOutputCallback.onDebugOutput(output);
        else System.out.println(output);
    }

    // Builtin registration
    public void registerBuiltin(String name, BuiltinHandler handler) {
        builtins.put(name.toLowerCase(), handler);
    }

    public BuiltinHandler getBuiltin(String name) {
        return builtins.get(name.toLowerCase());
    }

    // Script resolution delegates
    public String getScriptMemberName(ScriptChunk script) { return scriptResolver.getScriptMemberName(script); }
    public String getScriptMemberName(ScriptChunk script, CastLib sourceCast) { return scriptResolver.getScriptMemberName(script, sourceCast); }

    // Script execution

    public Datum call(String handlerName, Datum... args) { return call(handlerName, Arrays.asList(args)); }

    public Datum call(String handlerName, List<Datum> args) {
        BuiltinHandler builtin = builtins.get(handlerName.toLowerCase());
        if (builtin != null) return builtin.call(this, args);

        ScriptResolver.HandlerLocation loc = scriptResolver.findHandler(handlerName);
        if (loc == null) throw LingoException.undefinedHandler(handlerName);
        return execute(loc.script(), loc.handler(), args.toArray(new Datum[0]));
    }

    public Datum execute(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        // Check for excessive recursion before pushing to call stack
        if (callStack.size() >= maxCallDepth) {
            String handlerName = scriptResolver.getName(handler.nameId());
            throw new LingoException("Maximum call depth exceeded (" + maxCallDepth + ") - possible infinite recursion in handler '" + handlerName + "'");
        }

        if (debugMode) logHandlerEntry(script, handler, args);

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
                        debugFormatter.formatInstruction(instr, scriptResolver),
                        debugFormatter.formatStack(stack)));
                }
                executeInstruction(scope, instr);
                scope.advanceIP();
                instructionCount++;
            }
            if (instructionCount >= maxInstructions) {
                throw new LingoException("Execution limit exceeded: " + maxInstructions + " instructions");
            }
            Datum result = scope.getReturnValue();
            if (debugMode) { debugLog("=== RETURN " + debugFormatter.formatDatum(result) + " ==="); }
            return result;
        } finally {
            callStack.pop();
            if (debugMode) debugIndent = Math.max(0, debugIndent - 1);
        }
    }

    private void logHandlerEntry(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        String handlerName = scriptResolver.getName(handler.nameId());
        String scriptType = script.scriptType() != null ? script.scriptType().name() : "UNKNOWN";
        String memberName = scriptResolver.getScriptMemberName(script);
        CastLib sourceCast = scriptResolver.findCastForScript(script);
        String castInfo = sourceCast != null
            ? " in cast#" + sourceCast.getNumber() + (sourceCast.getName().isEmpty() ? "" : " \"" + sourceCast.getName() + "\"")
            : "";
        String scriptInfo = memberName != null && !memberName.isEmpty()
            ? scriptType + " \"" + memberName + "\"" + castInfo
            : scriptType + " script#" + script.id() + castInfo;
        String callerInfo = !callStack.isEmpty()
            ? " [called from " + scriptResolver.getName(callStack.peek().getHandler().nameId()) + " at IP:" + callStack.peek().getInstructionPointer() + "]"
            : "";
        debugLog("=== CALL " + handlerName + " in " + scriptInfo + callerInfo + " ===");
        debugLog("Args: " + debugFormatter.formatArgs(args));
        debugIndent++;
    }

    private void executeInstruction(Scope scope, ScriptChunk.Handler.Instruction instr) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        switch (op) {
            // Stack operations
            case PUSH_ZERO -> push(Datum.of(0));
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> push(Datum.of(arg));
            case PUSH_FLOAT32 -> push(Datum.of(Float.intBitsToFloat(arg)));
            case PUSH_CONS -> push(getLiteral(scope.getScript(), arg));
            case PUSH_SYMB -> push(Datum.symbol(scriptResolver.getName(arg)));
            case POP -> { for (int i = 0; i < arg; i++) if (!stack.isEmpty()) stack.pop(); }
            case SWAP -> { if (stack.size() >= 2) { Datum a = stack.pop(); Datum b = stack.pop(); stack.push(a); stack.push(b); } }
            case PEEK -> { if (!stack.isEmpty()) push(stack.peek()); }

            // Arithmetic
            case ADD -> { Datum b = pop(), a = pop(); push(add(a, b)); }
            case SUB -> { Datum b = pop(), a = pop(); push(subtract(a, b)); }
            case MUL -> { Datum b = pop(), a = pop(); push(multiply(a, b)); }
            case DIV -> { Datum b = pop(), a = pop(); push(divide(a, b)); }
            case MOD -> { Datum b = pop(), a = pop(); push(modulo(a, b)); }
            case INV -> push(negate(pop()));

            // Comparison
            case LT -> { Datum b = pop(), a = pop(); push(compare(a, b) < 0 ? Datum.TRUE : Datum.FALSE); }
            case LT_EQ -> { Datum b = pop(), a = pop(); push(compare(a, b) <= 0 ? Datum.TRUE : Datum.FALSE); }
            case GT -> { Datum b = pop(), a = pop(); push(compare(a, b) > 0 ? Datum.TRUE : Datum.FALSE); }
            case GT_EQ -> { Datum b = pop(), a = pop(); push(compare(a, b) >= 0 ? Datum.TRUE : Datum.FALSE); }
            case EQ -> { Datum b = pop(), a = pop(); push(equals(a, b) ? Datum.TRUE : Datum.FALSE); }
            case NT_EQ -> { Datum b = pop(), a = pop(); push(!equals(a, b) ? Datum.TRUE : Datum.FALSE); }

            // Logical
            case AND -> { Datum b = pop(), a = pop(); push(a.boolValue() && b.boolValue() ? Datum.TRUE : Datum.FALSE); }
            case OR -> { Datum b = pop(), a = pop(); push(a.boolValue() || b.boolValue() ? Datum.TRUE : Datum.FALSE); }
            case NOT -> push(!pop().boolValue() ? Datum.TRUE : Datum.FALSE);

            // String operations
            case JOIN_STR -> { Datum b = pop(), a = pop(); push(Datum.of(a.stringValue() + b.stringValue())); }
            case JOIN_PAD_STR -> { Datum b = pop(), a = pop(); push(Datum.of(a.stringValue() + " " + b.stringValue())); }
            case CONTAINS_STR, CONTAINS_0_STR -> { Datum b = pop(), a = pop(); push(a.stringValue().toLowerCase().contains(b.stringValue().toLowerCase()) ? Datum.TRUE : Datum.FALSE); }

            // Variables
            case GET_LOCAL -> { int idx = arg / scriptResolver.getVariableMultiplier(); push(scope.getLocal(idx)); }
            case SET_LOCAL -> { int idx = arg / scriptResolver.getVariableMultiplier(); scope.setLocal(idx, pop()); }
            case GET_PARAM -> { int idx = arg / scriptResolver.getVariableMultiplier(); push(scope.getArg(idx)); }
            case SET_PARAM -> { int idx = arg / scriptResolver.getVariableMultiplier(); scope.setArg(idx, pop()); }
            case GET_GLOBAL, GET_GLOBAL2 -> push(getGlobal(scriptResolver.getName(arg)));
            case SET_GLOBAL, SET_GLOBAL2 -> setGlobal(scriptResolver.getName(arg), pop());

            // Control flow - using bytecodeIndexMap for O(1) offset lookups
            case JMP -> {
                int targetOffset = instr.offset() + arg;
                int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
                if (targetIndex >= 0) {
                    scope.setInstructionPointer(targetIndex - 1);
                } else {
                    throw new LingoException("JMP target offset not found: " + targetOffset);
                }
            }
            case JMP_IF_Z -> {
                // Conditional jump - used for repeat loops and if statements
                if (!pop().boolValue()) {
                    // Condition is false - jump forward (exit loop or skip if-body)
                    int targetOffset = instr.offset() + arg;
                    int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
                    if (targetIndex >= 0) {
                        scope.setInstructionPointer(targetIndex - 1);
                    } else {
                        throw new LingoException("JMP_IF_Z target offset not found: " + targetOffset);
                    }
                }
                // If condition is true, continue to next instruction
            }
            case END_REPEAT -> {
                // Jump back to loop start
                int targetOffset = instr.offset() - arg;
                int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
                if (targetIndex >= 0) {
                    scope.setInstructionPointer(targetIndex - 1);
                } else {
                    throw new LingoException("END_REPEAT target offset not found: " + targetOffset);
                }
            }
            case RET -> { if (!stack.isEmpty()) scope.setReturnValue(pop()); scope.setInstructionPointer(scope.getHandler().instructions().size()); }
            case RET_FACTORY -> { scope.setReturnValue(Datum.voidValue()); scope.setInstructionPointer(scope.getHandler().instructions().size()); }

            // Lists
            case PUSH_LIST -> { List<Datum> items = popN(arg); Datum.DList list = Datum.list(); items.forEach(list::add); push(list); }
            case PUSH_PROP_LIST -> { Datum.PropList pl = Datum.propList(); for (int i = 0; i < arg; i++) { Datum v = pop(), k = pop(); pl.put(k, v); } push(pl); }
            case PUSH_ARG_LIST -> push(new Datum.ArgList(popN(arg)));
            case PUSH_ARG_LIST_NO_RET -> push(new Datum.ArgListNoRet(popN(arg)));

            // Function calls
            case EXT_CALL -> executeExtCall(scope, scriptResolver.getName(arg));
            case LOCAL_CALL -> executeLocalCall(scope, arg);

            // Property access
            case GET_PROP, GET_OBJ_PROP, GET_CHAINED_PROP -> push(propertyAccessor.getProperty(pop(), scriptResolver.getName(arg), this::getActiveInstances));
            case SET_PROP, SET_OBJ_PROP -> { Datum v = pop(), o = pop(); propertyAccessor.setProperty(o, scriptResolver.getName(arg), v, this::getActiveInstances); }
            case GET_TOP_LEVEL_PROP -> push(getGlobal(scriptResolver.getName(arg)));
            case GET_MOVIE_PROP -> push(propertyAccessor.getMovieProperty(scriptResolver.getName(arg), movieState));
            case SET_MOVIE_PROP -> propertyAccessor.setMovieProperty(scriptResolver.getName(arg), pop(), movieState);

            // Object calls
            case OBJ_CALL, OBJ_CALL_V4 -> executeObjCall(scriptResolver.getName(arg));

            // Field access
            case GET_FIELD -> executeGetField(scope);

            // The entity
            case THE_BUILTIN -> push(propertyAccessor.getMovieProperty(scriptResolver.getName(arg), movieState));
            case GET -> push(getGlobal(scriptResolver.getName(arg)));
            case SET -> setGlobal(scriptResolver.getName(arg), pop());
            case PUT -> System.out.println(pop().stringValue());

            // Object creation
            case NEW_OBJ -> { List<Datum> args2 = extractArgList(pop()); push(new Datum.ScriptInstanceRef(scriptResolver.getName(arg), new HashMap<>())); }
            case PUSH_VAR_REF, PUSH_CHUNK_VAR_REF -> push(new Datum.VarRef(scriptResolver.getName(arg)));

            // String chunks
            case GET_CHUNK -> { Datum e = pop(), s = pop(), t = pop(), str = pop(); push(getStringChunk(str.stringValue(), t, s.intValue(), e.intValue())); }
            case PUT_CHUNK, DELETE_CHUNK, HILITE_CHUNK -> { for (int i = 0; i < 3; i++) pop(); }

            // Tell blocks
            case START_TELL -> scope.pushTellTarget(pop());
            case END_TELL -> scope.popTellTarget();
            case TELL_CALL -> { String name = scriptResolver.getName(arg); List<Datum> args2 = extractArgList(pop()); Datum t = scope.getTellTarget(); push(t != null ? methodDispatcher.callMethod(t, name, args2, xtraInstanceCallback) : call(name, args2)); }

            // Sprite tests
            case ONTO_SPR, INTO_SPR -> { pop(); pop(); push(Datum.FALSE); }

            case CALL_JAVASCRIPT -> push(Datum.voidValue());

            default -> System.err.println("Unimplemented opcode: " + op + " (0x" + Integer.toHexString(op.getCode()) + ")");
        }
    }

    private void executeExtCall(Scope scope, String handlerName) {
        Datum argListDatum = pop();
        boolean isNoRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = extractArgList(argListDatum);

        if (handlerName.contains("return")) {
            Datum returnValue = args.isEmpty() ? Datum.voidValue() : args.get(0);
            scope.setReturnValue(returnValue);
            scope.setInstructionPointer(scope.getHandler().instructions().size());
            if (!isNoRet) push(returnValue);
            return;
        }

        Datum result = callGlobalHandler(handlerName, args, scope);
        lastHandlerResult = result;
        scope.setReturnValue(result);
        if (!isNoRet) push(result);
    }

    private void executeLocalCall(Scope scope, int handlerIndex) {
        Datum argListDatum = pop();
        boolean isNoRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = extractArgList(argListDatum);

        ScriptChunk script = scope.getScript();
        if (handlerIndex >= 0 && handlerIndex < script.handlers().size()) {
            ScriptChunk.Handler targetHandler = script.handlers().get(handlerIndex);
            String handlerName = scriptResolver.getName(targetHandler.nameId());

            // Check first arg for script instance with this handler
            if (handlerName != null && !args.isEmpty()) {
                ScriptResolver.HandlerLocation loc = getHandlerFromFirstArg(args, handlerName);
                if (loc != null) {
                    Datum result = execute(loc.script(), loc.handler(), args.toArray(new Datum[0]));
                    lastHandlerResult = result;
                    if (!isNoRet) push(result);
                    return;
                }
            }

            Datum result = execute(script, targetHandler, args.toArray(new Datum[0]));
            lastHandlerResult = result;
            if (!isNoRet) push(result);
        } else if (!isNoRet) {
            push(Datum.voidValue());
        }
    }

    private void executeObjCall(String methodName) {
        Datum argListDatum = pop();
        boolean isNoRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = extractArgList(argListDatum);
        Datum obj = args.isEmpty() ? Datum.voidValue() : args.remove(0);
        Datum result = methodDispatcher.callMethod(obj, methodName, args, xtraInstanceCallback);
        lastHandlerResult = result;
        if (!isNoRet) push(result);
    }

    private void executeGetField(Scope scope) {
        int dirVersion = file != null && file.getConfig() != null ? file.getConfig().directorVersion() : 0;
        Datum castId = dirVersion >= 500 ? pop() : Datum.of(0);
        if (castId.intValue() <= 0) {
            CastLib scriptCast = scriptResolver.findCastForScript(scope.getScript());
            castId = scriptCast != null ? Datum.of(scriptCast.getNumber()) : Datum.of(1);
        }
        push(Datum.of(propertyAccessor.getFieldText(pop(), castId, scriptResolver)));
    }

    private Datum callGlobalHandler(String handlerName, List<Datum> args, Scope scope) {
        // 1. Check first arg for script/instance with handler (explicit receiver)
        if (!handlerName.equalsIgnoreCase("new") && !args.isEmpty()) {
            ScriptResolver.HandlerLocation loc = getHandlerFromFirstArg(args, handlerName);
            if (loc != null) return execute(loc.script(), loc.handler(), args.toArray(new Datum[0]));
        }

        // 2. Check builtins BEFORE script handlers to avoid shadowing built-in functions
        BuiltinHandler builtin = builtins.get(handlerName.toLowerCase());
        if (builtin != null) return builtin.call(this, args);

        // 3. Check static scripts (movie scripts, behaviors)
        if (!handlerName.equalsIgnoreCase("new")) {
            ScriptResolver.HandlerLocation staticLoc = scriptResolver.findHandler(handlerName);
            if (staticLoc != null) return execute(staticLoc.script(), staticLoc.handler(), args.toArray(new Datum[0]));

            // 4. Check active script instances (for handlers not found elsewhere)
            if (activeScriptInstancesCallback != null) {
                for (Datum.ScriptInstanceRef ref : activeScriptInstancesCallback.getActiveScriptInstances()) {
                    ScriptChunk script = scriptResolver.findScriptByName(ref.scriptName());
                    if (script != null) {
                        for (ScriptChunk.Handler h : script.handlers()) {
                            if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                                List<Datum> allArgs = new ArrayList<>();
                                allArgs.add(ref);
                                allArgs.addAll(args);
                                return execute(script, h, allArgs.toArray(new Datum[0]));
                            }
                        }
                    }
                }
            }
        }

        throw LingoException.undefinedHandler(handlerName);
    }

    private ScriptResolver.HandlerLocation getHandlerFromFirstArg(List<Datum> args, String handlerName) {
        if (args.isEmpty()) return null;
        Datum firstArg = args.get(0);

        if (firstArg instanceof Datum.ScriptInstanceRef si) {
            ScriptChunk script = scriptResolver.findScriptByName(si.scriptName());
            if (script != null) {
                for (ScriptChunk.Handler h : script.handlers()) {
                    if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                        return new ScriptResolver.HandlerLocation(script, h, null, null);
                    }
                }
            }
        }

        if (firstArg instanceof Datum.ScriptRef sr) {
            ScriptChunk script = scriptResolver.findScriptByCastRef(sr.memberRef());
            if (script != null) {
                for (ScriptChunk.Handler h : script.handlers()) {
                    if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                        return new ScriptResolver.HandlerLocation(script, h, null, null);
                    }
                }
            }
        }

        return null;
    }

    public Datum createScriptInstance(Datum.ScriptRef scriptRef, List<Datum> constructorArgs) {
        ScriptChunk script = scriptResolver.findScriptByCastRef(scriptRef.memberRef());
        if (script == null) throw new LingoException("Cannot find script for " + scriptRef.memberRef());

        String scriptName = scriptResolver.getScriptMemberName(script);
        if (scriptName == null || scriptName.isEmpty()) scriptName = "script_" + script.id();

        Map<String, Datum> props = new LinkedHashMap<>();
        for (ScriptChunk.PropertyEntry prop : script.properties()) {
            String propName = scriptResolver.getName(prop.nameId());
            if (propName != null) props.put(propName, Datum.voidValue());
        }

        Datum.ScriptInstanceRef instance = new Datum.ScriptInstanceRef(scriptName, scriptRef.memberRef(), props);

        // Call "new" handler if present
        for (ScriptChunk.Handler h : script.handlers()) {
            if ("new".equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                List<Datum> paddedArgs = new ArrayList<>(constructorArgs);
                while (paddedArgs.size() < h.argNameIds().size()) paddedArgs.add(Datum.voidValue());
                List<Datum> allArgs = new ArrayList<>();
                allArgs.add(instance);
                allArgs.addAll(paddedArgs);
                Datum result = execute(script, h, allArgs.toArray(new Datum[0]));
                return result instanceof Datum.Void ? instance : result;
            }
        }

        return instance;
    }

    /**
     * Handle the call(#handler, receiver, args...) builtin.
     */
    public Datum callHandler(List<Datum> args) {
        if (args.size() < 2 || !(args.get(0) instanceof Datum.Symbol symbol)) return Datum.voidValue();

        String handlerName = symbol.name();
        Datum receiver = args.get(1);
        List<Datum> handlerArgs = args.size() > 2 ? args.subList(2, args.size()) : new ArrayList<>();

        List<Datum.ScriptInstanceRef> instances = new ArrayList<>();
        methodDispatcher.collectScriptInstances(receiver, instances, activeScriptInstancesCallback);

        if (instances.isEmpty()) return methodDispatcher.callMethod(receiver, handlerName, handlerArgs, xtraInstanceCallback);

        Datum result = Datum.voidValue();
        for (Datum.ScriptInstanceRef instance : instances) {
            ScriptChunk script = scriptResolver.findScriptByName(instance.scriptName());
            if (script != null) {
                for (ScriptChunk.Handler h : script.handlers()) {
                    if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                        List<Datum> allArgs = new ArrayList<>();
                        allArgs.add(instance);
                        allArgs.addAll(handlerArgs);
                        result = execute(script, h, allArgs.toArray(new Datum[0]));
                        break;
                    }
                }
            }
        }
        return result;
    }

    private List<Datum.ScriptInstanceRef> getActiveInstances() {
        return activeScriptInstancesCallback != null ? activeScriptInstancesCallback.getActiveScriptInstances() : List.of();
    }

    // Stack operations
    private void push(Datum value) { stack.push(value); }
    private Datum pop() { return stack.isEmpty() ? Datum.voidValue() : stack.pop(); }
    private List<Datum> popN(int n) { List<Datum> items = new ArrayList<>(); for (int i = 0; i < n; i++) items.add(0, pop()); return items; }

    // Arithmetic operations
    private Datum add(Datum a, Datum b) { return a.isFloat() || b.isFloat() ? Datum.of(a.floatValue() + b.floatValue()) : Datum.of(a.intValue() + b.intValue()); }
    private Datum subtract(Datum a, Datum b) { return a.isFloat() || b.isFloat() ? Datum.of(a.floatValue() - b.floatValue()) : Datum.of(a.intValue() - b.intValue()); }
    private Datum multiply(Datum a, Datum b) { return a.isFloat() || b.isFloat() ? Datum.of(a.floatValue() * b.floatValue()) : Datum.of(a.intValue() * b.intValue()); }
    private Datum divide(Datum a, Datum b) {
        float bVal = b.floatValue();
        if (bVal == 0) throw new LingoException("Division by zero");
        return a.isInt() && b.isInt() && a.intValue() % b.intValue() == 0 ? Datum.of(a.intValue() / b.intValue()) : Datum.of(a.floatValue() / bVal);
    }
    private Datum modulo(Datum a, Datum b) { int bVal = b.intValue(); if (bVal == 0) throw new LingoException("Modulo by zero"); return Datum.of(a.intValue() % bVal); }
    private Datum negate(Datum a) { return a.isFloat() ? Datum.of(-a.floatValue()) : Datum.of(-a.intValue()); }
    private int compare(Datum a, Datum b) { return a.isNumber() && b.isNumber() ? Float.compare(a.floatValue(), b.floatValue()) : a.stringValue().compareToIgnoreCase(b.stringValue()); }
    private boolean equals(Datum a, Datum b) { return a.isNumber() && b.isNumber() ? a.floatValue() == b.floatValue() : a.stringValue().equalsIgnoreCase(b.stringValue()); }

    // Helper methods
    private Datum getLiteral(ScriptChunk script, int index) {
        if (index >= script.literals().size()) return Datum.voidValue();
        Object val = script.literals().get(index).value();
        if (val instanceof String s) return Datum.of(s);
        if (val instanceof Integer i) return Datum.of(i);
        if (val instanceof Double d) return Datum.of(d.floatValue());
        return Datum.voidValue();
    }

    private List<Datum> extractArgList(Datum argList) {
        if (argList instanceof Datum.ArgList al) return al.args();
        if (argList instanceof Datum.ArgListNoRet al) return al.args();
        return List.of();
    }

    private Datum getStringChunk(String str, Datum chunkType, int start, int end) {
        StringChunkType type = StringChunkType.CHAR;
        if (chunkType.isSymbol()) {
            type = switch (chunkType.stringValue().toLowerCase()) {
                case "word" -> StringChunkType.WORD;
                case "line" -> StringChunkType.LINE;
                case "item" -> StringChunkType.ITEM;
                default -> StringChunkType.CHAR;
            };
        } else if (chunkType.isInt()) {
            type = switch (chunkType.intValue()) { case 2 -> StringChunkType.WORD; case 3 -> StringChunkType.ITEM; case 4 -> StringChunkType.LINE; default -> StringChunkType.CHAR; };
        }
        return Datum.of(com.libreshockwave.handlers.StringHandlers.extractChunk(str, type, start, end, ','));
    }
}
