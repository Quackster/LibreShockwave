package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Random;

import static com.libreshockwave.handlers.HandlerArgs.*;

/**
 * Built-in math handlers for Lingo.
 * Refactored: Uses HandlerArgs for argument extraction, reducing boilerplate.
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
        if (isEmpty(args)) return Datum.of(0);
        Datum a = get0(args);
        if (a.isFloat()) return Datum.of(Math.abs(a.floatValue()));
        return Datum.of(Math.abs(a.intValue()));
    }

    private static Datum sqrt(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0.0f);
        return Datum.of((float) Math.sqrt(getFloat0(args)));
    }

    private static Datum power(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.of(0.0f);
        return Datum.of((float) Math.pow(getFloat(args, 0, 0), getFloat(args, 1, 0)));
    }

    private static Datum exp(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(1.0f);
        return Datum.of((float) Math.exp(getFloat0(args)));
    }

    private static Datum log(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0.0f);
        return Datum.of((float) Math.log(getFloat0(args)));
    }

    private static Datum sin(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0.0f);
        return Datum.of((float) Math.sin(getFloat0(args)));
    }

    private static Datum cos(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(1.0f);
        return Datum.of((float) Math.cos(getFloat0(args)));
    }

    private static Datum tan(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0.0f);
        return Datum.of((float) Math.tan(getFloat0(args)));
    }

    private static Datum atan(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0.0f);
        return Datum.of((float) Math.atan(getFloat0(args)));
    }

    private static Datum atan2(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.of(0.0f);
        return Datum.of((float) Math.atan2(getFloat(args, 0, 0), getFloat(args, 1, 0)));
    }

    private static Datum pi(LingoVM vm, List<Datum> args) {
        return Datum.of((float) Math.PI);
    }

    private static Datum randomNum(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0);
        int max = getInt0(args);
        if (max <= 0) return Datum.of(0);
        return Datum.of(random.nextInt(max) + 1);
    }

    private static Datum toInteger(LingoVM vm, List<Datum> args) {
        return Datum.of(getInt0(args));
    }

    private static Datum toFloat(LingoVM vm, List<Datum> args) {
        return Datum.of(getFloat0(args));
    }

    private static Datum min(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0);
        float minVal = getFloat0(args);
        for (int i = 1; i < args.size(); i++) {
            minVal = Math.min(minVal, getFloat(args, i, 0));
        }
        return Datum.of(minVal);
    }

    private static Datum max(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0);
        float maxVal = getFloat0(args);
        for (int i = 1; i < args.size(); i++) {
            maxVal = Math.max(maxVal, getFloat(args, i, 0));
        }
        return Datum.of(maxVal);
    }
}
