package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;

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
    private final List<Datum> arguments;
    private final Map<Integer, Datum> locals;
    private final Deque<Datum> stack;
    private final Datum receiver;  // 'me' in behavior/parent scripts
    private final ScriptNamesChunk scriptNames;  // For external cast scripts

    private int bytecodeIndex;
    private Datum returnValue;
    private boolean returned;

    // Loop tracking for repeat/exit repeat
    private final Deque<Integer> loopReturnIndices;

    public Scope(ScriptChunk script, ScriptChunk.Handler handler, List<Datum> arguments, Datum receiver) {
        this(script, handler, arguments, receiver, null);
    }

    public Scope(ScriptChunk script, ScriptChunk.Handler handler, List<Datum> arguments,
                 Datum receiver, ScriptNamesChunk scriptNames) {
        this.script = script;
        this.handler = handler;
        this.arguments = List.copyOf(arguments);
        this.locals = new HashMap<>();
        this.stack = new ArrayDeque<>();
        this.receiver = receiver != null ? receiver : Datum.VOID;
        this.scriptNames = scriptNames;
        this.bytecodeIndex = 0;
        this.returnValue = Datum.VOID;
        this.returned = false;
        this.loopReturnIndices = new ArrayDeque<>();

        // Initialize locals to void
        for (int i = 0; i < handler.localCount(); i++) {
            locals.put(i, Datum.VOID);
        }
    }

    /**
     * Get the ScriptNamesChunk for this scope (if from external cast).
     */
    public ScriptNamesChunk getScriptNames() {
        return scriptNames;
    }

    // Script and handler access

    public ScriptChunk getScript() {
        return script;
    }

    public ScriptChunk.Handler getHandler() {
        return handler;
    }

    public List<Datum> getArguments() {
        return arguments;
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

    public Datum getParam(int index) {
        if (index >= 0 && index < arguments.size()) {
            return arguments.get(index);
        }
        return Datum.VOID;
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
