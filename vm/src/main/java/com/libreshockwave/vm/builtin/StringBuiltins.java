package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * String-related builtin functions.
 */
public final class StringBuiltins {

    private StringBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("string", StringBuiltins::string);
        builtins.put("length", StringBuiltins::length);
        builtins.put("chars", StringBuiltins::chars);
        builtins.put("chartonum", StringBuiltins::charToNum);
        builtins.put("numtochar", StringBuiltins::numToChar);
        builtins.put("offset", StringBuiltins::offset);
    }

    private static Datum string(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.EMPTY_STRING;
        return Datum.of(args.get(0).toStr());
    }

    private static Datum length(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        Datum a = args.get(0);
        if (a instanceof Datum.Str s) {
            return Datum.of(s.value().length());
        } else if (a instanceof Datum.List l) {
            return Datum.of(l.items().size());
        } else if (a instanceof Datum.PropList p) {
            return Datum.of(p.properties().size());
        }
        return Datum.ZERO;
    }

    private static Datum chars(LingoVM vm, List<Datum> args) {
        if (args.size() < 3) return Datum.EMPTY_STRING;
        String str = args.get(0).toStr();
        int start = args.get(1).toInt() - 1; // Lingo is 1-indexed
        int end = args.get(2).toInt();
        if (start < 0) start = 0;
        if (end > str.length()) end = str.length();
        if (start >= end) return Datum.EMPTY_STRING;
        return Datum.of(str.substring(start, end));
    }

    private static Datum charToNum(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        String s = args.get(0).toStr();
        if (s.isEmpty()) return Datum.ZERO;
        return Datum.of((int) s.charAt(0));
    }

    private static Datum numToChar(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.EMPTY_STRING;
        int code = args.get(0).toInt();
        return Datum.of(String.valueOf((char) code));
    }

    /**
     * offset(substring, string)
     * Returns the 1-indexed position of substring in string, or 0 if not found.
     * Case-insensitive search.
     */
    private static Datum offset(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.ZERO;
        String substr = args.get(0).toStr();
        String str = args.get(1).toStr();
        if (substr.isEmpty()) return Datum.ZERO;

        // Case-insensitive search (Lingo default)
        int index = str.toLowerCase().indexOf(substr.toLowerCase());
        if (index < 0) return Datum.ZERO;
        return Datum.of(index + 1); // 1-indexed
    }
}
