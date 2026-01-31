package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
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
        // Note: pass is registered separately by LingoVM since it needs the passCallback
    }

    private static Datum put(LingoVM vm, List<Datum> args) {
        for (Datum arg : args) {
            System.out.print(arg.toStr() + " ");
        }
        System.out.println();
        return Datum.VOID;
    }

    private static Datum alert(LingoVM vm, List<Datum> args) {
        String msg = args.isEmpty() ? "" : args.get(0).toStr();
        System.out.println("[ALERT] " + msg);
        return Datum.VOID;
    }
}
