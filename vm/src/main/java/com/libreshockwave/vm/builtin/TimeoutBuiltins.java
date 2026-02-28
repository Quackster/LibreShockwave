package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Built-in timeout() function and method dispatch for TimeoutRef.
 *
 * Director timeout usage:
 *   timeout("name").new(periodMs, #handler, target)  -- create a named timeout
 *   timeout("name").forget()                          -- cancel a timeout
 *   timeout("name").target                            -- get/set properties
 *
 * Factory form (no args):
 *   timeout().new("name", periodMs, #handler, target) -- name passed as first arg to new()
 */
public final class TimeoutBuiltins {

    private TimeoutBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("timeout", TimeoutBuiltins::timeout);
    }

    /**
     * timeout() builtin function.
     * - timeout("name") → returns TimeoutRef(name)
     * - timeout() → returns TimeoutRef("") (factory mode, name provided in .new())
     */
    private static Datum timeout(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return new Datum.TimeoutRef("");
        }
        String name = args.get(0).toStr();
        return new Datum.TimeoutRef(name);
    }

    /**
     * Handle method calls on a TimeoutRef.
     * Called from CallOpcodes.dispatchMethod().
     */
    public static Datum handleMethod(Datum.TimeoutRef ref, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        TimeoutProvider provider = TimeoutProvider.getProvider();

        return switch (method) {
            case "new" -> {
                if (provider == null) yield Datum.VOID;

                String name = ref.name();
                int argOffset = 0;

                // Factory mode: name is empty, take it from first arg
                if (name.isEmpty() && !args.isEmpty()) {
                    name = args.get(0).toStr();
                    argOffset = 1;
                }

                if (name.isEmpty()) yield Datum.VOID;

                // Extract period, handler, target
                int periodMs = args.size() > argOffset ? args.get(argOffset).toInt() : 1000;
                String handler = args.size() > argOffset + 1 ? getHandlerName(args.get(argOffset + 1)) : "";
                Datum target = args.size() > argOffset + 2 ? args.get(argOffset + 2) : Datum.VOID;

                yield provider.createTimeout(name, periodMs, handler, target);
            }
            case "forget" -> {
                if (provider != null && !ref.name().isEmpty()) {
                    provider.forgetTimeout(ref.name());
                }
                yield Datum.VOID;
            }
            default -> {
                // Try as property get
                if (provider != null && !ref.name().isEmpty()) {
                    yield provider.getTimeoutProp(ref.name(), method);
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Get a property from a TimeoutRef (for GET_OBJ_PROP / GET_CHAINED_PROP).
     */
    public static Datum getProperty(Datum.TimeoutRef ref, String propName) {
        TimeoutProvider provider = TimeoutProvider.getProvider();
        if (provider == null || ref.name().isEmpty()) {
            return Datum.VOID;
        }
        return provider.getTimeoutProp(ref.name(), propName);
    }

    /**
     * Set a property on a TimeoutRef (for SET_OBJ_PROP).
     */
    public static boolean setProperty(Datum.TimeoutRef ref, String propName, Datum value) {
        TimeoutProvider provider = TimeoutProvider.getProvider();
        if (provider == null || ref.name().isEmpty()) {
            return false;
        }
        return provider.setTimeoutProp(ref.name(), propName, value);
    }

    /**
     * Extract handler name from a Symbol or String argument.
     */
    private static String getHandlerName(Datum datum) {
        if (datum instanceof Datum.Symbol sym) {
            return sym.name();
        }
        return datum.toStr();
    }
}
