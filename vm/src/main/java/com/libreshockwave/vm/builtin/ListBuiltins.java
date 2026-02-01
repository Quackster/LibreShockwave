package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * List-related builtin functions.
 */
public final class ListBuiltins {

    private ListBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("count", ListBuiltins::count);
        builtins.put("getat", ListBuiltins::getAt);
    }

    private static Datum count(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        Datum a = args.get(0);
        if (a instanceof Datum.List l) {
            return Datum.of(l.items().size());
        } else if (a instanceof Datum.PropList p) {
            return Datum.of(p.properties().size());
        }
        return Datum.ZERO;
    }

    private static Datum getAt(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;
        Datum list = args.get(0);
        int index = args.get(1).toInt() - 1; // Lingo is 1-indexed
        if (list instanceof Datum.List l) {
            if (index >= 0 && index < l.items().size()) {
                return l.items().get(index);
            }
        }
        return Datum.VOID;
    }
}
