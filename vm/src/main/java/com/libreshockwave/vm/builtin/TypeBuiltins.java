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
     * Based on dirplayer-rs vm-rust/src/player/handlers/types.rs
     */
    private static Datum value(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }
        Datum arg = args.get(0);

        // For non-strings, return as-is (matches dirplayer-rs behavior)
        if (!(arg instanceof Datum.Str str)) {
            return arg;
        }

        // For strings, try to evaluate as a Lingo expression
        String expr = str.value().trim();

        // Empty string -> VOID
        if (expr.isEmpty()) {
            return Datum.VOID;
        }

        try {
            return parseLingoExpression(expr, vm);
        } catch (Exception e) {
            // On parse error, return VOID (matches dirplayer-rs behavior)
            return Datum.VOID;
        }
    }

    /**
     * Parse a Lingo expression string into a Datum.
     * Handles: integers, floats, symbols, quoted strings, lists, and proplists.
     */
    private static Datum parseLingoExpression(String expr, LingoVM vm) {
        expr = expr.trim();

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
        if (expr.startsWith("#") && expr.length() > 1 && !expr.contains(":")) {
            String symName = expr.substring(1);
            if (symName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return Datum.symbol(symName);
            }
        }

        // Try to parse as quoted string
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2) {
            return Datum.of(expr.substring(1, expr.length() - 1));
        }

        // Try to parse as list or proplist: [...]
        if (expr.startsWith("[") && expr.endsWith("]")) {
            return parseListOrPropList(expr.substring(1, expr.length() - 1).trim(), vm);
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

        // Return VOID for expressions we can't evaluate
        return Datum.VOID;
    }

    /**
     * Parse list or proplist content (without the surrounding brackets).
     * Determines if it's a proplist (has #key: value pairs) or a linear list.
     */
    private static Datum parseListOrPropList(String content, LingoVM vm) {
        if (content.isEmpty()) {
            // Empty brackets -> empty list
            return Datum.list();
        }

        // Split by commas (respecting nested brackets and quoted strings)
        java.util.List<String> elements = splitListElements(content);

        if (elements.isEmpty()) {
            return Datum.list();
        }

        // Check if first element looks like a proplist entry (#key: value)
        String first = elements.get(0).trim();
        if (first.startsWith("#") && first.contains(":")) {
            // Parse as proplist
            Map<String, Datum> props = new LinkedHashMap<>();
            for (String element : elements) {
                element = element.trim();
                int colonIdx = findPropListColon(element);
                if (colonIdx > 0 && element.startsWith("#")) {
                    String key = element.substring(1, colonIdx).trim();
                    String valueStr = element.substring(colonIdx + 1).trim();
                    Datum value = parseLingoExpression(valueStr, vm);
                    props.put(key, value);
                }
            }
            return Datum.propList(props);
        } else {
            // Parse as linear list
            java.util.List<Datum> items = new java.util.ArrayList<>();
            for (String element : elements) {
                items.add(parseLingoExpression(element.trim(), vm));
            }
            return Datum.list(items);
        }
    }

    /**
     * Split list content by commas, respecting nested brackets and quoted strings.
     */
    private static java.util.List<String> splitListElements(String content) {
        java.util.List<String> elements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        boolean inQuote = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);

            if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
                current.append(c);
            } else if (inQuote) {
                current.append(c);
            } else if (c == '[') {
                bracketDepth++;
                current.append(c);
            } else if (c == ']') {
                bracketDepth--;
                current.append(c);
            } else if (c == ',' && bracketDepth == 0) {
                elements.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            elements.add(current.toString());
        }

        return elements;
    }

    /**
     * Find the colon in a proplist entry (#key: value), respecting nested structures.
     */
    private static int findPropListColon(String element) {
        int bracketDepth = 0;
        boolean inQuote = false;

        for (int i = 0; i < element.length(); i++) {
            char c = element.charAt(i);

            if (c == '"' && (i == 0 || element.charAt(i - 1) != '\\')) {
                inQuote = !inQuote;
            } else if (!inQuote) {
                if (c == '[') {
                    bracketDepth++;
                } else if (c == ']') {
                    bracketDepth--;
                } else if (c == ':' && bracketDepth == 0) {
                    return i;
                }
            }
        }
        return -1;
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
