package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.opcode.ExecutionContext;
import com.libreshockwave.vm.opcode.OpcodeHandler;
import com.libreshockwave.vm.opcode.OpcodeRegistry;
import com.libreshockwave.vm.trace.TracingHelper;

import java.util.*;

/**
 * Lingo Virtual Machine.
 * Executes bytecode from ScriptChunk handlers.
 * Similar to dirplayer-rs handler_manager.rs.
 */
public class LingoVM {
    private static final int MAX_CALL_STACK_DEPTH = 1000;

    private final DirectorFile file;
    private final Map<String, Datum> globals;
    private final Deque<Scope> callStack;
    private final BuiltinRegistry builtins;
    private final OpcodeRegistry opcodeRegistry;
    private final TracingHelper tracingHelper;

    private boolean traceEnabled = false;
    private int stepLimit = 100_000;  // Maximum instructions per handler call

    // Event propagation callback (set by EventDispatcher)
    private Runnable passCallback;

    // Trace listener for debug UI
    private TraceListener traceListener;

    // Error state - when true, no more handlers will execute (like dirplayer-rs stop())
    private boolean inErrorState = false;

    public LingoVM(DirectorFile file) {
        this.file = file;
        this.globals = new HashMap<>();
        this.callStack = new ArrayDeque<>();
        this.builtins = new BuiltinRegistry();
        this.opcodeRegistry = new OpcodeRegistry();
        this.tracingHelper = new TracingHelper();
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
        // Like dirplayer-rs: if we're in an error state, don't execute any more handlers
        if (inErrorState) {
            return Datum.VOID;
        }

        if (callStack.size() >= MAX_CALL_STACK_DEPTH) {
            throw new LingoException("Call stack overflow (max " + MAX_CALL_STACK_DEPTH + " frames)");
        }

        // If there's a receiver (for parent script methods), prepend it to args as param0
        // This matches dirplayer-rs behavior where the receiver is included in scope.args
        List<Datum> effectiveArgs = args;
        if (receiver != null && !receiver.isVoid()) {
            effectiveArgs = new java.util.ArrayList<>();
            effectiveArgs.add(receiver);
            effectiveArgs.addAll(args);
        }

        Scope scope = new Scope(script, handler, effectiveArgs, receiver);
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
                traceListener.onError("Error in " + script.getHandlerName(handler), e);
            }
            throw e;
        } finally {
            callStack.pop();
        }
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
