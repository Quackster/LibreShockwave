package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoException;
import com.libreshockwave.vm.Scope;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.builtin.BuiltinRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Execution context passed to opcode handlers.
 * Provides access to the current scope and VM utilities needed during instruction execution.
 */
public final class ExecutionContext {

    private final Scope scope;
    private final ScriptChunk.Handler.Instruction instruction;
    private final int argument;
    private final BuiltinRegistry builtins;
    private final TraceListener traceListener;

    // Callbacks to LingoVM
    private final HandlerExecutor handlerExecutor;
    private final HandlerFinder handlerFinder;
    private final GlobalAccessor globalAccessor;
    private final BuiltinInvoker builtinInvoker;

    public ExecutionContext(
            Scope scope,
            ScriptChunk.Handler.Instruction instruction,
            BuiltinRegistry builtins,
            TraceListener traceListener,
            HandlerExecutor handlerExecutor,
            HandlerFinder handlerFinder,
            GlobalAccessor globalAccessor,
            BuiltinInvoker builtinInvoker) {
        this.scope = scope;
        this.instruction = instruction;
        this.argument = instruction.argument();
        this.builtins = builtins;
        this.traceListener = traceListener;
        this.handlerExecutor = handlerExecutor;
        this.handlerFinder = handlerFinder;
        this.globalAccessor = globalAccessor;
        this.builtinInvoker = builtinInvoker;
    }

    // Scope access

    public Scope getScope() {
        return scope;
    }

    public int getArgument() {
        return argument;
    }

    public ScriptChunk.Handler.Instruction getInstruction() {
        return instruction;
    }

    // Stack operations (delegated to scope)

    public void push(Datum value) {
        scope.push(value);
    }

    public Datum pop() {
        return scope.pop();
    }

    public Datum peek() {
        return scope.peek();
    }

    public Datum peek(int depth) {
        return scope.peek(depth);
    }

    public void swap() {
        scope.swap();
    }

    // Local/param access

    public Datum getLocal(int index) {
        return scope.getLocal(index);
    }

    public void setLocal(int index, Datum value) {
        scope.setLocal(index, value);
        if (traceListener != null) {
            traceListener.onVariableSet("local", "local" + index, value);
        }
    }

    public Datum getParam(int index) {
        return scope.getParam(index);
    }

    // Global access

    public Datum getGlobal(String name) {
        return globalAccessor.getGlobal(name);
    }

    public void setGlobal(String name, Datum value) {
        globalAccessor.setGlobal(name, value);
        if (traceListener != null) {
            traceListener.onVariableSet("global", name, value);
        }
    }

    // Return handling

    public void setReturnValue(Datum value) {
        scope.setReturnValue(value);
    }

    public void setReturned(boolean returned) {
        scope.setReturned(returned);
    }

    // Name resolution - delegates to ScriptChunk which uses the correct cast lib's names

    public String resolveName(int nameId) {
        return getScript().resolveName(nameId);
    }

    // Script/handler access

    public ScriptChunk getScript() {
        return scope.getScript();
    }

    public ScriptChunk.Handler getHandler() {
        return scope.getHandler();
    }

    public Datum getReceiver() {
        return scope.getReceiver();
    }

    public List<ScriptChunk.LiteralEntry> getLiterals() {
        return scope.getScript().literals();
    }

    // Jump handling

    public void jumpTo(int targetOffset) {
        int targetIndex = scope.getHandler().getInstructionIndex(targetOffset);
        if (targetIndex >= 0) {
            scope.setBytecodeIndex(targetIndex);
        }
    }

    public int getInstructionOffset() {
        return instruction.offset();
    }

    // Function calls

    public ScriptChunk.Handler findLocalHandler(int vectorPos) {
        for (ScriptChunk.Handler handler : getScript().handlers()) {
            if (handler.handlerVectorPos() == vectorPos) {
                return handler;
            }
        }
        return null;
    }

    public HandlerRef findHandler(String handlerName) {
        return handlerFinder.findHandler(handlerName);
    }

    public Datum executeHandler(ScriptChunk script, ScriptChunk.Handler handler,
                                List<Datum> args, Datum receiver) {
        return handlerExecutor.executeHandler(script, handler, args, receiver);
    }

    public boolean isBuiltin(String name) {
        return builtins.contains(name);
    }

    public Datum invokeBuiltin(String name, List<Datum> args) {
        return builtinInvoker.invoke(name, args);
    }

    public BuiltinRegistry getBuiltins() {
        return builtins;
    }

    // Helper to pop multiple args in reverse order

    public List<Datum> popArgs(int count) {
        List<Datum> args = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            args.add(0, pop());
        }
        return args;
    }

    // Property tracing

    public void tracePropertySet(String propName, Datum value) {
        if (traceListener != null) {
            traceListener.onVariableSet("property", "me." + propName, value);
        }
    }

    // Exception helper

    public LingoException error(String message) {
        return new LingoException(message);
    }

    // Functional interfaces for callbacks

    @FunctionalInterface
    public interface HandlerExecutor {
        Datum executeHandler(ScriptChunk script, ScriptChunk.Handler handler,
                             List<Datum> args, Datum receiver);
    }

    @FunctionalInterface
    public interface HandlerFinder {
        HandlerRef findHandler(String name);
    }

    public interface GlobalAccessor {
        Datum getGlobal(String name);
        void setGlobal(String name, Datum value);
    }

    @FunctionalInterface
    public interface BuiltinInvoker {
        Datum invoke(String name, List<Datum> args);
    }
}
