package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Datum;

import java.util.*;

/**
 * Execution scope for a handler call.
 * Contains local variables, arguments, and execution state.
 */
public class Scope {

    private final ScriptChunk script;
    private final ScriptChunk.Handler handler;
    private final Datum[] args;
    private final Datum[] locals;
    private final Map<String, Datum> properties;
    private final Deque<Datum> tellTargets;
    private final Deque<Integer> loopReturnIndices;  // Track loop start indices for next repeat
    private int instructionPointer;
    private Datum returnValue;

    // Receiver for script instance method calls (matches dirplayer-rs scope.receiver)
    private Datum.ScriptInstanceRef receiver;

    // Passed flag for exit handler propagation (matches dirplayer-rs scope.passed)
    private boolean passed;

    public Scope(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        this.script = script;
        this.handler = handler;
        this.args = args;
        this.locals = new Datum[handler.localCount()];
        this.properties = new HashMap<>();
        this.tellTargets = new ArrayDeque<>();
        this.loopReturnIndices = new ArrayDeque<>();
        this.instructionPointer = 0;
        this.returnValue = Datum.voidValue();
        this.receiver = null;
        this.passed = false;

        // Initialize locals to VOID
        for (int i = 0; i < locals.length; i++) {
            locals[i] = Datum.voidValue();
        }
    }

    public ScriptChunk getScript() {
        return script;
    }

    public ScriptChunk.Handler getHandler() {
        return handler;
    }

    public int getInstructionPointer() {
        return instructionPointer;
    }

    public void setInstructionPointer(int ip) {
        this.instructionPointer = ip;
    }

    public void advanceIP() {
        instructionPointer++;
    }

    public ScriptChunk.Handler.Instruction getCurrentInstruction() {
        if (instructionPointer >= 0 && instructionPointer < handler.instructions().size()) {
            return handler.instructions().get(instructionPointer);
        }
        return null;
    }

    public boolean isAtEnd() {
        return instructionPointer >= handler.instructions().size();
    }

    // Argument access (0-based indexing, matching dirplayer-rs)

    public Datum getArg(int index) {
        if (index >= 0 && index < args.length) {
            return args[index];
        }
        return Datum.voidValue();
    }

    public void setArg(int index, Datum value) {
        if (index >= 0 && index < args.length) {
            args[index] = value;
        }
    }

    public int getArgCount() {
        return args.length;
    }

    // Local variable access (0-based indexing, matching dirplayer-rs)

    public Datum getLocal(int index) {
        if (index >= 0 && index < locals.length) {
            return locals[index];
        }
        return Datum.voidValue();
    }

    public void setLocal(int index, Datum value) {
        if (index >= 0 && index < locals.length) {
            locals[index] = value;
        }
    }

    public int getLocalCount() {
        return locals.length;
    }

    // Property access

    public Datum getProperty(String name) {
        return properties.getOrDefault(name, Datum.voidValue());
    }

    public void setProperty(String name, Datum value) {
        properties.put(name, value);
    }

    // Return value

    public Datum getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Datum value) {
        this.returnValue = value;
    }

    // Tell target management

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

    // Loop tracking (for next repeat support)

    /**
     * Push the current instruction index when entering a loop.
     * Called when JMP_IF_Z is executed (loop start).
     */
    public void pushLoopIndex(int index) {
        loopReturnIndices.push(index);
    }

    /**
     * Pop the loop start index when exiting a loop.
     * Returns -1 if no loop is active.
     */
    public int popLoopIndex() {
        if (loopReturnIndices.isEmpty()) {
            return -1;
        }
        return loopReturnIndices.pop();
    }

    /**
     * Peek at the current loop start index without removing it.
     * Returns -1 if no loop is active.
     */
    public int peekLoopIndex() {
        if (loopReturnIndices.isEmpty()) {
            return -1;
        }
        return loopReturnIndices.peek();
    }

    /**
     * Check if currently inside a loop.
     */
    public boolean inLoop() {
        return !loopReturnIndices.isEmpty();
    }

    // Receiver access (for script instance method calls)

    public Datum.ScriptInstanceRef getReceiver() {
        return receiver;
    }

    public void setReceiver(Datum.ScriptInstanceRef receiver) {
        this.receiver = receiver;
    }

    // Passed flag (for exit handler propagation)

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }
}
