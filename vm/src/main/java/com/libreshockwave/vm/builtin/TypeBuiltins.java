package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoVM;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Type-checking and conversion builtin functions.
 * Includes: objectp, voidp, value, script, ilk, listp, stringp, integerp, floatp, symbolp
 */
public final class TypeBuiltins {

    private TypeBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("objectp", TypeBuiltins::objectp);
        builtins.put("voidp", TypeBuiltins::voidp);
        builtins.put("value", TypeBuiltins::value);
        builtins.put("script", TypeBuiltins::script);
        builtins.put("ilk", TypeBuiltins::ilk);
        builtins.put("listp", TypeBuiltins::listp);
        builtins.put("stringp", TypeBuiltins::stringp);
        builtins.put("integerp", TypeBuiltins::integerp);
        builtins.put("floatp", TypeBuiltins::floatp);
        builtins.put("symbolp", TypeBuiltins::symbolp);
    }

    /**
     * objectp(value)
     * Returns TRUE if the value is an object (script instance, list, proplist, etc.)
     * Returns FALSE for void, integers, floats, strings, and symbols.
     */
    private static Datum objectp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        Datum value = args.get(0);
        boolean isObject = switch (value) {
            case Datum.Void v -> false;
            case Datum.Int i -> false;
            case Datum.Float f -> false;
            case Datum.Symbol s -> false;
            case Datum.Str s -> false;
            default -> true;
        };
        return isObject ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * voidp(value)
     * Returns TRUE if the value is VOID.
     */
    private static Datum voidp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.TRUE;
        }
        Datum value = args.get(0);
        return value.isVoid() ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * value(expression)
     * Evaluates a string expression or returns the value as-is.
     * For strings, attempts to parse and evaluate as Lingo.
     * For non-strings, returns the value unchanged.
     */
    private static Datum value(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }
        Datum arg = args.get(0);

        // For non-strings, return as-is
        if (!(arg instanceof Datum.Str str)) {
            return arg;
        }

        // For strings, try to evaluate as a simple expression
        String expr = str.value().trim();

        // Empty string -> VOID
        if (expr.isEmpty()) {
            return Datum.VOID;
        }

        // Try to parse as integer
        try {
            return Datum.of(Integer.parseInt(expr));
        } catch (NumberFormatException ignored) {}

        // Try to parse as float
        try {
            return Datum.of(Double.parseDouble(expr));
        } catch (NumberFormatException ignored) {}

        // Try to parse as symbol (#symbol)
        if (expr.startsWith("#")) {
            return Datum.symbol(expr.substring(1));
        }

        // Try to parse as quoted string
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2) {
            return Datum.of(expr.substring(1, expr.length() - 1));
        }

        // Try to evaluate as a handler call (simple case: just a handler name)
        if (expr.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            // Try to find and call a handler with no arguments
            HandlerRef ref = vm.findHandler(expr);
            if (ref != null) {
                return vm.executeHandler(ref.script(), ref.handler(), List.of(), null);
            }
            // Also check if it's a global variable
            Datum globalValue = vm.getGlobal(expr);
            if (!globalValue.isVoid()) {
                return globalValue;
            }
        }

        // Return VOID for complex expressions we can't evaluate
        return Datum.VOID;
    }

    /**
     * script(identifier)
     * Returns a ScriptRef for the specified script name or number.
     * The script can then be used with new() to create instances.
     */
    private static Datum script(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        Datum identifier = args.get(0);
        CastLibProvider provider = CastLibProvider.getProvider();

        if (identifier instanceof Datum.Str str) {
            // Find script by name
            if (provider != null) {
                Datum memberRef = provider.getMemberByName(0, str.value());
                if (memberRef instanceof Datum.CastMemberRef cmr) {
                    return new Datum.ScriptRef(cmr.castLib(), cmr.member());
                }
            }
        } else if (identifier instanceof Datum.Int num) {
            // Find script by number - assume cast 1
            if (provider != null) {
                return new Datum.ScriptRef(1, num.value());
            }
        } else if (identifier instanceof Datum.CastMemberRef cmr) {
            // Already a cast member reference
            return new Datum.ScriptRef(cmr.castLib(), cmr.member());
        }

        return Datum.VOID;
    }

    /**
     * ilk(value) or ilk(value, type)
     * Returns the type of a value, or checks if value matches type.
     */
    private static Datum ilk(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.symbol("void");
        }

        Datum value = args.get(0);
        String typeName = getIlkType(value);

        // If second argument provided, check if types match
        if (args.size() >= 2) {
            Datum checkType = args.get(1);
            String checkName = checkType instanceof Datum.Symbol s ? s.name() : checkType.toStr();
            return typeName.equalsIgnoreCase(checkName) ? Datum.TRUE : Datum.FALSE;
        }

        return Datum.symbol(typeName);
    }

    private static String getIlkType(Datum value) {
        return switch (value) {
            case Datum.Void v -> "void";
            case Datum.Int i -> "integer";
            case Datum.Float f -> "float";
            case Datum.Str s -> "string";
            case Datum.Symbol s -> "symbol";
            case Datum.List l -> "list";
            case Datum.PropList p -> "propList";
            case Datum.Point p -> "point";
            case Datum.Rect r -> "rect";
            case Datum.Color c -> "color";
            case Datum.SpriteRef s -> "sprite";
            case Datum.CastMemberRef c -> "member";
            case Datum.CastLibRef c -> "castLib";
            case Datum.ScriptInstance s -> "instance";
            case Datum.ScriptRef s -> "script";
            case Datum.XtraRef x -> "xtra";
            case Datum.XtraInstance x -> "xtraInstance";
            case Datum.StageRef s -> "stage";
            case Datum.WindowRef w -> "window";
            default -> "object";
        };
    }

    /**
     * listp(value)
     * Returns TRUE if value is a linear list.
     */
    private static Datum listp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        return args.get(0) instanceof Datum.List ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * stringp(value)
     * Returns TRUE if value is a string.
     */
    private static Datum stringp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        return args.get(0) instanceof Datum.Str ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * integerp(value)
     * Returns TRUE if value is an integer.
     */
    private static Datum integerp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        return args.get(0) instanceof Datum.Int ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * floatp(value)
     * Returns TRUE if value is a float.
     */
    private static Datum floatp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        return args.get(0) instanceof Datum.Float ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * symbolp(value)
     * Returns TRUE if value is a symbol.
     */
    private static Datum symbolp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        return args.get(0) instanceof Datum.Symbol ? Datum.TRUE : Datum.FALSE;
    }
}
