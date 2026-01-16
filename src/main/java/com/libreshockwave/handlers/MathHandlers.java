package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Random;

/**
 * Built-in math handlers for Lingo.
 */
public class MathHandlers {

    private static final Random random = new Random();

    public static void register(LingoVM vm) {
        // Basic math
        vm.registerBuiltin("abs", MathHandlers::abs);
        vm.registerBuiltin("sqrt", MathHandlers::sqrt);
        vm.registerBuiltin("power", MathHandlers::power);
        vm.registerBuiltin("exp", MathHandlers::exp);
        vm.registerBuiltin("log", MathHandlers::log);

        // Trigonometry
        vm.registerBuiltin("sin", MathHandlers::sin);
        vm.registerBuiltin("cos", MathHandlers::cos);
        vm.registerBuiltin("tan", MathHandlers::tan);
        vm.registerBuiltin("atan", MathHandlers::atan);
        vm.registerBuiltin("atan2", MathHandlers::atan2);

        // Constants
        vm.registerBuiltin("pi", MathHandlers::pi);

        // Random
        vm.registerBuiltin("random", MathHandlers::randomNum);

        // Rounding
        vm.registerBuiltin("integer", MathHandlers::toInteger);
        vm.registerBuiltin("float", MathHandlers::toFloat);

        // Min/Max
        vm.registerBuiltin("min", MathHandlers::min);
        vm.registerBuiltin("max", MathHandlers::max);
    }

    private static Datum abs(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        Datum a = args.get(0);
        if (a.isFloat()) return Datum.of(Math.abs(a.floatValue()));
        return Datum.of(Math.abs(a.intValue()));
    }

    private static Datum sqrt(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0f);
        return Datum.of((float) Math.sqrt(args.get(0).floatValue()));
    }

    private static Datum power(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of(0.0f);
        return Datum.of((float) Math.pow(args.get(0).floatValue(), args.get(1).floatValue()));
    }

    private static Datum exp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(1.0f);
        return Datum.of((float) Math.exp(args.get(0).floatValue()));
    }

    private static Datum log(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0f);
        return Datum.of((float) Math.log(args.get(0).floatValue()));
    }

    private static Datum sin(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0f);
        return Datum.of((float) Math.sin(args.get(0).floatValue()));
    }

    private static Datum cos(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(1.0f);
        return Datum.of((float) Math.cos(args.get(0).floatValue()));
    }

    private static Datum tan(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0f);
        return Datum.of((float) Math.tan(args.get(0).floatValue()));
    }

    private static Datum atan(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0f);
        return Datum.of((float) Math.atan(args.get(0).floatValue()));
    }

    private static Datum atan2(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of(0.0f);
        return Datum.of((float) Math.atan2(args.get(0).floatValue(), args.get(1).floatValue()));
    }

    private static Datum pi(LingoVM vm, List<Datum> args) {
        return Datum.of((float) Math.PI);
    }

    private static Datum randomNum(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        int max = args.get(0).intValue();
        if (max <= 0) return Datum.of(0);
        return Datum.of(random.nextInt(max) + 1);
    }

    private static Datum toInteger(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        return Datum.of(args.get(0).intValue());
    }

    private static Datum toFloat(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0f);
        return Datum.of(args.get(0).floatValue());
    }

    private static Datum min(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        float minVal = args.get(0).floatValue();
        for (int i = 1; i < args.size(); i++) {
            minVal = Math.min(minVal, args.get(i).floatValue());
        }
        return Datum.of(minVal);
    }

    private static Datum max(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        float maxVal = args.get(0).floatValue();
        for (int i = 1; i < args.size(); i++) {
            maxVal = Math.max(maxVal, args.get(i).floatValue());
        }
        return Datum.of(maxVal);
    }
}
