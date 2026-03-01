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
        builtins.put("addat", ListBuiltins::addAt);
        builtins.put("append", ListBuiltins::append);
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

    /**
     * append(list, value) - add value to the end of a list.
     */
    private static Datum append(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;
        Datum datum = args.get(0);
        if (datum instanceof Datum.List list) {
            list.items().add(args.get(1));
        }
        return Datum.VOID;
    }

    /**
     * addAt(list, position, value) - insert value at position in a list.
     * Matching dirplayer-rs: if first arg is not a List, return VOID (no-op).
     */
    private static Datum addAt(LingoVM vm, List<Datum> args) {
        if (args.size() < 3) return Datum.VOID;
        Datum datum = args.get(0);
        if (!(datum instanceof Datum.List list)) {
            // Non-list types: no-op, return VOID (matches dirplayer-rs)
            return Datum.VOID;
        }
        int position = args.get(1).toInt() - 1; // Lingo is 1-indexed
        Datum value = args.get(2);
        if (position < 0) position = 0;
        if (position > list.items().size()) position = list.items().size();
        list.items().add(position, value);
        return Datum.VOID;
    }
}
