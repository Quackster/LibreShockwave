package com.libreshockwave.runtime;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.vm.LingoVM;

/**
 * Context for bytecode handler execution in the runtime.
 * Provides access to script, handler, and name resolution.
 * Used by DirPlayer to provide additional context during execution.
 */
public class BytecodeHandlerContext {

    private final ScriptChunk script;
    private final ScriptChunk.Handler handler;
    private final ScriptNamesChunk scriptNames;
    private final LingoVM vm;

    public BytecodeHandlerContext(
            ScriptChunk script,
            ScriptChunk.Handler handler,
            ScriptNamesChunk scriptNames,
            LingoVM vm) {
        this.script = script;
        this.handler = handler;
        this.scriptNames = scriptNames;
        this.vm = vm;
    }

    /**
     * Get the script being executed.
     */
    public ScriptChunk getScript() {
        return script;
    }

    /**
     * Get the handler being executed.
     */
    public ScriptChunk.Handler getHandler() {
        return handler;
    }

    /**
     * Get the script names for resolving name IDs.
     */
    public ScriptNamesChunk getScriptNames() {
        return scriptNames;
    }

    /**
     * Get the VM for execution.
     */
    public LingoVM getVM() {
        return vm;
    }

    /**
     * Get a name by ID from the script names chunk.
     */
    public String getName(int nameId) {
        if (scriptNames != null) {
            return scriptNames.getName(nameId);
        }
        return "<name:" + nameId + ">";
    }

    /**
     * Get a local variable name by index.
     */
    public String getLocalName(int index) {
        if (index >= 0 && index < handler.localNameIds().size()) {
            int nameId = handler.localNameIds().get(index);
            return getName(nameId);
        }
        return "local_" + index;
    }

    /**
     * Get an argument name by index.
     */
    public String getArgName(int index) {
        if (index >= 0 && index < handler.argNameIds().size()) {
            int nameId = handler.argNameIds().get(index);
            return getName(nameId);
        }
        return "arg_" + index;
    }

    /**
     * Get a literal value from the script's literal table.
     */
    public Object getLiteral(int index) {
        if (index >= 0 && index < script.literals().size()) {
            return script.literals().get(index).value();
        }
        return null;
    }
}
