package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;

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
        builtins.put("callancestor", TypeBuiltins::callAncestor);
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

        // Debug: trace value() calls for manager/class configs
        String argStr = arg.toString();
        if (argStr.contains("CastLoad") || argStr.contains("Manager Class")) {
            System.err.println("[value] called with: " + arg + " (type=" + arg.getClass().getSimpleName() + ")");
        }

        // For non-strings, return as-is (matches dirplayer-rs behavior)
        if (!(arg instanceof Datum.Str str)) {
            // Debug: trace when value() receives a list (already parsed)
            if (arg instanceof Datum.List l && l.items().size() == 2) {
                System.err.println("[value] received 2-elem list: " + l + " (returning as-is)");
            }
            return arg;
        }

        // For strings, try to evaluate as a Lingo expression
        String expr = str.value().trim();

        // Empty string -> VOID
        if (expr.isEmpty()) {
            return Datum.VOID;
        }

        try {
            Datum result = parseLingoExpression(expr, vm);
            // Debug: trace value() results for manager/class configs
            if (expr.contains("CastLoad") || expr.contains("Manager Class")) {
                System.err.println("[value] parsed '" + expr + "' -> " + result + " (type=" + result.getClass().getSimpleName() + ")");
            }
            return result;
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
                Datum parsed = parseLingoExpression(element.trim(), vm);
                System.err.println("[value] parsed list element '" + element.trim() + "' -> " + parsed);
                items.add(parsed);
            }
            Datum result = Datum.list(items);
            System.err.println("[value] created list @" + System.identityHashCode(result) + ": " + result);
            return result;
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

        // Debug: trace script() calls
        String idStr = identifier.toString();
        if (idStr.contains("CastLoad") || idStr.contains("Manager")) {
            System.err.println("[script] called with: " + identifier + " (type=" + identifier.getClass().getSimpleName() + ")");
        }

        if (identifier instanceof Datum.Str str) {
            // Find script by name
            if (provider != null) {
                Datum memberRef = provider.getMemberByName(0, str.value());
                if (memberRef instanceof Datum.CastMemberRef cmr) {
                    System.err.println("[script] '" + str.value() + "' -> member(" + cmr.member() + ", " + cmr.castLib() + ")");
                    return new Datum.ScriptRef(cmr.castLib(), cmr.member());
                } else {
                    // Debug: member not found
                    if (str.value().contains("CastLoad") || str.value().contains("Manager")) {
                        System.err.println("[script] '" + str.value() + "' NOT FOUND (returned " + memberRef + ")");
                    }
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
            System.err.println("[script] processing list: " + list);
            for (Datum item : list.items()) {
                System.err.println("[script] trying list item: " + item);
                Datum result = script(vm, List.of(item));
                if (!result.isVoid()) {
                    System.err.println("[script] list item found: " + item + " -> " + result);
                    return result;
                }
            }
            System.err.println("[script] no items in list found, returning VOID");
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

    /**
     * callAncestor(#handler, me, arg1, arg2, ...)
     * Calls a handler on the ancestor of the given script instance.
     *
     * In Director, callAncestor:
     * 1. Finds the ancestor of the 'me' argument (args[1])
     * 2. Looks up the handler in the ancestor's SCRIPT
     * 3. Executes the handler with 'me' still being the ORIGINAL instance
     *
     * When inside an ancestor's handler (due to callAncestor), a nested callAncestor
     * should use the CURRENT SCOPE's script to determine which ancestor to call next.
     */
    private static Datum callAncestor(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) {
            return Datum.VOID;
        }

        // Get handler name from first argument (symbol or string)
        Datum handlerArg = args.get(0);
        String handlerName;
        if (handlerArg instanceof Datum.Symbol sym) {
            handlerName = sym.name();
        } else {
            handlerName = handlerArg.toStr();
        }

        // Get the script instance (me)
        Datum meArg = args.get(1);
        if (!(meArg instanceof Datum.ScriptInstance instance)) {
            // Also handle lists of instances
            if (meArg instanceof Datum.List list) {
                Datum result = Datum.VOID;
                for (Datum item : list.items()) {
                    if (item instanceof Datum.ScriptInstance) {
                        List<Datum> newArgs = new java.util.ArrayList<>();
                        newArgs.add(handlerArg);
                        newArgs.add(item);
                        newArgs.addAll(args.subList(2, args.size()));
                        result = callAncestor(vm, newArgs);
                    }
                }
                return result;
            }
            return Datum.VOID;
        }

        // Find the ancestor
        // If we're already executing in an ancestor's script, we need to find
        // which level of the ancestor chain we're at and get THAT instance's ancestor
        Datum ancestor = findAncestorForCall(vm, instance);
        if (ancestor == null || ancestor.isVoid()) {
            return Datum.VOID;
        }

        if (!(ancestor instanceof Datum.ScriptInstance ancestorInstance)) {
            return Datum.VOID;
        }

        // Look up the handler in the ancestor's script (or its ancestors)
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }

        // Walk the ancestor chain to find a script with the handler
        Datum.ScriptInstance currentAncestor = ancestorInstance;
        CastLibProvider.HandlerLocation location = null;

        for (int i = 0; i < 100; i++) { // Safety limit
            location = provider.findHandlerInScript(currentAncestor.scriptId(), handlerName);
            if (location != null) {
                break;
            }
            // Try next ancestor
            Datum nextAncestor = currentAncestor.properties().get("ancestor");
            if (nextAncestor instanceof Datum.ScriptInstance next) {
                currentAncestor = next;
            } else {
                break;
            }
        }

        if (location == null || location.script() == null || location.handler() == null) {
            return Datum.VOID;
        }

        // Build call arguments (me + remaining args)
        List<Datum> callArgs = new java.util.ArrayList<>();
        callArgs.addAll(args.subList(2, args.size()));

        // Execute the handler with original 'me' as receiver
        if (location.script() instanceof com.libreshockwave.chunks.ScriptChunk script
                && location.handler() instanceof com.libreshockwave.chunks.ScriptChunk.Handler handler) {
            return vm.executeHandler(script, handler, callArgs, instance);
        }

        return Datum.VOID;
    }

    /**
     * Find the appropriate ancestor for a callAncestor call.
     * If we're already executing in an ancestor's handler, we need to find
     * which level we're at and return THAT instance's ancestor.
     */
    private static Datum findAncestorForCall(LingoVM vm, Datum.ScriptInstance me) {
        Scope currentScope = vm.getCurrentScope();
        if (currentScope == null) {
            // Not in a handler, just return me's ancestor
            return me.properties().get("ancestor");
        }

        // Get the script we're currently executing in
        int currentScriptId = currentScope.getScript().id();

        // Walk me's ancestor chain to find which instance has the script we're in
        Datum.ScriptInstance walkInstance = me;
        for (int i = 0; i < 100; i++) { // Safety limit
            if (walkInstance.scriptId() == currentScriptId) {
                // Found it - return this instance's ancestor
                Datum ancestor = walkInstance.properties().get("ancestor");
                return ancestor != null ? ancestor : Datum.VOID;
            }
            // Move to next ancestor
            Datum nextAncestor = walkInstance.properties().get("ancestor");
            if (nextAncestor instanceof Datum.ScriptInstance next) {
                walkInstance = next;
            } else {
                break;
            }
        }

        // Fallback: return me's direct ancestor
        Datum ancestor = me.properties().get("ancestor");
        return ancestor != null ? ancestor : Datum.VOID;
    }
}
