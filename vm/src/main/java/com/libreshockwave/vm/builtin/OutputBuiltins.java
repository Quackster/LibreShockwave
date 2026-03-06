package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Output builtin functions (put, alert, pass).
 */
public final class OutputBuiltins {

    private OutputBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("put", OutputBuiltins::put);
        builtins.put("alert", OutputBuiltins::alert);
        builtins.put("error", OutputBuiltins::error);
        // Note: pass is registered separately by LingoVM since it needs the passCallback
    }

    private static Datum put(LingoVM vm, List<Datum> args) {
        if (!DebugConfig.isDebugPlaybackEnabled()) {
            return Datum.VOID;
        }
        for (Datum arg : args) {
            System.out.print(arg.toStr() + " ");
        }
        System.out.println();
        return Datum.VOID;
    }

    /**
     * Lingo error(tObject, tMsg, tMethod, tErrorLevel) — fallback when the
     * Error Manager script isn't loaded yet. Logs the args and call stack
     * when debug is enabled, and always returns 0 (false).
     */
    private static Datum error(LingoVM vm, List<Datum> args) {
        if (DebugConfig.isDebugPlaybackEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(args.get(i).toStr());
            }
            System.out.println(sb.toString());
            System.out.println(vm.formatCallStack());
        }
        return Datum.of(0);
    }

    private static Datum alert(LingoVM vm, List<Datum> args) {
        String msg = args.isEmpty() ? "" : args.get(0).toStr();
        // Try alertHook first — if it handles the alert, suppress the default output
        if (vm.fireAlertHook(msg)) {
            return Datum.VOID;
        }
        System.out.println("[ALERT] " + msg);
        return Datum.VOID;
    }
}
