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
    private int instructionPointer;
    private Datum returnValue;

    public Scope(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args) {
        this.script = script;
        this.handler = handler;
        this.args = args;
        this.locals = new Datum[handler.localCount()];
        this.properties = new HashMap<>();
        this.tellTargets = new ArrayDeque<>();
        this.instructionPointer = 0;
        this.returnValue = Datum.voidValue();

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

    // Argument access (1-based indexing)

    public Datum getArg(int index) {
        if (index >= 1 && index <= args.length) {
            return args[index - 1];
        }
        return Datum.voidValue();
    }

    public void setArg(int index, Datum value) {
        if (index >= 1 && index <= args.length) {
            args[index - 1] = value;
        }
    }

    public int getArgCount() {
        return args.length;
    }

    // Local variable access (1-based indexing)

    public Datum getLocal(int index) {
        if (index >= 1 && index <= locals.length) {
            return locals[index - 1];
        }
        return Datum.voidValue();
    }

    public void setLocal(int index, Datum value) {
        if (index >= 1 && index <= locals.length) {
            locals[index - 1] = value;
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
}
