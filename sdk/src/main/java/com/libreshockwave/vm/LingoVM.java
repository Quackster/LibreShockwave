package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.handlers.HandlerRegistry;
import com.libreshockwave.lingo.*;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.player.Score;

import java.util.*;

/**
 * Lingo bytecode virtual machine.
 * Executes Director Lingo scripts.
 *
 * Matches dirplayer-rs architecture:
 * - Pre-allocated scope pool for efficient call stack management
 * - Per-scope evaluation stacks
 * - Unified handler execution via callHandler()
 */
public class LingoVM {

    // Maximum call stack depth (matches dirplayer-rs MAX_STACK_SIZE)
    private static final int MAX_STACK_SIZE = 50;

    private final DirectorFile file;
    private final Map<String, Datum> globals = new HashMap<>();
    private final Map<String, BuiltinHandler> builtins = new HashMap<>();

    // Pre-allocated scope pool (matches dirplayer-rs scopes: Vec<Scope>)
    private final List<Scope> scopes;
    private int scopeCount = 0;

    private boolean halted;
    private int maxInstructions = 100000;
    private NetManager netManager;
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

    // Score reference for active script instances (matches dirplayer-rs player.movie.score)
    private Score score;

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

    public LingoVM(DirectorFile file) {
        this.file = file;

        // Pre-allocate scope pool (matches dirplayer-rs)
        this.scopes = new ArrayList<>(MAX_STACK_SIZE);
        for (int i = 0; i < MAX_STACK_SIZE; i++) {
            scopes.add(new Scope(i));
        }

        initializeComponents();
        HandlerRegistry.registerAll(this);
    }

    private void initializeComponents() {
        this.scriptResolver = new ScriptResolver(file, file != null ? file.getCastManager() : null, this);
        this.propertyAccessor = new PropertyAccessor(this);
        this.methodDispatcher = new MethodDispatcher(this, scriptResolver, this::callHandlerDirect);
        this.debugFormatter = new DebugFormatter(this);
    }

    // === Scope Pool Management (matches dirplayer-rs push_scope/pop_scope) ===

    /**
     * Push a new scope onto the call stack.
     * Returns the scope reference (index).
     * Matches dirplayer-rs: player.push_scope()
     */
    private int pushScope() {
        if (scopeCount >= MAX_STACK_SIZE) {
            throw new LingoException("Stack overflow - maximum call depth (" + MAX_STACK_SIZE + ") exceeded");
        }
        int scopeRef = scopeCount;
        Scope scope = scopes.get(scopeRef);
        scope.reset();
        scopeCount++;
        return scopeRef;
    }

    /**
     * Pop the current scope from the call stack.
     * Matches dirplayer-rs: player.pop_scope()
     */
    private void popScope() {
        if (scopeCount > 0) {
            scopeCount--;
        }
    }

    /**
     * Get the current scope reference (index).
     * Matches dirplayer-rs: player.current_scope_ref()
     */
    public int currentScopeRef() {
        return scopeCount > 0 ? scopeCount - 1 : -1;
    }

    /**
     * Get a scope by reference.
     */
    public Scope getScope(int scopeRef) {
        if (scopeRef >= 0 && scopeRef < scopeCount) {
            return scopes.get(scopeRef);
        }
        return null;
    }

    /**
     * Get the current scope.
     */
    public Scope getCurrentScope() {
        return getScope(currentScopeRef());
    }

    /**
     * Get the scope count (call depth).
     */
    public int getScopeCount() {
        return scopeCount;
    }

    // === Public API ===

    public DirectorFile getFile() { return file; }
    public Datum getGlobal(String name) { return globals.getOrDefault(name, Datum.voidValue()); }
    public void setGlobal(String name, Datum value) { globals.put(name, value); }
    public Map<String, Datum> getAllGlobals() { return Collections.unmodifiableMap(globals); }
    public void halt() { halted = true; }
    public void setMaxInstructions(int max) { this.maxInstructions = max; }
    public void setNetManager(NetManager netManager) { this.netManager = netManager; }
    public NetManager getNetManager() { return netManager; }
    public void setCastManager(CastManager castManager) {
        if (file != null) {
            file.setCastManager(castManager);
        }
        this.scriptResolver = new ScriptResolver(file, castManager, this);
        this.methodDispatcher = new MethodDispatcher(this, scriptResolver, this::callHandlerDirect);
    }
    public CastManager getCastManager() { return file != null ? file.getCastManager() : null; }
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
    public void setScore(Score score) { this.score = score; }
    public Score getScore() { return score; }

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
    public ScriptResolver getScriptResolver() { return scriptResolver; }

    // === Unified Handler Execution ===

    /**
     * Call a handler by name with arguments.
     * This is the main public entry point for script execution.
     */
    public Datum call(String handlerName, Datum... args) {
        return call(handlerName, Arrays.asList(args));
    }

    public Datum call(String handlerName, List<Datum> args) {
        BuiltinHandler builtin = builtins.get(handlerName.toLowerCase());
        if (builtin != null) return builtin.call(this, args);

        ScriptResolver.HandlerLocation loc = scriptResolver.findHandler(handlerName);
        if (loc == null) throw LingoException.undefinedHandler(handlerName);
        return callHandler(null, loc.script(), loc.handler(), args);
    }

    /**
     * Legacy execute method for compatibility.
     * Delegates to unified callHandler().
     */
    public Datum execute(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        return callHandler(null, script, handler, Arrays.asList(args));
    }

    /**
     * Internal method for MethodDispatcher compatibility.
     */
    private Datum callHandlerDirect(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        return callHandler(null, script, handler, Arrays.asList(args));
    }

    /**
     * Unified handler execution method.
     * Matches dirplayer-rs: player_call_script_handler_raw_args()
     *
     * @param receiver Optional script instance receiver (null for static scripts)
     * @param script The script containing the handler
     * @param handler The handler to execute
     * @param args The arguments to pass
     * @return The result of execution
     */
    public Datum callHandler(Datum.ScriptInstanceRef receiver, ScriptChunk script,
                              ScriptChunk.Handler handler, List<Datum> args) {
        if (debugMode) logHandlerEntry(script, handler, args.toArray(new Datum[0]));

        // Push a new scope
        int scopeRef = pushScope();
        Scope scope = scopes.get(scopeRef);

        // Initialize scope for this handler
        Datum.CastMemberRef scriptMemberRef = null; // TODO: get from script
        scope.initialize(script, handler, scriptMemberRef);
        scope.setReceiver(receiver);
        scope.setArgs(args);

        int instructionCount = 0;
        halted = false;

        // Loop logging optimization
        Map<Integer, Integer> loopIterations = new HashMap<>();
        int loopStartIP = -1;
        boolean suppressLoopLogging = false;

        try {
            while (!scope.isAtEnd() && !halted && instructionCount < maxInstructions) {
                ScriptChunk.Handler.Instruction instr = scope.getCurrentInstruction();
                int currentIP = scope.getBytecodeIndex();

                // Debug logging with loop optimization
                if (debugMode) {
                    Opcode op = instr.opcode();
                    if (op == Opcode.END_REPEAT) {
                        int targetOffset = instr.offset() - instr.argument();
                        int loopStart = handler.getInstructionIndex(targetOffset);
                        if (loopStart >= 0) {
                            int iteration = loopIterations.getOrDefault(loopStart, 0) + 1;
                            loopIterations.put(loopStart, iteration);
                            if (iteration == 1) {
                                loopStartIP = loopStart;
                                suppressLoopLogging = true;
                                debugLog("[LOOP] First iteration complete, suppressing further loop logging");
                            } else if (iteration % 100 == 0) {
                                debugLog("[LOOP] Iteration " + iteration + "...");
                            }
                        }
                    }

                    if (!suppressLoopLogging || currentIP < loopStartIP || op == Opcode.JMP_IF_Z) {
                        debugLog(String.format("[%03d] %-20s | Stack: %s",
                            currentIP,
                            debugFormatter.formatInstruction(instr, scriptResolver),
                            debugFormatter.formatScopeStack(scope)));
                    }
                }

                int ipBeforeExec = scope.getBytecodeIndex();
                executeInstruction(scope, instr);

                if (debugMode && suppressLoopLogging && instr.opcode() == Opcode.JMP_IF_Z) {
                    int newIP = scope.getBytecodeIndex();
                    if (newIP > ipBeforeExec) {
                        int totalIterations = loopIterations.getOrDefault(loopStartIP, 0);
                        debugLog("[LOOP] Exited after " + totalIterations + " iterations");
                        suppressLoopLogging = false;
                        loopStartIP = -1;
                    }
                }

                scope.advanceIP();
                instructionCount++;
            }

            if (instructionCount >= maxInstructions) {
                throw new LingoException("Execution limit exceeded: " + maxInstructions + " instructions");
            }

            Datum result = scope.getReturnValue();
            lastHandlerResult = result;

            // Match dirplayer-rs: propagate passed flag to parent scope
            if (scope.isPassed() && scopeCount > 1) {
                Scope parentScope = scopes.get(scopeCount - 2);
                parentScope.setPassed(true);
            }

            if (debugMode) {
                debugLog("=== RETURN " + debugFormatter.formatDatum(result) + " ===");
            }

            return result;
        } finally {
            popScope();
            if (debugMode) debugIndent = Math.max(0, debugIndent - 1);
        }
    }

    /**
     * Result of scope execution.
     * Matches dirplayer-rs ScopeResult.
     */
    public record ScopeResult(Datum returnValue, boolean passed) {}

    /**
     * Handle scope return - propagate passed flag to parent scope.
     * Matches dirplayer-rs: player_handle_scope_return()
     */
    private void handleScopeReturn(ScopeResult result) {
        if (result.passed && scopeCount > 0) {
            Scope currentScope = scopes.get(scopeCount - 1);
            currentScope.setPassed(true);
        }
    }

    // === Script Name Resolution ===

    public String formatChunkName(ScriptChunk script) {
        String name = getScriptName(script);
        if (name == null) {
            name = script.scriptType().name().toUpperCase() + " #" + script.id();
        }
        return name;
    }

    public String getScriptName(ScriptChunk script) {
        DirectorFile scriptFile = script.file();
        Map<Integer, String> scriptIdToName = new HashMap<>();

        for (CastMemberChunk cm : scriptFile.getCastMembers()) {
            if (cm.isScript() && cm.scriptId() > 0) {
                scriptIdToName.put(cm.scriptId(), cm.name());
            }
        }

        if (scriptFile.getScriptContext() != null) {
            var entries = scriptFile.getScriptContext().entries();
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (entry.id() == script.id()) {
                    int scriptIndex = i + 1;
                    return scriptIdToName.getOrDefault(scriptIndex, null);
                }
            }
        }

        return null;
    }

    private void logHandlerEntry(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        String handlerName = scriptResolver.getName(handler.nameId());
        String scriptType = script.scriptType() != null ? script.scriptType().name() : "UNKNOWN";
        String memberName = this.formatChunkName(script);
        CastLib sourceCast = scriptResolver.findCastForScript(script);
        String castInfo = sourceCast != null
            ? " in cast#" + sourceCast.getNumber() + (sourceCast.getName().isEmpty() ? "" : " \"" + sourceCast.getName() + "\"")
            : "";
        String scriptInfo = memberName != null && !memberName.isEmpty()
            ? scriptType + " \"" + memberName + "\"" + castInfo
            : scriptType + " script#" + script.id() + castInfo;
        String callerInfo = scopeCount > 0
            ? " [called from " + scriptResolver.getName(getCurrentScope().getHandler().nameId()) + " at IP:" + getCurrentScope().getBytecodeIndex() + "]"
            : "";
        debugLog("=== CALL " + handlerName + " in " + scriptInfo + callerInfo + " ===");
        debugLog("Args: " + debugFormatter.formatArgs(args));
        debugIndent++;
    }

    // === Instruction Execution ===

    private void executeInstruction(Scope scope, ScriptChunk.Handler.Instruction instr) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        switch (op) {
            // Stack operations (now use per-scope stack)
            case PUSH_ZERO -> scope.push(Datum.of(0));
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> scope.push(Datum.of(arg));
            case PUSH_FLOAT32 -> scope.push(Datum.of(Float.intBitsToFloat(arg)));
            case PUSH_CONS -> scope.push(getLiteral(scope.getScript(), arg));
            case PUSH_SYMB -> scope.push(Datum.symbol(scriptResolver.getName(arg)));
            case POP -> { for (int i = 0; i < arg; i++) scope.pop(); }
            case SWAP -> scope.swap();
            case PEEK -> scope.push(scope.peek());

            // Arithmetic
            case ADD -> { Datum b = scope.pop(), a = scope.pop(); scope.push(add(a, b)); }
            case SUB -> { Datum b = scope.pop(), a = scope.pop(); scope.push(subtract(a, b)); }
            case MUL -> { Datum b = scope.pop(), a = scope.pop(); scope.push(multiply(a, b)); }
            case DIV -> { Datum b = scope.pop(), a = scope.pop(); scope.push(divide(a, b)); }
            case MOD -> { Datum b = scope.pop(), a = scope.pop(); scope.push(modulo(a, b)); }
            case INV -> scope.push(negate(scope.pop()));

            // Comparison
            case LT -> { Datum b = scope.pop(), a = scope.pop(); scope.push(compare(a, b) < 0 ? Datum.TRUE : Datum.FALSE); }
            case LT_EQ -> { Datum b = scope.pop(), a = scope.pop(); scope.push(compare(a, b) <= 0 ? Datum.TRUE : Datum.FALSE); }
            case GT -> { Datum b = scope.pop(), a = scope.pop(); scope.push(compare(a, b) > 0 ? Datum.TRUE : Datum.FALSE); }
            case GT_EQ -> { Datum b = scope.pop(), a = scope.pop(); scope.push(compare(a, b) >= 0 ? Datum.TRUE : Datum.FALSE); }
            case EQ -> { Datum b = scope.pop(), a = scope.pop(); scope.push(equals(a, b) ? Datum.TRUE : Datum.FALSE); }
            case NT_EQ -> { Datum b = scope.pop(), a = scope.pop(); scope.push(!equals(a, b) ? Datum.TRUE : Datum.FALSE); }

            // Logical
            case AND -> { Datum b = scope.pop(), a = scope.pop(); scope.push(a.boolValue() && b.boolValue() ? Datum.TRUE : Datum.FALSE); }
            case OR -> { Datum b = scope.pop(), a = scope.pop(); scope.push(a.boolValue() || b.boolValue() ? Datum.TRUE : Datum.FALSE); }
            case NOT -> scope.push(!scope.pop().boolValue() ? Datum.TRUE : Datum.FALSE);

            // String operations
            case JOIN_STR -> { Datum b = scope.pop(), a = scope.pop(); scope.push(Datum.of(a.stringValue() + b.stringValue())); }
            case JOIN_PAD_STR -> { Datum b = scope.pop(), a = scope.pop(); scope.push(Datum.of(a.stringValue() + " " + b.stringValue())); }
            case CONTAINS_STR, CONTAINS_0_STR -> { Datum b = scope.pop(), a = scope.pop(); scope.push(a.stringValue().toLowerCase().contains(b.stringValue().toLowerCase()) ? Datum.TRUE : Datum.FALSE); }

            // Variables
            case GET_LOCAL -> { int idx = arg / scriptResolver.getVariableMultiplier(); scope.push(scope.getLocal(idx)); }
            case SET_LOCAL -> { int idx = arg / scriptResolver.getVariableMultiplier(); scope.setLocal(idx, scope.pop()); }
            case GET_PARAM -> { int idx = arg / scriptResolver.getVariableMultiplier(); scope.push(scope.getArg(idx)); }
            case SET_PARAM -> { int idx = arg / scriptResolver.getVariableMultiplier(); scope.setArg(idx, scope.pop()); }
            case GET_GLOBAL, GET_GLOBAL2 -> scope.push(getGlobal(scriptResolver.getName(arg)));
            case SET_GLOBAL, SET_GLOBAL2 -> setGlobal(scriptResolver.getName(arg), scope.pop());

            // Control flow
            case JMP -> {
                int targetOffset = instr.offset() + arg;
                int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
                if (targetIndex >= 0) {
                    scope.setBytecodeIndex(targetIndex - 1);
                } else {
                    throw new LingoException("JMP target offset not found: " + targetOffset);
                }
            }
            case JMP_IF_Z -> {
                if (!scope.pop().boolValue()) {
                    int targetOffset = instr.offset() + arg;
                    int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
                    if (targetIndex >= 0) {
                        scope.setBytecodeIndex(targetIndex - 1);
                    } else {
                        throw new LingoException("JMP_IF_Z target offset not found: " + targetOffset);
                    }
                }
            }
            case END_REPEAT -> {
                int targetOffset = instr.offset() - arg;
                int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
                if (targetIndex >= 0) {
                    scope.setBytecodeIndex(targetIndex - 1);
                } else {
                    throw new LingoException("END_REPEAT target offset not found: " + targetOffset);
                }
            }
            case RET -> {
                if (scope.stackSize() > 0) scope.setReturnValue(scope.pop());
                scope.setBytecodeIndex(scope.getHandler().instructions().size());
            }
            case RET_FACTORY -> {
                scope.setReturnValue(Datum.voidValue());
                scope.setBytecodeIndex(scope.getHandler().instructions().size());
            }

            // Lists
            case PUSH_LIST -> { List<Datum> items = scope.popN(arg); Datum.DList list = Datum.list(); items.forEach(list::add); scope.push(list); }
            case PUSH_PROP_LIST -> { Datum.PropList pl = Datum.propList(); for (int i = 0; i < arg; i++) { Datum v = scope.pop(), k = scope.pop(); pl.put(k, v); } scope.push(pl); }
            case PUSH_ARG_LIST -> scope.push(new Datum.ArgList(scope.popN(arg)));
            case PUSH_ARG_LIST_NO_RET -> scope.push(new Datum.ArgListNoRet(scope.popN(arg)));

            // Function calls
            case EXT_CALL -> executeExtCall(scope, scriptResolver.getName(arg));
            case LOCAL_CALL -> executeLocalCall(scope, arg);

            // Property access
            case GET_PROP, GET_OBJ_PROP, GET_CHAINED_PROP -> scope.push(propertyAccessor.getProperty(scope.pop(), scriptResolver.getName(arg), this::getActiveScriptInstances));
            case SET_PROP, SET_OBJ_PROP -> { Datum v = scope.pop(), o = scope.pop(); propertyAccessor.setProperty(o, scriptResolver.getName(arg), v, this::getActiveScriptInstances); }
            case GET_TOP_LEVEL_PROP -> scope.push(getGlobal(scriptResolver.getName(arg)));
            case GET_MOVIE_PROP -> scope.push(propertyAccessor.getMovieProperty(scriptResolver.getName(arg), movieState));
            case SET_MOVIE_PROP -> propertyAccessor.setMovieProperty(scriptResolver.getName(arg), scope.pop(), movieState);

            // Object calls
            case OBJ_CALL, OBJ_CALL_V4 -> executeObjCall(scope, scriptResolver.getName(arg));

            // Field access
            case GET_FIELD -> executeGetField(scope);

            // The entity
            case THE_BUILTIN -> scope.push(propertyAccessor.getMovieProperty(scriptResolver.getName(arg), movieState));
            case GET -> scope.push(getGlobal(scriptResolver.getName(arg)));
            case SET -> setGlobal(scriptResolver.getName(arg), scope.pop());
            case PUT -> System.out.println(scope.pop().stringValue());

            // Object creation
            case NEW_OBJ -> { List<Datum> args2 = extractArgList(scope.pop()); scope.push(new Datum.ScriptInstanceRef(scriptResolver.getName(arg), new HashMap<>())); }
            case PUSH_VAR_REF, PUSH_CHUNK_VAR_REF -> scope.push(new Datum.VarRef(scriptResolver.getName(arg)));

            // String chunks
            case GET_CHUNK -> { Datum e = scope.pop(), s = scope.pop(), t = scope.pop(), str = scope.pop(); scope.push(getStringChunk(str.stringValue(), t, s.intValue(), e.intValue())); }
            case PUT_CHUNK, DELETE_CHUNK, HILITE_CHUNK -> { for (int i = 0; i < 3; i++) scope.pop(); }

            // Tell blocks
            case START_TELL -> scope.pushTellTarget(scope.pop());
            case END_TELL -> scope.popTellTarget();
            case TELL_CALL -> { String name = scriptResolver.getName(arg); List<Datum> args2 = extractArgList(scope.pop()); Datum t = scope.getTellTarget(); scope.push(t != null ? methodDispatcher.callMethod(t, name, args2, xtraInstanceCallback) : call(name, args2)); }

            // Sprite tests
            case ONTO_SPR, INTO_SPR -> { scope.pop(); scope.pop(); scope.push(Datum.FALSE); }

            case CALL_JAVASCRIPT -> scope.push(Datum.voidValue());

            default -> System.err.println("Unimplemented opcode: " + op + " (0x" + Integer.toHexString(op.getCode()) + ")");
        }
    }

    // === Handler Call Implementations ===

    private void executeExtCall(Scope scope, String handlerName) {
        Datum argListDatum = scope.pop();
        boolean isNoRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = extractArgList(argListDatum);

        // Match dirplayer-rs: exact string match for "return" handler
        if ("return".equals(handlerName)) {
            Datum returnValue = args.isEmpty() ? Datum.voidValue() : args.getFirst();
            scope.setReturnValue(returnValue);
            scope.setBytecodeIndex(scope.getHandler().instructions().size());
            if (!isNoRet) scope.push(returnValue);
            return;
        }

        Datum result = callGlobalHandler(handlerName, args, scope);
        lastHandlerResult = result;
        scope.setReturnValue(result);
        if (!isNoRet) scope.push(result);
    }

    /**
     * Execute a local call (handler in the current script).
     * Matches dirplayer-rs: local_call() in flow_control.rs
     */
    private void executeLocalCall(Scope currentScope, int handlerIndex) {
        Datum argListDatum = currentScope.pop();
        boolean isNoRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = new ArrayList<>(extractArgList(argListDatum));

        ScriptChunk script = currentScope.getScript();
        if (handlerIndex >= 0 && handlerIndex < script.handlers().size()) {
            ScriptChunk.Handler targetHandler = script.handlers().get(handlerIndex);
            String handlerName = scriptResolver.getName(targetHandler.nameId());

            // Match dirplayer-rs: Check first arg for script/instance with handler (polymorphism)
            ScriptChunk resolvedScript = script;
            ScriptChunk.Handler resolvedHandler = targetHandler;
            Datum.ScriptInstanceRef receiver = null;

            if (handlerName != null && !args.isEmpty()) {
                HandlerWithReceiver hwResult = getHandlerFromFirstArgWithReceiver(args, handlerName);
                if (hwResult != null) {
                    receiver = hwResult.receiver;
                    resolvedScript = hwResult.script;
                    resolvedHandler = hwResult.handler;
                } else {
                    receiver = currentScope.getReceiver();
                }
            } else {
                receiver = currentScope.getReceiver();
            }

            Datum result = callHandler(receiver, resolvedScript, resolvedHandler, args);
            if (!isNoRet) currentScope.push(result);
        } else if (!isNoRet) {
            currentScope.push(Datum.voidValue());
        }
    }

    private record HandlerWithReceiver(
        ScriptChunk script,
        ScriptChunk.Handler handler,
        Datum.ScriptInstanceRef receiver
    ) {}

    private void executeObjCall(Scope scope, String methodName) {
        Datum argListDatum = scope.pop();
        boolean isNoRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = extractArgList(argListDatum);
        Datum obj = args.isEmpty() ? Datum.voidValue() : args.remove(0);
        Datum result = methodDispatcher.callMethod(obj, methodName, args, xtraInstanceCallback);
        lastHandlerResult = result;
        if (!isNoRet) scope.push(result);
    }

    private void executeGetField(Scope scope) {
        int dirVersion = file != null && file.getConfig() != null ? file.getConfig().directorVersion() : 0;
        Datum castId = dirVersion >= 500 ? scope.pop() : Datum.of(0);
        if (castId.intValue() <= 0) {
            CastLib scriptCast = scriptResolver.findCastForScript(scope.getScript());
            castId = scriptCast != null ? Datum.of(scriptCast.getNumber()) : Datum.of(1);
        }
        scope.push(Datum.of(propertyAccessor.getFieldText(scope.pop(), castId, scriptResolver)));
    }

    /**
     * Call a global handler following dirplayer-rs resolution order:
     * 1. Check first arg for script/instance with handler (polymorphism)
     * 2. Search active script instances
     * 3. Search active static scripts (movie scripts + frame script)
     * 4. Check built-in handlers (LAST - fallback)
     */
    private Datum callGlobalHandler(String handlerName, List<Datum> args, Scope scope) {
        // "new" invocations should always go through the built-in handler
        if (!handlerName.equalsIgnoreCase("new")) {

            // 1. Check first arg for script/instance with handler (polymorphism)
            if (!args.isEmpty()) {
                HandlerWithReceiver hwResult = getHandlerFromFirstArgWithReceiver(args, handlerName);
                if (hwResult != null) {
                    return callHandler(hwResult.receiver, hwResult.script, hwResult.handler, args);
                }
            }

            // 2. Search active script instances (from score)
            // Matches dirplayer-rs: player.movie.score.get_active_script_instance_list()
            List<Datum.ScriptInstanceRef> activeInstances = getActiveScriptInstances();
            for (Datum.ScriptInstanceRef instanceRef : activeInstances) {
                ScriptChunk script = scriptResolver.findScriptByCastRef(instanceRef.scriptRef());
                if (script != null) {
                    for (ScriptChunk.Handler h : script.handlers()) {
                        if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                            return callHandler(instanceRef, script, h, args);
                        }
                    }
                }
            }

            // 3. Search active static scripts (movie scripts + frame script)
            ScriptResolver.HandlerLocation staticLoc = scriptResolver.findHandler(handlerName);
            if (staticLoc != null) {
                return callHandler(null, staticLoc.script(), staticLoc.handler(), args);
            }
        }

        // 4. Check built-in handlers (LAST - fallback)
        BuiltinHandler builtin = builtins.get(handlerName.toLowerCase());
        if (builtin != null) {
            return builtin.call(this, args);
        }

        throw LingoException.undefinedHandler(handlerName);
    }

    // === Handler Resolution ===

    private HandlerWithReceiver getHandlerFromFirstArgWithReceiver(List<Datum> args, String handlerName) {
        if (args.isEmpty()) return null;
        Datum firstArg = args.getFirst();

        if (firstArg instanceof Datum.ScriptRef sr) {
            ScriptChunk script = scriptResolver.findScriptByCastRef(sr.memberRef());
            if (script != null) {
                for (ScriptChunk.Handler h : script.handlers()) {
                    if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                        return new HandlerWithReceiver(script, h, null);
                    }
                }
            }
            return null;
        }

        if (firstArg instanceof Datum.ScriptInstanceRef si) {
            HandlerWithReceiver result = getScriptInstanceHandler(handlerName, si);
            if (result != null) {
                return new HandlerWithReceiver(result.script, result.handler, si);
            }
            return null;
        }

        return null;
    }

    private HandlerWithReceiver getScriptInstanceHandler(String handlerName, Datum.ScriptInstanceRef instanceRef) {
        ScriptChunk script = scriptResolver.findScriptByCastRef(instanceRef.scriptRef());
        if (script == null) return null;

        for (ScriptChunk.Handler h : script.handlers()) {
            if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                return new HandlerWithReceiver(script, h, instanceRef);
            }
        }

        // Check ancestor chain
        Datum.ScriptInstanceRef ancestorRef = instanceRef.ancestor();
        if (ancestorRef != null) {
            return getScriptInstanceHandler(handlerName, ancestorRef);
        }

        return null;
    }

    // === Script Instance Creation ===

    public Datum createScriptInstance(Datum.ScriptRef scriptRef, List<Datum> constructorArgs) {
        ScriptChunk script = scriptResolver.findScriptByCastRef(scriptRef.memberRef());
        if (script == null) throw new LingoException("Cannot find script for " + scriptRef.memberRef());

        String scriptName = null;
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
                Datum result = callHandler(instance, script, h, allArgs);
                return result instanceof Datum.Void ? instance : result;
            }
        }

        return instance;
    }

    public Datum callHandler(List<Datum> args) {
        if (args.size() < 2 || !(args.get(0) instanceof Datum.Symbol symbol)) return Datum.voidValue();

        String handlerName = symbol.name();
        Datum receiver = args.get(1);
        List<Datum> handlerArgs = args.size() > 2 ? args.subList(2, args.size()) : new ArrayList<>();

        List<Datum.ScriptInstanceRef> instances = new ArrayList<>();
        methodDispatcher.collectScriptInstances(receiver, instances, getActiveScriptInstances());

        if (instances.isEmpty()) return methodDispatcher.callMethod(receiver, handlerName, handlerArgs, xtraInstanceCallback);

        Datum result = Datum.voidValue();
        for (Datum.ScriptInstanceRef instance : instances) {
            ScriptChunk script = scriptResolver.findScriptByCastRef(instance.scriptRef());
            if (script != null) {
                for (ScriptChunk.Handler h : script.handlers()) {
                    if (handlerName.equalsIgnoreCase(scriptResolver.getName(h.nameId()))) {
                        List<Datum> allArgs = new ArrayList<>();
                        allArgs.add(instance);
                        allArgs.addAll(handlerArgs);
                        result = callHandler(instance, script, h, allArgs);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Get active script instances from the score.
     * Matches dirplayer-rs: player.movie.score.get_active_script_instance_list()
     * Currently returns empty list - sprite behavior instances not yet implemented.
     */
    private List<Datum.ScriptInstanceRef> getActiveScriptInstances() {
        // TODO: When sprite behavior script instances are implemented,
        // query them from score.getActiveScriptInstances()
        return List.of();
    }

    // === Arithmetic Operations ===

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

    // === Helper Methods ===

    private Datum getLiteral(ScriptChunk script, int index) {
        if (index >= script.literals().size()) return Datum.voidValue();
        Object val = script.literals().get(index).value();
        if (val instanceof String s) return Datum.of(s);
        if (val instanceof Integer i) return Datum.of(i);
        if (val instanceof Double d) return Datum.of(d.floatValue());
        return Datum.voidValue();
    }

    private List<Datum> extractArgList(Datum argList) {
        if (argList instanceof Datum.ArgList al) return new ArrayList<>(al.args());
        if (argList instanceof Datum.ArgListNoRet al) return new ArrayList<>(al.args());
        return new ArrayList<>();
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
