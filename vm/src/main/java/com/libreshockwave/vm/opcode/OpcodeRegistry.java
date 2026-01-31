package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of opcode handlers.
 * Maps each Opcode to its handler implementation.
 */
public class OpcodeRegistry {

    private final Map<Opcode, OpcodeHandler> handlers = new EnumMap<>(Opcode.class);
    private boolean traceEnabled = false;

    public OpcodeRegistry() {
        StackOpcodes.register(handlers);
        ArithmeticOpcodes.register(handlers);
        ComparisonOpcodes.register(handlers);
        LogicalOpcodes.register(handlers);
        StringOpcodes.register(handlers);
        VariableOpcodes.register(handlers);
        ControlFlowOpcodes.register(handlers);
        ListOpcodes.register(handlers);
        CallOpcodes.register(handlers);
        PropertyOpcodes.register(handlers);
    }

    /**
     * Get the handler for an opcode.
     * @param opcode The opcode
     * @return The handler, or null if not implemented
     */
    public OpcodeHandler get(Opcode opcode) {
        return handlers.get(opcode);
    }

    /**
     * Check if an opcode has a registered handler.
     */
    public boolean hasHandler(Opcode opcode) {
        return handlers.containsKey(opcode);
    }

    /**
     * Register a handler for an opcode.
     */
    public void register(Opcode opcode, OpcodeHandler handler) {
        handlers.put(opcode, handler);
    }

    /**
     * Set trace mode for unimplemented opcode warnings.
     */
    public void setTraceEnabled(boolean enabled) {
        this.traceEnabled = enabled;
    }

    public boolean isTraceEnabled() {
        return traceEnabled;
    }
}
