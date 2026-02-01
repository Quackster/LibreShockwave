package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Registry of built-in Lingo functions.
 */
public class BuiltinRegistry {

    private final Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins = new HashMap<>();

    public BuiltinRegistry() {
        MathBuiltins.register(builtins);
        StringBuiltins.register(builtins);
        ListBuiltins.register(builtins);
        ConstructorBuiltins.register(builtins);
        OutputBuiltins.register(builtins);
        NetBuiltins.register(builtins);
        XtraBuiltins.register(builtins);
        CastLibBuiltins.register(builtins);
    }

    /**
     * Check if a builtin function exists.
     */
    public boolean contains(String name) {
        return builtins.containsKey(name);
    }

    /**
     * Invoke a builtin function.
     */
    public Datum invoke(String name, LingoVM vm, List<Datum> args) {
        var func = builtins.get(name);
        if (func != null) {
            return func.apply(vm, args);
        }
        return Datum.VOID;
    }

    /**
     * Register a custom builtin function.
     */
    public void register(String name, BiFunction<LingoVM, List<Datum>, Datum> func) {
        builtins.put(name, func);
    }

    /**
     * Get the underlying map for direct access (used by pass() builtin).
     */
    public Map<String, BiFunction<LingoVM, List<Datum>, Datum>> getMap() {
        return builtins;
    }
}
