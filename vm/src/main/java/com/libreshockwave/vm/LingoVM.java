package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.opcode.ExecutionContext;
import com.libreshockwave.vm.opcode.OpcodeHandler;
import com.libreshockwave.vm.opcode.OpcodeRegistry;
import com.libreshockwave.vm.trace.ConsoleTracePrinter;
import com.libreshockwave.vm.trace.TracingHelper;

import java.util.*;

/**
 * Lingo Virtual Machine.
 * Executes bytecode from ScriptChunk handlers.
 * Similar to dirplayer-rs handler_manager.rs.
 */
public class LingoVM {
    // Match dirplayer-rs MAX_STACK_SIZE
    private static final int MAX_CALL_STACK_DEPTH = 50;
    // Default step limit per handler - high enough for complex scripts like dump()
    // that iterate character-by-character through large property files
    private static final int DEFAULT_STEP_LIMIT = 1_000_000;

    private final DirectorFile file;
    private final Map<String, Datum> globals;
    private final Deque<Scope> callStack;
    private final BuiltinRegistry builtins;
    private final OpcodeRegistry opcodeRegistry;
    private final TracingHelper tracingHelper;
    private final ConsoleTracePrinter consolePrinter;

    private boolean traceEnabled = false;
    private int stepLimit = DEFAULT_STEP_LIMIT;

    // Event propagation callback (set by EventDispatcher)
    private Runnable passCallback;

    // Trace listener for debug UI
    private TraceListener traceListener;

    // Error state - when true, no more handlers will execute (like dirplayer-rs stop())
    private boolean inErrorState = false;

    // Track if we're currently inside an error handler to prevent recursive error handling
    private int errorHandlerDepth = 0;
    private static final Set<String> ERROR_HANDLER_NAMES = Set.of(
        "error", "executemessage", "executeMessage", "alerthook"
    );

    public LingoVM(DirectorFile file) {
        this.file = file;
        this.globals = new HashMap<>();
        this.callStack = new ArrayDeque<>();
        this.builtins = new BuiltinRegistry();
        this.opcodeRegistry = new OpcodeRegistry();
        this.tracingHelper = new TracingHelper();
        this.consolePrinter = new ConsoleTracePrinter();
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
        this.opcodeRegistry.setTraceEnabled(enabled);
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
     * Searches the main file first, then external cast libraries.
     * @param handlerName The handler name to find
     * @return The script and handler, or null if not found
     */
    public HandlerRef findHandler(String handlerName) {
        // First search the main file
        if (file != null) {
            for (ScriptChunk script : file.getScripts()) {
                ScriptChunk.Handler handler = script.findHandler(handlerName);
                if (handler != null) {
                    return new HandlerRef(script, handler);
                }
            }
        }

        // Then search external cast libraries via CastLibProvider
        var provider = com.libreshockwave.vm.builtin.CastLibProvider.getProvider();
        if (provider != null) {
            var location = provider.findHandler(handlerName);
            if (location != null && location.script() instanceof ScriptChunk script
                    && location.handler() instanceof ScriptChunk.Handler handler) {
                return new HandlerRef(script, handler);
            }
        }

        return null;
    }

    /**
     * Find a handler in a specific script.
     */
    public HandlerRef findHandler(ScriptChunk script, String handlerName) {
        ScriptChunk.Handler handler = script.findHandler(handlerName);
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
        // Like dirplayer-rs: if we're in an error state, don't execute any more handlers
        if (inErrorState) {
            return Datum.VOID;
        }

        // Prevent recursive error handling - if we're already in an error handler
        // and trying to call another error handler, return VOID
        String handlerName = script.getHandlerName(handler);
        String hn = handlerName.toLowerCase();
        boolean isErrorHandler = ERROR_HANDLER_NAMES.contains(hn);
        if (isErrorHandler && errorHandlerDepth > 0) {
            // Already in an error handler, skip recursive call
            return Datum.VOID;
        }

        if (callStack.size() >= MAX_CALL_STACK_DEPTH) {
            throw new LingoException("Call stack overflow (max " + MAX_CALL_STACK_DEPTH + " frames)");
        }

        // If there's a receiver (for parent script methods), prepend it to args as param0
        // This matches dirplayer-rs behavior where the receiver is included in scope.args
        List<Datum> effectiveArgs = args;
        Datum scopeReceiver = receiver;
        if (receiver != null && !receiver.isVoid()) {
            effectiveArgs = new ArrayList<>();
            effectiveArgs.add(receiver);
            effectiveArgs.addAll(args);
        } else {
            // No explicit receiver — derive from first arg if it's a ScriptInstance.
            // This handles LOCAL_CALL where the Lingo code explicitly passes 'me'
            // (e.g., searchTask(me, arg)). args[0] IS 'me' and should be used
            // as the receiver for property access within the handler.
            if (!args.isEmpty() && args.get(0) instanceof Datum.ScriptInstance) {
                scopeReceiver = args.get(0);
            }
        }

        Scope scope = new Scope(script, handler, effectiveArgs, scopeReceiver);
        callStack.push(scope);

        // Track error handler depth
        if (isErrorHandler) {
            errorHandlerDepth++;
        }

        // Notify trace listener of handler entry
        TraceListener.HandlerInfo handlerInfo = null;
        if (traceListener != null || traceEnabled) {
            handlerInfo = tracingHelper.buildHandlerInfo(script, handler, args, receiver, globals);

            if (traceEnabled) {
                consolePrinter.onHandlerEnter(handlerInfo);
            }
            if (traceListener != null) {
                traceListener.onHandlerEnter(handlerInfo);
            }
        }

        Datum result = Datum.VOID;
        try {
            int steps = 0;
            while (scope.hasMoreInstructions() && !scope.isReturned()) {
                if (steps++ >= stepLimit) {
                    throw new LingoException("Step limit exceeded (" + stepLimit + " instructions)");
                }
                executeInstruction(scope);
            }

            result = scope.getReturnValue();
        } catch (Exception e) {
            if (traceListener != null) {
                traceListener.onError("Error in " + script.getHandlerName(handler), e);
            }
            // Try alertHook before rethrowing — if it returns true, suppress the error
            if (!isErrorHandler && fireAlertHook(e.getMessage())) {
                result = Datum.VOID; // Error suppressed by alertHook
            } else {
                throw e;
            }
        } finally {
            // Always notify handler exit, even on exception path.
            // Critical for debugger callDepth tracking - without this,
            // exceptions cause callDepth to drift and break step-over.
            if (traceListener != null && handlerInfo != null) {
                traceListener.onHandlerExit(handlerInfo, result);
            }
            if (traceEnabled && handlerInfo != null) {
                consolePrinter.onHandlerExit(handlerInfo, result);
            }
            callStack.pop();
            if (isErrorHandler) {
                errorHandlerDepth--;
            }
        }
        return result;
    }

    /**
     * Set the error state. When true, no more handlers will execute.
     * Like dirplayer-rs stop() behavior.
     */
    public void setErrorState(boolean errorState) {
        this.inErrorState = errorState;
    }

    /**
     * Check if VM is in error state.
     */
    public boolean isInErrorState() {
        return inErrorState;
    }

    /**
     * Reset the error state to allow execution to continue.
     * Call this at the start of each frame or event dispatch.
     */
    public void resetErrorState() {
        this.inErrorState = false;
    }

    /**
     * Fire the alertHook handler if one is set.
     * In Director, "the alertHook" is set to a script instance that has an "alertHook" handler.
     * When a script error occurs, Director calls that handler with the error message.
     * If the handler returns true, the error is suppressed.
     *
     * @param errorMsg The error message to pass to the handler
     * @return true if the error was handled (suppressed), false otherwise
     */
    public boolean fireAlertHook(String errorMsg) {
        if (errorHandlerDepth > 0) {
            return false; // Prevent recursion
        }

        var provider = com.libreshockwave.vm.builtin.MoviePropertyProvider.getProvider();
        if (provider == null) {
            return false;
        }

        Datum hookValue = provider.getMovieProp("alertHook");
        if (hookValue == null || hookValue.isVoid()) {
            return false;
        }

        if (!(hookValue instanceof Datum.ScriptInstance hookInstance)) {
            return false;
        }

        // Find the "alertHook" handler in the instance's script
        Datum.ScriptRef scriptRef = null;
        Datum scriptRefDatum = hookInstance.properties().get(Datum.PROP_SCRIPT_REF);
        if (scriptRefDatum instanceof Datum.ScriptRef sr) {
            scriptRef = sr;
        }

        var castProvider = com.libreshockwave.vm.builtin.CastLibProvider.getProvider();
        if (castProvider == null) {
            return false;
        }

        com.libreshockwave.vm.builtin.CastLibProvider.HandlerLocation location;
        if (scriptRef != null) {
            location = castProvider.findHandlerInScript(scriptRef.castLib(), scriptRef.member(), "alertHook");
        } else {
            location = castProvider.findHandlerInScript(hookInstance.scriptId(), "alertHook");
        }

        if (location == null || !(location.script() instanceof ScriptChunk script)
                || !(location.handler() instanceof ScriptChunk.Handler handler)) {
            return false;
        }

        try {
            List<Datum> alertArgs = List.of(Datum.of(errorMsg));
            Datum result = executeHandler(script, handler, alertArgs, hookInstance);
            return result != null && result.isTruthy();
        } catch (Exception e) {
            // alertHook itself failed — don't suppress original error
            System.err.println("[LingoVM] alertHook handler failed: " + e.getMessage());
            return false;
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
            TraceListener.InstructionInfo instrInfo = tracingHelper.buildInstructionInfo(scope, instr, globals);
            if (traceEnabled) {
                consolePrinter.onInstruction(instrInfo);
            }
            if (traceListener != null) {
                traceListener.onInstruction(instrInfo);
            }
        }

        Opcode op = instr.opcode();

        OpcodeHandler handler = opcodeRegistry.get(op);
        if (handler != null) {
            ExecutionContext ctx = createExecutionContext(scope, instr);
            boolean advance = handler.execute(ctx);
            if (advance) {
                scope.advanceBytecodeIndex();
            }
        } else {
            if (traceEnabled) {
                System.err.println("Unimplemented opcode: " + op);
            }
            scope.advanceBytecodeIndex();
        }
    }

    /**
     * Create an execution context for opcode handlers.
     */
    private ExecutionContext createExecutionContext(Scope scope, ScriptChunk.Handler.Instruction instr) {
        return new ExecutionContext(
            scope,
            instr,
            builtins,
            traceListener,
            this::executeHandler,
            this::findHandler,
            new ExecutionContext.GlobalAccessor() {
                @Override
                public Datum getGlobal(String name) {
                    return LingoVM.this.getGlobal(name);
                }
                @Override
                public void setGlobal(String name, Datum value) {
                    LingoVM.this.setGlobal(name, value);
                }
            },
            (name, args) -> builtins.invoke(name, LingoVM.this, args),
            this::setErrorState
        );
    }
}
