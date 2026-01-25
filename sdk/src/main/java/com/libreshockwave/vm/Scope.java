package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Datum;

import java.util.*;

/**
 * Execution scope for a handler call.
 * Matches dirplayer-rs Scope structure.
 *
 * Contains:
 * - Per-scope evaluation stack
 * - Local variables and arguments
 * - Execution state (bytecode index, return value)
 * - Receiver for instance method calls
 *
 * Designed for pool-based reuse via reset() method.
 */
public class Scope {

    // Pool index for this scope (matches dirplayer-rs scope_ref)
    private final int scopeRef;

    // Script reference (cast member ref of the script being executed)
    private Datum.CastMemberRef scriptRef;

    // Script chunk and handler being executed
    private ScriptChunk script;
    private ScriptChunk.Handler handler;

    // Receiver for script instance method calls (matches dirplayer-rs scope.receiver)
    private Datum.ScriptInstanceRef receiver;

    // Handler name ID for debugging/lookup
    private int handlerNameId;

    // Arguments passed to this handler
    private final List<Datum> args;

    // Bytecode index (instruction pointer)
    private int bytecodeIndex;

    // Local variables (by index for bytecode execution)
    private Datum[] locals;

    // Loop tracking for repeat/next repeat
    private final Deque<Integer> loopReturnIndices;

    // Return value from this handler
    private Datum returnValue;

    // Per-scope evaluation stack (matches dirplayer-rs scope.stack)
    private final Deque<Datum> stack;

    // Passed flag for exit handler propagation (matches dirplayer-rs scope.passed)
    private boolean passed;

    // Tell block targets
    private final Deque<Datum> tellTargets;

    // Properties (for parent script access)
    private final Map<String, Datum> properties;

    /**
     * Create a new scope with the given pool index.
     * Use reset() to initialize for execution.
     */
    public Scope(int scopeRef) {
        this.scopeRef = scopeRef;
        this.args = new ArrayList<>();
        this.loopReturnIndices = new ArrayDeque<>();
        this.stack = new ArrayDeque<>();
        this.tellTargets = new ArrayDeque<>();
        this.properties = new HashMap<>();
        this.locals = new Datum[0];
        reset();
    }

    /**
     * Reset this scope for reuse (matches dirplayer-rs Scope::reset).
     * Clears all execution state while keeping the pool index.
     */
    public void reset() {
        this.scriptRef = null;
        this.script = null;
        this.handler = null;
        this.receiver = null;
        this.handlerNameId = 0;
        this.args.clear();
        this.bytecodeIndex = 0;
        this.locals = new Datum[0];
        this.loopReturnIndices.clear();
        this.returnValue = Datum.voidValue();
        this.stack.clear();
        this.passed = false;
        this.tellTargets.clear();
        this.properties.clear();
    }

    /**
     * Initialize this scope for executing a handler.
     */
    public void initialize(ScriptChunk script, ScriptChunk.Handler handler, Datum.CastMemberRef scriptRef) {
        this.script = script;
        this.handler = handler;
        this.scriptRef = scriptRef;
        this.handlerNameId = handler.nameId();
        this.bytecodeIndex = 0;
        this.returnValue = Datum.voidValue();
        this.passed = false;

        // Allocate locals array based on handler's local count
        int localCount = handler.localCount();
        if (this.locals.length != localCount) {
            this.locals = new Datum[localCount];
        }
        // Initialize all locals to VOID
        for (int i = 0; i < localCount; i++) {
            this.locals[i] = Datum.voidValue();
        }
    }

    // === Pool reference ===

    public int getScopeRef() {
        return scopeRef;
    }

    // === Script/Handler access ===

    public Datum.CastMemberRef getScriptRef() {
        return scriptRef;
    }

    public ScriptChunk getScript() {
        return script;
    }

    public ScriptChunk.Handler getHandler() {
        return handler;
    }

    public int getHandlerNameId() {
        return handlerNameId;
    }

    // === Bytecode execution ===

    public int getBytecodeIndex() {
        return bytecodeIndex;
    }

    public void setBytecodeIndex(int index) {
        this.bytecodeIndex = index;
    }

    // Alias for compatibility
    public int getInstructionPointer() {
        return bytecodeIndex;
    }

    public void setInstructionPointer(int ip) {
        this.bytecodeIndex = ip;
    }

    public void advanceIP() {
        bytecodeIndex++;
    }

    public ScriptChunk.Handler.Instruction getCurrentInstruction() {
        if (handler == null) return null;
        if (bytecodeIndex >= 0 && bytecodeIndex < handler.instructions().size()) {
            return handler.instructions().get(bytecodeIndex);
        }
        return null;
    }

    public boolean isAtEnd() {
        return handler == null || bytecodeIndex >= handler.instructions().size();
    }

    // === Stack operations (matches dirplayer-rs scope.stack) ===

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

    /**
     * Pop n items from the stack and return them in order (first popped = last in list).
     * Matches dirplayer-rs Scope::pop_n behavior.
     */
    public List<Datum> popN(int n) {
        List<Datum> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(0, pop());
        }
        return result;
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

    // === Argument access ===

    public void addArg(Datum arg) {
        args.add(arg != null ? arg : Datum.voidValue());
    }

    public void setArgs(List<Datum> newArgs) {
        args.clear();
        if (newArgs != null) {
            args.addAll(newArgs);
        }
    }

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

    // === Local variable access (by index for bytecode) ===

    public Datum getLocal(int index) {
        if (index >= 0 && index < locals.length) {
            return locals[index];
        }
        return Datum.voidValue();
    }

    public void setLocal(int index, Datum value) {
        if (index >= 0 && index < locals.length) {
            locals[index] = value != null ? value : Datum.voidValue();
        }
    }

    public int getLocalCount() {
        return locals.length;
    }

    // === Property access (for scope properties) ===

    public Datum getProperty(String name) {
        return properties.getOrDefault(name, Datum.voidValue());
    }

    public void setProperty(String name, Datum value) {
        properties.put(name, value);
    }

    // === Return value ===

    public Datum getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Datum value) {
        this.returnValue = value != null ? value : Datum.voidValue();
    }

    // === Receiver (for script instance method calls) ===

    public Datum.ScriptInstanceRef getReceiver() {
        return receiver;
    }

    public void setReceiver(Datum.ScriptInstanceRef receiver) {
        this.receiver = receiver;
    }

    // === Passed flag ===

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    // === Tell target management ===

    public void pushTellTarget(Datum target) {
        tellTargets.push(target);
    }

    public Datum popTellTarget() {
        if (tellTargets.isEmpty()) {
            return null;
        }
        return tellTargets.pop();
    }

    public Datum getTellTarget() {
        if (tellTargets.isEmpty()) {
            return null;
        }
        return tellTargets.peek();
    }

    public boolean hasTellTarget() {
        return !tellTargets.isEmpty();
    }

    // === Loop tracking ===

    public void pushLoopIndex(int index) {
        loopReturnIndices.push(index);
    }

    public int popLoopIndex() {
        if (loopReturnIndices.isEmpty()) {
            return -1;
        }
        return loopReturnIndices.pop();
    }

    public int peekLoopIndex() {
        if (loopReturnIndices.isEmpty()) {
            return -1;
        }
        return loopReturnIndices.peek();
    }

    public boolean inLoop() {
        return !loopReturnIndices.isEmpty();
    }
}
