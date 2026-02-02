package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution scope for a handler call.
 * Represents a single stack frame in the call stack.
 * Similar to dirplayer-rs scope.rs.
 */
public final class Scope {

    private final ScriptChunk script;
    private final ScriptChunk.Handler handler;
    private final List<Datum> originalArguments;
    private final Map<Integer, Datum> modifiedParams;  // For SET_PARAM modifications
    private final Map<Integer, Datum> locals;
    private final Deque<Datum> stack;
    private final Datum receiver;  // 'me' in behavior/parent scripts

    private int bytecodeIndex;
    private Datum returnValue;
    private boolean returned;

    // Loop tracking for repeat/exit repeat
    private final Deque<Integer> loopReturnIndices;

    public Scope(ScriptChunk script, ScriptChunk.Handler handler, List<Datum> arguments, Datum receiver) {
        this.script = script;
        this.handler = handler;
        this.originalArguments = List.copyOf(arguments);
        this.modifiedParams = new HashMap<>();
        this.locals = new HashMap<>();
        this.stack = new ArrayDeque<>();
        this.receiver = receiver != null ? receiver : Datum.VOID;
        this.bytecodeIndex = 0;
        this.returnValue = Datum.VOID;
        this.returned = false;
        this.loopReturnIndices = new ArrayDeque<>();

        // Initialize locals to void
        for (int i = 0; i < handler.localCount(); i++) {
            locals.put(i, Datum.VOID);
        }
    }

    // Script and handler access

    public ScriptChunk getScript() {
        return script;
    }

    public ScriptChunk.Handler getHandler() {
        return handler;
    }

    public List<Datum> getArguments() {
        return originalArguments;
    }

    public Datum getReceiver() {
        return receiver;
    }

    // Bytecode position

    public int getBytecodeIndex() {
        return bytecodeIndex;
    }

    public void setBytecodeIndex(int index) {
        this.bytecodeIndex = index;
    }

    public void advanceBytecodeIndex() {
        this.bytecodeIndex++;
    }

    public boolean hasMoreInstructions() {
        return bytecodeIndex < handler.instructions().size();
    }

    public ScriptChunk.Handler.Instruction getCurrentInstruction() {
        if (bytecodeIndex >= 0 && bytecodeIndex < handler.instructions().size()) {
            return handler.instructions().get(bytecodeIndex);
        }
        return null;
    }

    // Stack operations

    public void push(Datum value) {
        stack.push(value);
    }

    public Datum pop() {
        return stack.isEmpty() ? Datum.VOID : stack.pop();
    }

    public Datum peek() {
        return stack.isEmpty() ? Datum.VOID : stack.peek();
    }

    public Datum peek(int depth) {
        if (depth < 0 || depth >= stack.size()) {
            return Datum.VOID;
        }
        int i = 0;
        for (Datum d : stack) {
            if (i == depth) return d;
            i++;
        }
        return Datum.VOID;
    }

    public int stackSize() {
        return stack.size();
    }

    public void swap() {
        if (stack.size() >= 2) {
            Datum a = stack.pop();
            Datum b = stack.pop();
            stack.push(a);
            stack.push(b);
        }
    }

    // Parameter access
    // In Lingo bytecode, parameters are 0-indexed:
    //   param0 = first argument in args list
    //   param1 = second argument in args list, etc.
    // For parent script methods, the receiver ('me') is included as args[0].
    // For movie script handlers, there's no receiver, so args[0] is the first explicit argument.

    public Datum getParam(int index) {
        // Check if param was modified via SET_PARAM
        if (modifiedParams.containsKey(index)) {
            return modifiedParams.get(index);
        }
        // Otherwise return original argument
        if (index >= 0 && index < originalArguments.size()) {
            return originalArguments.get(index);
        }
        return Datum.VOID;
    }

    public void setParam(int index, Datum value) {
        modifiedParams.put(index, value);
    }

    // Local variable access

    public Datum getLocal(int index) {
        return locals.getOrDefault(index, Datum.VOID);
    }

    public void setLocal(int index, Datum value) {
        locals.put(index, value);
    }

    // Return handling

    public boolean isReturned() {
        return returned;
    }

    public void setReturned(boolean returned) {
        this.returned = returned;
    }

    public Datum getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Datum value) {
        this.returnValue = value;
        this.returned = true;
    }

    // Loop handling

    public void pushLoopReturnIndex(int index) {
        loopReturnIndices.push(index);
    }

    public int popLoopReturnIndex() {
        return loopReturnIndices.isEmpty() ? -1 : loopReturnIndices.pop();
    }

    public boolean isInLoop() {
        return !loopReturnIndices.isEmpty();
    }

    @Override
    public String toString() {
        String handlerName = "handler#" + handler.nameId();
        return "Scope{" + handlerName + ", bytecodeIndex=" + bytecodeIndex +
               ", stackSize=" + stack.size() + ", returned=" + returned + "}";
    }
}
