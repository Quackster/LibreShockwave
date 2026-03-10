package com.libreshockwave.vm.builtin.timeout;

import com.libreshockwave.vm.datum.Datum;
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
        // Note: createTimeout and timeoutExists are NOT registered as builtins.
        // Habbo defines these as movie script functions that delegate to its own
        // Timeout Manager Class (which wraps Java timeouts with a pItemList PropList).
        // Registering builtins would shadow the movie scripts and bypass the Lingo layer,
        // causing removeTimeout (no builtin) to fail with "Item not found".
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
     * createTimeout(name, periodMs, #handler, target, VOID, oneShot)
     * Global function form of timeout(name).new(periodMs, #handler, target).
     * Args 5+ are optional. Arg 6 (oneShot): if truthy, timeout auto-removes after firing once.
     */
    private static Datum createTimeout(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        TimeoutProvider provider = TimeoutProvider.getProvider();
        if (provider == null) return Datum.VOID;

        String name = args.get(0).toStr();
        int periodMs = args.size() > 1 ? args.get(1).toInt() : 1000;
        String handler = args.size() > 2 ? getHandlerName(args.get(2)) : "";
        Datum target = args.size() > 3 ? args.get(3) : Datum.VOID;
        // Arg 4 (index 4) is unused (typically VOID)
        boolean oneShot = args.size() > 5 && args.get(5).isTruthy();

        Datum result = provider.createTimeout(name, periodMs, handler, target);

        // Set oneShot via the property interface if supported
        if (oneShot) {
            provider.setTimeoutProp(name, "oneshot", Datum.TRUE);
        }

        return result;
    }

    /**
     * timeoutExists(name) → 1 or 0
     */
    private static Datum timeoutExists(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        TimeoutProvider provider = TimeoutProvider.getProvider();
        if (provider == null) return Datum.FALSE;
        String name = args.get(0).toStr();
        return provider.timeoutExists(name) ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * Handle method calls on a TimeoutRef.
     * Called from CallOpcodes.dispatchMethod().
     */
    public static Datum handleMethod(Datum.TimeoutRef ref, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        TimeoutProvider provider = TimeoutProvider.getProvider();

        if ("new".equals(method)) {
            if (provider == null) return Datum.VOID;

            String name = ref.name();
            int argOffset = 0;

            // Factory mode: name is empty, take it from first arg
            if (name.isEmpty() && !args.isEmpty()) {
                name = args.get(0).toStr();
                argOffset = 1;
            }

            if (name.isEmpty()) return Datum.VOID;

            // Extract period, handler, target
            int periodMs = args.size() > argOffset ? args.get(argOffset).toInt() : 1000;
            String handler = args.size() > argOffset + 1 ? getHandlerName(args.get(argOffset + 1)) : "";
            Datum target = args.size() > argOffset + 2 ? args.get(argOffset + 2) : Datum.VOID;

            return provider.createTimeout(name, periodMs, handler, target);
        } else if ("forget".equals(method)) {
            if (provider != null && !ref.name().isEmpty()) {
                provider.forgetTimeout(ref.name());
            }
            return Datum.VOID;
        } else {
            // Try as property get
            if (provider != null && !ref.name().isEmpty()) {
                return provider.getTimeoutProp(ref.name(), method);
            }
            return Datum.VOID;
        }
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

    private static String getHandlerName(Datum datum) {
        return datum.toKeyName();
    }
}
