package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Builtins for Shockwave external parameter access.
 * - externalParamValue(nameOrIndex) -> value string or VOID
 * - externalParamName(nameOrIndex) -> name string or VOID
 * - externalParamCount() -> integer count
 */
public final class ExternalParamBuiltins {

    private ExternalParamBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("externalparamvalue", ExternalParamBuiltins::externalParamValue);
        builtins.put("externalparamname", ExternalParamBuiltins::externalParamName);
        builtins.put("externalparamcount", ExternalParamBuiltins::externalParamCount);
    }

    private static Datum externalParamValue(LingoVM vm, List<Datum> args) {
        ExternalParamProvider provider = ExternalParamProvider.getProvider();
        if (provider == null || args.isEmpty()) {
            return Datum.VOID;
        }

        Datum arg = args.get(0);

        // Case 1: string argument - lookup by name (case-insensitive)
        if (arg instanceof Datum.Str s) {
            String value = provider.getParamValue(s.value());
            return value != null ? Datum.of(value) : Datum.VOID;
        }

        // Case 2: integer argument - lookup by 1-based index
        if (arg instanceof Datum.Int i) {
            int index = i.value();
            if (index > 0 && index <= provider.getParamCount()) {
                String name = provider.getParamName(index);
                if (name != null) {
                    String value = provider.getParamValue(name);
                    return value != null ? Datum.of(value) : Datum.VOID;
                }
            }
            return Datum.VOID;
        }

        return Datum.VOID;
    }

    private static Datum externalParamName(LingoVM vm, List<Datum> args) {
        ExternalParamProvider provider = ExternalParamProvider.getProvider();
        if (provider == null || args.isEmpty()) {
            return Datum.VOID;
        }

        Datum arg = args.get(0);

        // Case 1: string argument - verify the name exists (case-insensitive)
        if (arg instanceof Datum.Str s) {
            // Check if this key exists (any case)
            for (String key : provider.getAllParams().keySet()) {
                if (key.equalsIgnoreCase(s.value())) {
                    return Datum.of(key);
                }
            }
            return Datum.VOID;
        }

        // Case 2: integer argument - lookup by 1-based index
        if (arg instanceof Datum.Int i) {
            String name = provider.getParamName(i.value());
            return name != null ? Datum.of(name) : Datum.VOID;
        }

        return Datum.VOID;
    }

    private static Datum externalParamCount(LingoVM vm, List<Datum> args) {
        ExternalParamProvider provider = ExternalParamProvider.getProvider();
        if (provider == null) {
            return Datum.ZERO;
        }
        return Datum.of(provider.getParamCount());
    }
}
