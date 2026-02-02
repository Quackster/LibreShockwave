package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Math-related builtin functions.
 */
public final class MathBuiltins {

    private MathBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("abs", MathBuiltins::abs);
        builtins.put("sqrt", MathBuiltins::sqrt);
        builtins.put("sin", MathBuiltins::sin);
        builtins.put("cos", MathBuiltins::cos);
        builtins.put("random", MathBuiltins::random);
        builtins.put("integer", MathBuiltins::integer);
        builtins.put("float", MathBuiltins::toFloat);
    }

    private static Datum abs(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        Datum a = args.get(0);
        if (a.isFloat()) return Datum.of(Math.abs(a.toDouble()));
        return Datum.of(Math.abs(a.toInt()));
    }

    private static Datum sqrt(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        return Datum.of(Math.sqrt(args.get(0).toDouble()));
    }

    private static Datum sin(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        return Datum.of(Math.sin(Math.toRadians(args.get(0).toDouble())));
    }

    private static Datum cos(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        return Datum.of(Math.cos(Math.toRadians(args.get(0).toDouble())));
    }

    private static Datum random(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(1);
        int max = args.get(0).toInt();
        if (max <= 0) return Datum.of(1);
        return Datum.of((int) (Math.random() * max) + 1);
    }

    /**
     * integer(value)
     * Converts a value to an integer.
     * - For floats: truncates to integer
     * - For numeric strings: converts to integer
     * - For non-numeric strings: returns the original string unchanged
     */
    private static Datum integer(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        Datum arg = args.get(0);

        // For strings, only convert if it's actually numeric
        if (arg instanceof Datum.Str str) {
            String trimmed = str.value().trim();
            try {
                // Try integer first
                return Datum.of(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                try {
                    // Try parsing as float and truncating
                    return Datum.of((int) Double.parseDouble(trimmed));
                } catch (NumberFormatException e2) {
                    // Return the original string unchanged if not a valid number
                    return arg;
                }
            }
        }

        // For other types, use standard conversion
        return Datum.of(arg.toInt());
    }

    /**
     * float(value)
     * Converts a value to a floating-point number.
     * - For integers: converts to float (1 -> 1.0)
     * - For floats: returns as-is
     * - For numeric strings: converts to float ("1.5" -> 1.5)
     * - For non-numeric strings: returns the original string unchanged
     *   (this allows floatp(float(x)) to correctly return false for non-numeric strings)
     */
    private static Datum toFloat(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0.0);
        Datum arg = args.get(0);

        // For strings, only convert if it's actually numeric
        if (arg instanceof Datum.Str str) {
            try {
                return Datum.of(Double.parseDouble(str.value().trim()));
            } catch (NumberFormatException e) {
                // Return the original string unchanged if not a valid number
                return arg;
            }
        }

        // For other types, use standard conversion
        return Datum.of(arg.toDouble());
    }
}
