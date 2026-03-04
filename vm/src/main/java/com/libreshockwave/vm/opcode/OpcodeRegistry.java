package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of opcode handlers.
 * Maps each Opcode to its handler implementation.
 *
 * Uses a flat array indexed by Opcode.ordinal() for O(1) dispatch
 * without any map lookup overhead. Critical for WASM where each
 * instruction's dispatch cost matters (~292K instructions during dump).
 */
public class OpcodeRegistry {

    // Flat array for O(1) dispatch — faster than EnumMap for hot-path lookups
    private final OpcodeHandler[] handlerArray;
    private boolean traceEnabled = false;

    public OpcodeRegistry() {
        // Use EnumMap for registration phase, then flatten to array
        Map<Opcode, OpcodeHandler> handlers = new EnumMap<>(Opcode.class);
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

        // Flatten to array indexed by ordinal
        Opcode[] allOpcodes = Opcode.values();
        handlerArray = new OpcodeHandler[allOpcodes.length];
        for (Map.Entry<Opcode, OpcodeHandler> entry : handlers.entrySet()) {
            handlerArray[entry.getKey().ordinal()] = entry.getValue();
        }
    }

    /**
     * Get the handler for an opcode.
     * @param opcode The opcode
     * @return The handler, or null if not implemented
     */
    public OpcodeHandler get(Opcode opcode) {
        return handlerArray[opcode.ordinal()];
    }

    /**
     * Check if an opcode has a registered handler.
     */
    public boolean hasHandler(Opcode opcode) {
        return handlerArray[opcode.ordinal()] != null;
    }

    /**
     * Register a handler for an opcode (post-initialization).
     */
    public void register(Opcode opcode, OpcodeHandler handler) {
        handlerArray[opcode.ordinal()] = handler;
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
