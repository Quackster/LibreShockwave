package com.libreshockwave.vm;

import com.libreshockwave.lingo.Datum;

import java.util.*;

/**
 * Execution scope for a handler call.
 * Contains the stack, local variables, arguments, and execution state.
 * Matches dirplayer-rs Scope structure.
 */
public class ExecutionScope {

    private final Deque<Datum> stack = new ArrayDeque<>();
    private final Map<String, Datum> locals = new HashMap<>();
    private final List<Datum> args = new ArrayList<>();
    private final Deque<Integer> loopReturnIndices = new ArrayDeque<>();

    private int bytecodeIndex = 0;
    private Datum returnValue = Datum.voidValue();
    private Datum receiver;  // 'me' reference for object methods
    private Object scriptRef;  // Reference to containing script

    public ExecutionScope() {
    }

    public ExecutionScope(List<Datum> args) {
        if (args != null) {
            this.args.addAll(args);
        }
    }

    // Stack operations

    public void push(Datum value) {
        stack.push(value != null ? value : Datum.voidValue());
    }

    public Datum pop() {
        if (stack.isEmpty()) {
            return Datum.voidValue();
        }
        return stack.pop();
    }

    public Datum peek() {
        if (stack.isEmpty()) {
            return Datum.voidValue();
        }
        return stack.peek();
    }

    public Datum peek(int offset) {
        if (offset >= stack.size()) {
            return Datum.voidValue();
        }
        // Convert stack to array to access by index
        Datum[] arr = stack.toArray(new Datum[0]);
        return arr[offset];
    }

    public void popN(int count) {
        for (int i = 0; i < count && !stack.isEmpty(); i++) {
            stack.pop();
        }
    }

    public int stackSize() {
        return stack.size();
    }

    public void clearStack() {
        stack.clear();
    }

    public void swap() {
        if (stack.size() >= 2) {
            Datum a = stack.pop();
            Datum b = stack.pop();
            stack.push(a);
            stack.push(b);
        }
    }

    // Local variable access

    public Datum getLocal(String name) {
        return locals.getOrDefault(name, Datum.voidValue());
    }

    public void setLocal(String name, Datum value) {
        locals.put(name, value != null ? value : Datum.voidValue());
    }

    public boolean hasLocal(String name) {
        return locals.containsKey(name);
    }

    public Map<String, Datum> getLocals() {
        return Collections.unmodifiableMap(locals);
    }

    // Argument access

    public Datum getArg(int index) {
        if (index >= 0 && index < args.size()) {
            return args.get(index);
        }
        return Datum.voidValue();
    }

    public void setArg(int index, Datum value) {
        while (args.size() <= index) {
            args.add(Datum.voidValue());
        }
        args.set(index, value != null ? value : Datum.voidValue());
    }

    public int getArgCount() {
        return args.size();
    }

    public List<Datum> getArgs() {
        return Collections.unmodifiableList(args);
    }

    // Loop tracking

    public void pushLoopReturn(int index) {
        loopReturnIndices.push(index);
    }

    public int popLoopReturn() {
        if (loopReturnIndices.isEmpty()) {
            return -1;
        }
        return loopReturnIndices.pop();
    }

    // Bytecode index

    public int getBytecodeIndex() {
        return bytecodeIndex;
    }

    public void setBytecodeIndex(int index) {
        this.bytecodeIndex = index;
    }

    public void advanceBytecodeIndex() {
        this.bytecodeIndex++;
    }

    // Return value

    public Datum getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Datum value) {
        this.returnValue = value != null ? value : Datum.voidValue();
    }

    // Receiver (me)

    public Datum getReceiver() {
        return receiver;
    }

    public void setReceiver(Datum receiver) {
        this.receiver = receiver;
    }

    // Script reference

    public Object getScriptRef() {
        return scriptRef;
    }

    public void setScriptRef(Object scriptRef) {
        this.scriptRef = scriptRef;
    }

    @Override
    public String toString() {
        return "ExecutionScope{" +
            "stackSize=" + stack.size() +
            ", locals=" + locals.size() +
            ", args=" + args.size() +
            ", bytecodeIndex=" + bytecodeIndex +
            '}';
    }
}
