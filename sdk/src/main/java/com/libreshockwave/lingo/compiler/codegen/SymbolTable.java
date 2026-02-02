package com.libreshockwave.lingo.compiler.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Symbol table for managing names, literals, and variables during compilation.
 * Handles interning of names to ID mappings used in the script chunk format.
 */
public class SymbolTable {

    // Global name table (shared across all handlers in a script)
    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> nameToId = new HashMap<>();

    // Literal table
    private final List<LiteralEntry> literals = new ArrayList<>();
    private final Map<Object, Integer> literalToIndex = new HashMap<>();

    // Per-handler tracking
    private final List<String> currentArgs = new ArrayList<>();
    private final Map<String, Integer> argToIndex = new HashMap<>();
    private final List<String> currentLocals = new ArrayList<>();
    private final Map<String, Integer> localToIndex = new HashMap<>();

    // Script-level declarations
    private final List<String> properties = new ArrayList<>();
    private final Map<String, Integer> propertyToIndex = new HashMap<>();
    private final List<String> globals = new ArrayList<>();
    private final Map<String, Integer> globalToIndex = new HashMap<>();

    /**
     * Literal entry types matching ScriptChunk format.
     */
    public enum LiteralType {
        STRING(1),
        INT(4),
        FLOAT(9);

        private final int code;

        LiteralType(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /**
     * A literal entry in the literal table.
     */
    public record LiteralEntry(LiteralType type, Object value) {}

    // --- Name Management ---

    /**
     * Intern a name, returning its ID. If already interned, returns existing ID.
     */
    public int internName(String name) {
        // Case-insensitive interning
        String key = name.toLowerCase();
        if (nameToId.containsKey(key)) {
            return nameToId.get(key);
        }
        int id = names.size();
        names.add(name);
        nameToId.put(key, id);
        return id;
    }

    /**
     * Get a name by its ID.
     */
    public String getName(int id) {
        if (id < 0 || id >= names.size()) {
            return null;
        }
        return names.get(id);
    }

    /**
     * Get the ID for a name, or -1 if not found.
     */
    public int getNameId(String name) {
        return nameToId.getOrDefault(name.toLowerCase(), -1);
    }

    /**
     * Get all interned names.
     */
    public List<String> getNames() {
        return List.copyOf(names);
    }

    // --- Literal Management ---

    /**
     * Add a string literal, returning its index in the literal table.
     */
    public int addStringLiteral(String value) {
        // Check if already exists
        String key = "s:" + value;
        if (literalToIndex.containsKey(key)) {
            return literalToIndex.get(key);
        }
        int index = literals.size();
        literals.add(new LiteralEntry(LiteralType.STRING, value));
        literalToIndex.put(key, index);
        return index;
    }

    /**
     * Add an integer literal, returning its index in the literal table.
     * Note: Small integers (-128 to 127) can use PUSH_INT8 directly.
     */
    public int addIntLiteral(int value) {
        String key = "i:" + value;
        if (literalToIndex.containsKey(key)) {
            return literalToIndex.get(key);
        }
        int index = literals.size();
        literals.add(new LiteralEntry(LiteralType.INT, value));
        literalToIndex.put(key, index);
        return index;
    }

    /**
     * Add a float literal, returning its index in the literal table.
     */
    public int addFloatLiteral(double value) {
        String key = "f:" + Double.doubleToRawLongBits(value);
        if (literalToIndex.containsKey(key)) {
            return literalToIndex.get(key);
        }
        int index = literals.size();
        literals.add(new LiteralEntry(LiteralType.FLOAT, value));
        literalToIndex.put(key, index);
        return index;
    }

    /**
     * Get all literals.
     */
    public List<LiteralEntry> getLiterals() {
        return List.copyOf(literals);
    }

    // --- Handler Scope Management ---

    /**
     * Begin a new handler scope. Clears args and locals.
     */
    public void beginHandler() {
        currentArgs.clear();
        argToIndex.clear();
        currentLocals.clear();
        localToIndex.clear();
    }

    /**
     * Add a parameter to the current handler.
     */
    public int addArg(String name) {
        String key = name.toLowerCase();
        if (argToIndex.containsKey(key)) {
            return argToIndex.get(key);
        }
        int index = currentArgs.size();
        currentArgs.add(name);
        argToIndex.put(key, index);
        // Also intern the name
        internName(name);
        return index;
    }

    /**
     * Add a local variable to the current handler.
     */
    public int addLocal(String name) {
        String key = name.toLowerCase();
        if (localToIndex.containsKey(key)) {
            return localToIndex.get(key);
        }
        int index = currentLocals.size();
        currentLocals.add(name);
        localToIndex.put(key, index);
        // Also intern the name
        internName(name);
        return index;
    }

    /**
     * Check if a name is an argument in the current handler.
     */
    public boolean isArg(String name) {
        return argToIndex.containsKey(name.toLowerCase());
    }

    /**
     * Get the argument index, or -1 if not found.
     */
    public int getArgIndex(String name) {
        return argToIndex.getOrDefault(name.toLowerCase(), -1);
    }

    /**
     * Check if a name is a local in the current handler.
     */
    public boolean isLocal(String name) {
        return localToIndex.containsKey(name.toLowerCase());
    }

    /**
     * Get the local index, or -1 if not found.
     */
    public int getLocalIndex(String name) {
        return localToIndex.getOrDefault(name.toLowerCase(), -1);
    }

    /**
     * Get argument name IDs for the current handler.
     */
    public List<Integer> getArgNameIds() {
        List<Integer> ids = new ArrayList<>();
        for (String arg : currentArgs) {
            ids.add(getNameId(arg));
        }
        return ids;
    }

    /**
     * Get local name IDs for the current handler.
     */
    public List<Integer> getLocalNameIds() {
        List<Integer> ids = new ArrayList<>();
        for (String local : currentLocals) {
            ids.add(getNameId(local));
        }
        return ids;
    }

    /**
     * Get the current handler's argument count.
     */
    public int getArgCount() {
        return currentArgs.size();
    }

    /**
     * Get the current handler's local count.
     */
    public int getLocalCount() {
        return currentLocals.size();
    }

    // --- Script-Level Declarations ---

    /**
     * Add a property declaration.
     */
    public int addProperty(String name) {
        String key = name.toLowerCase();
        if (propertyToIndex.containsKey(key)) {
            return propertyToIndex.get(key);
        }
        int index = properties.size();
        properties.add(name);
        propertyToIndex.put(key, index);
        internName(name);
        return index;
    }

    /**
     * Check if a name is a property.
     */
    public boolean isProperty(String name) {
        return propertyToIndex.containsKey(name.toLowerCase());
    }

    /**
     * Get the property index, or -1 if not found.
     */
    public int getPropertyIndex(String name) {
        return propertyToIndex.getOrDefault(name.toLowerCase(), -1);
    }

    /**
     * Get all properties.
     */
    public List<String> getProperties() {
        return List.copyOf(properties);
    }

    /**
     * Get property name IDs.
     */
    public List<Integer> getPropertyNameIds() {
        List<Integer> ids = new ArrayList<>();
        for (String prop : properties) {
            ids.add(getNameId(prop));
        }
        return ids;
    }

    /**
     * Add a global declaration.
     */
    public int addGlobal(String name) {
        String key = name.toLowerCase();
        if (globalToIndex.containsKey(key)) {
            return globalToIndex.get(key);
        }
        int index = globals.size();
        globals.add(name);
        globalToIndex.put(key, index);
        internName(name);
        return index;
    }

    /**
     * Check if a name is a global.
     */
    public boolean isGlobal(String name) {
        return globalToIndex.containsKey(name.toLowerCase());
    }

    /**
     * Get the global index, or -1 if not found.
     */
    public int getGlobalIndex(String name) {
        return globalToIndex.getOrDefault(name.toLowerCase(), -1);
    }

    /**
     * Get all globals.
     */
    public List<String> getGlobals() {
        return List.copyOf(globals);
    }

    /**
     * Get global name IDs.
     */
    public List<Integer> getGlobalNameIds() {
        List<Integer> ids = new ArrayList<>();
        for (String global : globals) {
            ids.add(getNameId(global));
        }
        return ids;
    }

    /**
     * Resolve a variable name to its type and index.
     * Returns null if not found in any scope.
     */
    public VariableRef resolveVariable(String name) {
        String key = name.toLowerCase();

        // Check in order: args, locals, properties, globals
        if (argToIndex.containsKey(key)) {
            return new VariableRef(VariableScope.PARAM, argToIndex.get(key));
        }
        if (localToIndex.containsKey(key)) {
            return new VariableRef(VariableScope.LOCAL, localToIndex.get(key));
        }
        if (propertyToIndex.containsKey(key)) {
            return new VariableRef(VariableScope.PROPERTY, propertyToIndex.get(key));
        }
        if (globalToIndex.containsKey(key)) {
            return new VariableRef(VariableScope.GLOBAL, globalToIndex.get(key));
        }

        return null;
    }

    /**
     * Variable scope types.
     */
    public enum VariableScope {
        PARAM, LOCAL, PROPERTY, GLOBAL
    }

    /**
     * Reference to a resolved variable.
     */
    public record VariableRef(VariableScope scope, int index) {}

    /**
     * Reset all state (for reuse).
     */
    public void reset() {
        names.clear();
        nameToId.clear();
        literals.clear();
        literalToIndex.clear();
        currentArgs.clear();
        argToIndex.clear();
        currentLocals.clear();
        localToIndex.clear();
        properties.clear();
        propertyToIndex.clear();
        globals.clear();
        globalToIndex.clear();
    }
}
