package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Constructor builtin functions (point, rect, color).
 */
public final class ConstructorBuiltins {

    private ConstructorBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("point", ConstructorBuiltins::point);
        builtins.put("rect", ConstructorBuiltins::rect);
        builtins.put("color", ConstructorBuiltins::color);
    }

    private static Datum point(LingoVM vm, List<Datum> args) {
        int x = args.size() > 0 ? args.get(0).toInt() : 0;
        int y = args.size() > 1 ? args.get(1).toInt() : 0;
        return new Datum.Point(x, y);
    }

    private static Datum rect(LingoVM vm, List<Datum> args) {
        int left = args.size() > 0 ? args.get(0).toInt() : 0;
        int top = args.size() > 1 ? args.get(1).toInt() : 0;
        int right = args.size() > 2 ? args.get(2).toInt() : 0;
        int bottom = args.size() > 3 ? args.get(3).toInt() : 0;
        return new Datum.Rect(left, top, right, bottom);
    }

    private static Datum color(LingoVM vm, List<Datum> args) {
        int r = args.size() > 0 ? args.get(0).toInt() : 0;
        int g = args.size() > 1 ? args.get(1).toInt() : 0;
        int b = args.size() > 2 ? args.get(2).toInt() : 0;
        return new Datum.Color(r, g, b);
    }
}
