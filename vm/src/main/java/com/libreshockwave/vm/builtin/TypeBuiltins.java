package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoVM;

import java.util.ArrayList;
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
        builtins.put("symbol", TypeBuiltins::symbol);
        builtins.put("callancestor", AncestorCallHandler::call);
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
     *
     * Director behavior: "Expressions that Lingo cannot parse will produce unexpected
     * results, but will not produce Lingo errors. The result is the value of the
     * initial portion of the expression up to the first syntax error found in the string."
     *
     * Examples:
     * - value("3 5") returns 3 (parses first token, stops at syntax error)
     * - value("penny") returns VOID (unknown identifier has no value)
     * - value("[\"cat\", \"dog\"]") returns ["cat", "dog"]
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

        // For strings, try to evaluate as a Lingo expression
        String expr = str.value().trim();

        // Empty string -> VOID
        if (expr.isEmpty()) {
            return Datum.VOID;
        }

        // Use partial parsing - returns the value of the initial valid portion
        return parseLingoExpressionWithPartial(expr, vm);
    }

    /**
     * Parse a Lingo expression string into a Datum with partial parsing support.
     * Per Director docs: returns the value of the initial valid portion on syntax error.
     * Handles: integers, floats, symbols, quoted strings, lists, and proplists.
     */
    private static Datum parseLingoExpressionWithPartial(String expr, LingoVM vm) {
        expr = expr.trim();

        if (expr.isEmpty()) {
            return Datum.VOID;
        }

        // Try complete expression first (most common case)
        Datum completeResult = tryParseComplete(expr, vm);
        if (completeResult != null) {
            return completeResult;
        }

        // Partial parsing: extract and evaluate the first valid token/expression
        return parseFirstValidExpression(expr, vm);
    }

    /**
     * Try to parse the complete expression as a single value.
     * Returns null if the complete string cannot be parsed as-is.
     */
    private static Datum tryParseComplete(String expr, LingoVM vm) {
        // Try to parse as integer (complete match)
        if (expr.matches("-?\\d+")) {
            try {
                return Datum.of(Integer.parseInt(expr));
            } catch (NumberFormatException ignored) {}
        }

        // Try to parse as float (complete match)
        if (expr.matches("-?\\d+\\.\\d+")) {
            try {
                return Datum.of(Double.parseDouble(expr));
            } catch (NumberFormatException ignored) {}
        }

        // Try to parse as symbol (#symbol) - complete match
        if (expr.startsWith("#") && expr.length() > 1) {
            String symName = expr.substring(1);
            if (symName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                return Datum.symbol(symName);
            }
        }

        // Try to parse as quoted string - must have matching quotes
        if (expr.startsWith("\"") && expr.endsWith("\"") && expr.length() >= 2) {
            return Datum.of(unescapeString(expr.substring(1, expr.length() - 1)));
        }

        // Try to parse as list or proplist: [...]
        if (expr.startsWith("[") && expr.endsWith("]")) {
            try {
                return parseListOrPropList(expr.substring(1, expr.length() - 1).trim(), vm);
            } catch (Exception ignored) {
                // If list parsing fails, fall through to partial parsing
            }
        }

        // Try to evaluate as a simple identifier (handler call or global variable)
        if (expr.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            // Check global variable first
            Datum globalValue = vm.getGlobal(expr);
            if (!globalValue.isVoid()) {
                return globalValue;
            }
            // Try to find and call a handler with no arguments
            HandlerRef ref = vm.findHandler(expr);
            if (ref != null) {
                return vm.executeHandler(ref.script(), ref.handler(), List.of(), null);
            }
            // Unknown identifier - return VOID (like "penny" in the docs)
            return Datum.VOID;
        }

        // Couldn't parse as a complete simple expression
        return null;
    }

    /**
     * Extract and evaluate the first valid expression from the string.
     * This handles cases like "3 5" where we should return 3.
     */
    private static Datum parseFirstValidExpression(String expr, LingoVM vm) {
        int pos = 0;
        int len = expr.length();

        // Skip leading whitespace
        while (pos < len && Character.isWhitespace(expr.charAt(pos))) {
            pos++;
        }

        if (pos >= len) {
            return Datum.VOID;
        }

        char first = expr.charAt(pos);

        // Try to parse a number (integer or float)
        if (Character.isDigit(first) || (first == '-' && pos + 1 < len && Character.isDigit(expr.charAt(pos + 1)))) {
            int start = pos;
            if (first == '-') pos++;

            // Parse integer part
            while (pos < len && Character.isDigit(expr.charAt(pos))) {
                pos++;
            }

            // Check for decimal point
            if (pos < len && expr.charAt(pos) == '.' && pos + 1 < len && Character.isDigit(expr.charAt(pos + 1))) {
                pos++; // consume '.'
                while (pos < len && Character.isDigit(expr.charAt(pos))) {
                    pos++;
                }
                // Parsed a float
                String numStr = expr.substring(start, pos);
                try {
                    return Datum.of(Double.parseDouble(numStr));
                } catch (NumberFormatException e) {
                    return Datum.VOID;
                }
            } else {
                // Parsed an integer
                String numStr = expr.substring(start, pos);
                try {
                    return Datum.of(Integer.parseInt(numStr));
                } catch (NumberFormatException e) {
                    return Datum.VOID;
                }
            }
        }

        // Try to parse a quoted string
        if (first == '"') {
            int start = pos;
            pos++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < len && expr.charAt(pos) != '"') {
                if (expr.charAt(pos) == '\\' && pos + 1 < len) {
                    pos++; // skip backslash
                    sb.append(expr.charAt(pos));
                } else {
                    sb.append(expr.charAt(pos));
                }
                pos++;
            }
            if (pos < len && expr.charAt(pos) == '"') {
                return Datum.of(sb.toString());
            }
            // Unterminated string - return VOID
            return Datum.VOID;
        }

        // Try to parse a symbol
        if (first == '#') {
            pos++; // skip #
            int start = pos;
            while (pos < len && (Character.isLetterOrDigit(expr.charAt(pos)) || expr.charAt(pos) == '_')) {
                pos++;
            }
            if (pos > start) {
                return Datum.symbol(expr.substring(start, pos));
            }
            return Datum.VOID;
        }

        // Try to parse a list/proplist - need matching brackets
        if (first == '[') {
            int bracketDepth = 1;
            int start = pos;
            pos++; // skip opening bracket
            while (pos < len && bracketDepth > 0) {
                char c = expr.charAt(pos);
                if (c == '[') bracketDepth++;
                else if (c == ']') bracketDepth--;
                else if (c == '"') {
                    // Skip quoted strings within the list
                    pos++;
                    while (pos < len && expr.charAt(pos) != '"') {
                        if (expr.charAt(pos) == '\\' && pos + 1 < len) pos++;
                        pos++;
                    }
                }
                pos++;
            }
            if (bracketDepth == 0) {
                String listExpr = expr.substring(start, pos);
                try {
                    return parseListOrPropList(listExpr.substring(1, listExpr.length() - 1).trim(), vm);
                } catch (Exception ignored) {
                    return Datum.VOID;
                }
            }
            return Datum.VOID;
        }

        // Try to parse an identifier
        if (Character.isLetter(first) || first == '_') {
            int start = pos;
            while (pos < len && (Character.isLetterOrDigit(expr.charAt(pos)) || expr.charAt(pos) == '_')) {
                pos++;
            }
            String identifier = expr.substring(start, pos);

            // Check global variable
            Datum globalValue = vm.getGlobal(identifier);
            if (!globalValue.isVoid()) {
                return globalValue;
            }

            // Try to find and call a handler
            HandlerRef ref = vm.findHandler(identifier);
            if (ref != null) {
                return vm.executeHandler(ref.script(), ref.handler(), List.of(), null);
            }

            // Unknown identifier - return VOID
            return Datum.VOID;
        }

        // Couldn't parse anything valid
        return Datum.VOID;
    }

    /**
     * Unescape a string (handle backslash escape sequences).
     */
    private static String unescapeString(String s) {
        if (!s.contains("\\")) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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
        List<String> elements = splitListElements(content);

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
                    Datum value = parseLingoExpressionWithPartial(valueStr, vm);
                    props.put(key, value);
                }
            }
            return Datum.propList(props);
        } else {
            // Parse as linear list
            List<Datum> items = new ArrayList<>();
            for (String element : elements) {
                items.add(parseLingoExpressionWithPartial(element.trim(), vm));
            }
            return Datum.list(items);
        }
    }

    /**
     * Split list content by commas, respecting nested brackets and quoted strings.
     */
    private static List<String> splitListElements(String content) {
        List<String> elements = new ArrayList<>();
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
     *
     * If a list of names is passed, returns the ScriptRef for the FIRST valid script found.
     * This is used in Director for parent script lookup with fallback classes.
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
        } else if (identifier instanceof Datum.Symbol sym) {
            // Find script by symbol name
            if (provider != null) {
                Datum memberRef = provider.getMemberByName(0, sym.name());
                if (memberRef instanceof Datum.CastMemberRef cmr) {
                    return new Datum.ScriptRef(cmr.castLib(), cmr.member());
                }
            }
        } else if (identifier instanceof Datum.Int num) {
            // Find script by number
            // If the number is a slot number (high bits set), decode it
            // Slot number format: (castLib << 16) | (memberNum & 0xFFFF)
            if (provider != null) {
                int value = num.value();
                int castLib, memberNum;
                if (value > 65535) {
                    // This is a slot number - decode it
                    castLib = value >> 16;
                    memberNum = value & 0xFFFF;
                } else {
                    // Regular member number - assume cast lib 1
                    castLib = 1;
                    memberNum = value;
                }
                return new Datum.ScriptRef(castLib, memberNum);
            }
        } else if (identifier instanceof Datum.CastMemberRef cmr) {
            // Already a cast member reference
            return new Datum.ScriptRef(cmr.castLib(), cmr.member());
        } else if (identifier instanceof Datum.List list) {
            // List of script names - return the first valid one found
            // This is used for class hierarchies like ["Manager Template Class", "Variable Container Class"]
            for (Datum item : list.items()) {
                Datum result = script(vm, List.of(item));
                if (!result.isVoid()) {
                    return result;
                }
            }
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
            case Datum.ImageRef ir -> "image";
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
     * Returns TRUE if value is a list (linear list or property list).
     * Matches dirplayer-rs: both Datum::List and Datum::PropList return true.
     */
    private static Datum listp(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.FALSE;
        }
        Datum value = args.get(0);
        return (value instanceof Datum.List || value instanceof Datum.PropList) ? Datum.TRUE : Datum.FALSE;
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
     * symbol(value)
     * Converts a string to a symbol. Matches dirplayer-rs behavior:
     * - If already a symbol, returns it unchanged
     * - If a string starting with "#", returns Symbol("#")
     * - If an empty string, returns Symbol("")
     * - Otherwise, creates Symbol from the string content
     */
    private static Datum symbol(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }
        Datum arg = args.get(0);
        if (arg instanceof Datum.Symbol) {
            return arg;
        }
        if (arg instanceof Datum.Str str) {
            String s = str.value();
            if (s.isEmpty()) {
                return Datum.symbol("");
            }
            if (s.startsWith("#")) {
                return Datum.symbol("#");
            }
            return Datum.symbol(s);
        }
        return Datum.VOID;
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
