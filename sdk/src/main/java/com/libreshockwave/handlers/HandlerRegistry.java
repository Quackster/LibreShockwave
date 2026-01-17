package com.libreshockwave.handlers;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.vm.LingoVM;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry for all built-in Lingo handlers.
 * Registers handlers from all handler modules with the VM.
 */
public class HandlerRegistry {

    /**
     * Register all built-in handlers with the VM.
     */
    public static void registerAll(LingoVM vm) {
        MathHandlers.register(vm);
        StringHandlers.register(vm);
        ListHandlers.register(vm);
        SoundHandlers.register(vm);
        NetworkHandlers.register(vm);
        registerCoreHandlers(vm);
        registerTypeHandlers(vm);
        registerPointRectHandlers(vm);
        registerReferenceHandlers(vm);
        registerBitwiseHandlers(vm);
        registerNavigationHandlers(vm);
        registerCallHandler(vm);
    }

    private static void registerCoreHandlers(LingoVM vm) {
        vm.registerBuiltin("put", HandlerRegistry::put);
        vm.registerBuiltin("alert", HandlerRegistry::alert);
        vm.registerBuiltin("beep", HandlerRegistry::beep);
        vm.registerBuiltin("halt", HandlerRegistry::halt);
        vm.registerBuiltin("nothing", HandlerRegistry::nothing);
        vm.registerBuiltin("pass", HandlerRegistry::nothing);
        vm.registerBuiltin("delay", HandlerRegistry::nothing);
        vm.registerBuiltin("timeout", HandlerRegistry::nothing);
        vm.registerBuiltin("cursor", HandlerRegistry::nothing);
    }

    private static void registerTypeHandlers(LingoVM vm) {
        vm.registerBuiltin("ilk", HandlerRegistry::ilk);
        vm.registerBuiltin("objectP", HandlerRegistry::objectP);
        vm.registerBuiltin("listP", HandlerRegistry::listP);
        vm.registerBuiltin("stringP", HandlerRegistry::stringP);
        vm.registerBuiltin("symbolP", HandlerRegistry::symbolP);
        vm.registerBuiltin("integerP", HandlerRegistry::integerP);
        vm.registerBuiltin("floatP", HandlerRegistry::floatP);
        vm.registerBuiltin("voidP", HandlerRegistry::voidP);
        vm.registerBuiltin("value", HandlerRegistry::value);
        vm.registerBuiltin("symbol", HandlerRegistry::symbol);
    }

    private static void registerPointRectHandlers(LingoVM vm) {
        vm.registerBuiltin("point", HandlerRegistry::point);
        vm.registerBuiltin("rect", HandlerRegistry::rect);
        vm.registerBuiltin("union", HandlerRegistry::union);
        vm.registerBuiltin("intersect", HandlerRegistry::intersect);
        vm.registerBuiltin("inside", HandlerRegistry::inside);
        vm.registerBuiltin("map", HandlerRegistry::map);
    }

    private static void registerReferenceHandlers(LingoVM vm) {
        vm.registerBuiltin("castLib", HandlerRegistry::castLib);
        vm.registerBuiltin("member", HandlerRegistry::member);
        vm.registerBuiltin("sprite", HandlerRegistry::sprite);
        vm.registerBuiltin("sound", HandlerRegistry::sound);
        vm.registerBuiltin("script", HandlerRegistry::script);
    }

    private static void registerBitwiseHandlers(LingoVM vm) {
        vm.registerBuiltin("bitAnd", (vm1, args) ->
            args.size() < 2 ? Datum.of(0) : Datum.of(args.get(0).intValue() & args.get(1).intValue()));
        vm.registerBuiltin("bitOr", (vm1, args) ->
            args.size() < 2 ? Datum.of(0) : Datum.of(args.get(0).intValue() | args.get(1).intValue()));
        vm.registerBuiltin("bitXor", (vm1, args) ->
            args.size() < 2 ? Datum.of(0) : Datum.of(args.get(0).intValue() ^ args.get(1).intValue()));
        vm.registerBuiltin("bitNot", (vm1, args) ->
            args.isEmpty() ? Datum.of(0) : Datum.of(~args.get(0).intValue()));
    }

    private static void registerNavigationHandlers(LingoVM vm) {
        vm.registerBuiltin("go", (vm1, args) -> {
            if (!args.isEmpty()) System.out.println("[go] frame=" + args.get(0).stringValue());
            return Datum.voidValue();
        });
        vm.registerBuiltin("play", (vm1, args) -> {
            System.out.println("[play] " + (args.isEmpty() ? "" : args.get(0).stringValue()));
            return Datum.voidValue();
        });
        vm.registerBuiltin("updateStage", (vm1, args) -> Datum.voidValue());
        vm.registerBuiltin("puppetTempo", (vm1, args) -> {
            if (!args.isEmpty()) System.out.println("[puppetTempo] " + args.get(0).intValue() + " fps");
            return Datum.voidValue();
        });
        vm.registerBuiltin("moveToFront", (vm1, args) -> Datum.voidValue());
        vm.registerBuiltin("puppetSound", (vm1, args) -> Datum.voidValue());
    }

    private static void registerCallHandler(LingoVM vm) {
        vm.registerBuiltin("call", HandlerRegistry::call);
    }

    // Core handlers

    private static Datum put(LingoVM vm, List<Datum> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(args.get(i).stringValue());
        }
        System.out.println(sb);
        return Datum.voidValue();
    }

    private static Datum alert(LingoVM vm, List<Datum> args) {
        if (!args.isEmpty()) System.out.println("[ALERT] " + args.get(0).stringValue());
        return Datum.voidValue();
    }

    private static Datum beep(LingoVM vm, List<Datum> args) {
        System.out.println("[BEEP]");
        return Datum.voidValue();
    }

    private static Datum halt(LingoVM vm, List<Datum> args) {
        vm.halt();
        return Datum.voidValue();
    }

    private static Datum nothing(LingoVM vm, List<Datum> args) {
        return Datum.voidValue();
    }

    // Type checking handlers

    private static Datum ilk(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.symbol("void");
        Datum d = args.get(0);

        if (args.size() > 1) {
            String checkType = args.get(1).stringValue().toLowerCase();
            return switch (checkType) {
                case "integer" -> d.isInt() ? Datum.TRUE : Datum.FALSE;
                case "float" -> d.isFloat() ? Datum.TRUE : Datum.FALSE;
                case "string" -> d.isString() ? Datum.TRUE : Datum.FALSE;
                case "symbol" -> d.isSymbol() ? Datum.TRUE : Datum.FALSE;
                case "list" -> d instanceof Datum.DList ? Datum.TRUE : Datum.FALSE;
                case "proplist" -> d instanceof Datum.PropList ? Datum.TRUE : Datum.FALSE;
                case "point" -> d instanceof Datum.IntPoint ? Datum.TRUE : Datum.FALSE;
                case "rect" -> d instanceof Datum.IntRect ? Datum.TRUE : Datum.FALSE;
                case "void" -> d.isVoid() ? Datum.TRUE : Datum.FALSE;
                default -> Datum.FALSE;
            };
        }

        return switch (d) {
            case Datum.Int i -> Datum.symbol("integer");
            case Datum.DFloat f -> Datum.symbol("float");
            case Datum.Str s -> Datum.symbol("string");
            case Datum.Symbol sym -> Datum.symbol("symbol");
            case Datum.DList l -> Datum.symbol("list");
            case Datum.PropList p -> Datum.symbol("propList");
            case Datum.IntPoint p -> Datum.symbol("point");
            case Datum.IntRect r -> Datum.symbol("rect");
            case Datum.Void v -> Datum.symbol("void");
            default -> Datum.symbol("object");
        };
    }

    private static Datum objectP(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.FALSE :
            args.get(0) instanceof Datum.ScriptInstanceRef ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum listP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        Datum d = args.get(0);
        return d instanceof Datum.DList || d instanceof Datum.PropList ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum stringP(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.FALSE : args.get(0).isString() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum symbolP(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.FALSE : args.get(0).isSymbol() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum integerP(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.FALSE : args.get(0).isInt() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum floatP(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.FALSE : args.get(0) instanceof Datum.DFloat ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum voidP(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.TRUE : args.get(0).isVoid() ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * Parse a Lingo literal string into a Datum.
     * Supports: lists, proplists, strings, symbols, integers, floats, VOID, TRUE, FALSE.
     * Example: value("[\"Object Manager Class\"]") -> list with one string element
     */
    private static Datum value(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        String s = args.get(0).stringValue().trim();
        if (s.isEmpty()) return Datum.voidValue();
        return parseLingoLiteral(s, 0).datum;
    }

    private record ParseResult(Datum datum, int endPos) {}

    private static ParseResult parseLingoLiteral(String s, int start) {
        // Skip whitespace
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
        if (start >= s.length()) return new ParseResult(Datum.voidValue(), start);

        char c = s.charAt(start);

        // List or PropList: [...]
        if (c == '[') {
            return parseListOrPropList(s, start);
        }

        // Quoted string: "..."
        if (c == '"') {
            return parseString(s, start);
        }

        // Symbol: #name
        if (c == '#') {
            return parseSymbol(s, start);
        }

        // Number (integer or float) or keyword
        return parseNumberOrKeyword(s, start);
    }

    private static ParseResult parseListOrPropList(String s, int start) {
        // Skip '['
        int pos = start + 1;

        // Skip whitespace
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;

        // Empty list/proplist check
        if (pos < s.length() && s.charAt(pos) == ']') {
            return new ParseResult(Datum.list(), pos + 1);
        }

        // Check for proplist marker ':'
        if (pos < s.length() && s.charAt(pos) == ':') {
            // Empty proplist [:]
            pos++;
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == ']') {
                return new ParseResult(Datum.propList(), pos + 1);
            }
        }

        // Parse first element to determine if list or proplist
        List<Datum> items = new ArrayList<>();
        Datum.PropList propList = null;
        boolean isPropList = false;

        while (pos < s.length()) {
            // Skip whitespace
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos >= s.length()) break;

            // Check for end
            if (s.charAt(pos) == ']') {
                pos++;
                break;
            }

            // Parse key/value
            ParseResult keyResult = parseLingoLiteral(s, pos);
            pos = keyResult.endPos;

            // Skip whitespace
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;

            // Check for ':' (proplist)
            if (pos < s.length() && s.charAt(pos) == ':') {
                if (!isPropList && items.isEmpty()) {
                    isPropList = true;
                    propList = Datum.propList();
                }
                pos++; // skip ':'

                // Parse value
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
                ParseResult valResult = parseLingoLiteral(s, pos);
                pos = valResult.endPos;

                if (propList != null) {
                    propList.put(keyResult.datum, valResult.datum);
                }
            } else {
                // Regular list item
                items.add(keyResult.datum);
            }

            // Skip whitespace and comma
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos < s.length() && s.charAt(pos) == ',') pos++;
        }

        if (isPropList && propList != null) {
            return new ParseResult(propList, pos);
        } else {
            Datum.DList list = Datum.list();
            for (Datum item : items) {
                list.add(item);
            }
            return new ParseResult(list, pos);
        }
    }

    private static ParseResult parseString(String s, int start) {
        StringBuilder sb = new StringBuilder();
        int pos = start + 1; // skip opening quote

        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '"') {
                pos++;
                break;
            }
            if (c == '\\' && pos + 1 < s.length()) {
                pos++;
                c = s.charAt(pos);
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
            pos++;
        }

        return new ParseResult(Datum.of(sb.toString()), pos);
    }

    private static ParseResult parseSymbol(String s, int start) {
        int pos = start + 1; // skip '#'
        StringBuilder sb = new StringBuilder();

        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
                pos++;
            } else {
                break;
            }
        }

        return new ParseResult(Datum.symbol(sb.toString()), pos);
    }

    private static ParseResult parseNumberOrKeyword(String s, int start) {
        int pos = start;
        StringBuilder sb = new StringBuilder();

        // Handle negative numbers
        if (pos < s.length() && s.charAt(pos) == '-') {
            sb.append('-');
            pos++;
        }

        // Collect digits and decimal point
        boolean hasDecimal = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isDigit(c)) {
                sb.append(c);
                pos++;
            } else if (c == '.' && !hasDecimal) {
                hasDecimal = true;
                sb.append(c);
                pos++;
            } else if (Character.isLetter(c) || c == '_') {
                // It's a keyword/identifier
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                    sb.append(s.charAt(pos));
                    pos++;
                }
                break;
            } else {
                break;
            }
        }

        String token = sb.toString();
        if (token.isEmpty()) {
            return new ParseResult(Datum.voidValue(), pos);
        }

        // Check for keywords
        return switch (token.toUpperCase()) {
            case "VOID" -> new ParseResult(Datum.voidValue(), pos);
            case "TRUE", "1" -> new ParseResult(Datum.TRUE, pos);
            case "FALSE", "0" -> {
                // Only treat as boolean if it's the keyword, not just digit
                if (token.equalsIgnoreCase("FALSE")) {
                    yield new ParseResult(Datum.FALSE, pos);
                }
                yield new ParseResult(Datum.of(0), pos);
            }
            case "EMPTY" -> new ParseResult(Datum.of(""), pos);
            default -> {
                // Try parsing as number
                try {
                    if (hasDecimal) {
                        yield new ParseResult(Datum.of(Float.parseFloat(token)), pos);
                    } else {
                        yield new ParseResult(Datum.of(Integer.parseInt(token)), pos);
                    }
                } catch (NumberFormatException e) {
                    // Unknown identifier returns VOID per Lingo spec
                    yield new ParseResult(Datum.voidValue(), pos);
                }
            }
        };
    }

    private static Datum symbol(LingoVM vm, List<Datum> args) {
        return args.isEmpty() ? Datum.symbol("") : Datum.symbol(args.get(0).stringValue());
    }

    // Point/Rect handlers

    private static Datum point(LingoVM vm, List<Datum> args) {
        int x = args.size() > 0 ? args.get(0).intValue() : 0;
        int y = args.size() > 1 ? args.get(1).intValue() : 0;
        return new Datum.IntPoint(x, y);
    }

    private static Datum rect(LingoVM vm, List<Datum> args) {
        int left = args.size() > 0 ? args.get(0).intValue() : 0;
        int top = args.size() > 1 ? args.get(1).intValue() : 0;
        int right = args.size() > 2 ? args.get(2).intValue() : 0;
        int bottom = args.size() > 3 ? args.get(3).intValue() : 0;
        return new Datum.IntRect(left, top, right, bottom);
    }

    private static Datum union(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return new Datum.IntRect(0, 0, 0, 0);
        if (args.get(0) instanceof Datum.IntRect r1 && args.get(1) instanceof Datum.IntRect r2) {
            return new Datum.IntRect(
                Math.min(r1.left(), r2.left()), Math.min(r1.top(), r2.top()),
                Math.max(r1.right(), r2.right()), Math.max(r1.bottom(), r2.bottom()));
        }
        return new Datum.IntRect(0, 0, 0, 0);
    }

    private static Datum intersect(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return new Datum.IntRect(0, 0, 0, 0);
        if (args.get(0) instanceof Datum.IntRect r1 && args.get(1) instanceof Datum.IntRect r2) {
            int left = Math.max(r1.left(), r2.left());
            int top = Math.max(r1.top(), r2.top());
            int right = Math.min(r1.right(), r2.right());
            int bottom = Math.min(r1.bottom(), r2.bottom());
            if (right > left && bottom > top) return new Datum.IntRect(left, top, right, bottom);
        }
        return new Datum.IntRect(0, 0, 0, 0);
    }

    private static Datum inside(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.FALSE;
        if (args.get(0) instanceof Datum.IntPoint p && args.get(1) instanceof Datum.IntRect r) {
            return p.x() >= r.left() && p.x() < r.right() && p.y() >= r.top() && p.y() < r.bottom()
                ? Datum.TRUE : Datum.FALSE;
        }
        return Datum.FALSE;
    }

    private static Datum map(LingoVM vm, List<Datum> args) {
        return new Datum.IntPoint(0, 0);
    }

    // Reference handlers

    private static Datum castLib(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        return new Datum.CastLibRef(args.get(0).intValue());
    }

    private static Datum member(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        int castLib = args.size() >= 2 ? args.get(1).intValue() : 1;
        return new Datum.CastMemberRef(args.get(0).intValue(), castLib);
    }

    private static Datum sprite(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        return new Datum.SpriteRef(args.get(0).intValue());
    }

    private static Datum sound(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        return new Datum.SoundRef(args.get(0).intValue());
    }

    // Call handler - delegates to VM for script resolution
    private static Datum call(LingoVM vm, List<Datum> args) {
        return vm.callHandler(args);
    }

    /**
     * Get a reference to a script by name, number, or member reference.
     * Matches dirplayer-rs MovieHandlers::script().
     */
    private static Datum script(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();

        CastManager castManager = vm.getCastManager();
        if (castManager == null) return Datum.voidValue();

        Datum identifier = args.get(0);
        Datum.CastMemberRef memberRef = null;

        // Look up by name, number, or use existing ref
        if (identifier.isString()) {
            memberRef = castManager.findMemberRefByName(identifier.stringValue());
        } else if (identifier.isInt()) {
            memberRef = castManager.findMemberRefByNumber(identifier.intValue());
        } else if (identifier instanceof Datum.CastMemberRef ref) {
            memberRef = ref;
        }

        if (memberRef == null) {
            return Datum.voidValue();  // Script not found
        }

        // Verify it's actually a script
        ScriptChunk script = castManager.getScriptByRef(memberRef);
        if (script == null) {
            return Datum.voidValue();  // Not a script member
        }

        return new Datum.ScriptRef(memberRef);
    }
}
