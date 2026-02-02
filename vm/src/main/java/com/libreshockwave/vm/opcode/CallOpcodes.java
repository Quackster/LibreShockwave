package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.CastLibProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function call opcodes.
 */
public final class CallOpcodes {

    private CallOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.LOCAL_CALL, CallOpcodes::localCall);
        handlers.put(Opcode.EXT_CALL, CallOpcodes::extCall);
        handlers.put(Opcode.OBJ_CALL, CallOpcodes::objCall);
    }

    private static boolean localCall(ExecutionContext ctx) {
        ScriptChunk.Handler targetHandler = ctx.findLocalHandler(ctx.getArgument());
        if (targetHandler != null) {
            Datum argListDatum = ctx.pop();
            boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
            List<Datum> args = getArgs(argListDatum);
            Datum result = ctx.executeHandler(ctx.getScript(), targetHandler, args, ctx.getReceiver());
            if (!noRet) {
                ctx.push(result);
            }
        }
        return true;
    }

    private static boolean extCall(ExecutionContext ctx) {
        String handlerName = ctx.resolveName(ctx.getArgument());
        Datum argListDatum = ctx.pop();
        boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = getArgs(argListDatum);

        Datum result;
        if (ctx.isBuiltin(handlerName)) {
            result = ctx.invokeBuiltin(handlerName, args);
        } else {
            HandlerRef ref = ctx.findHandler(handlerName);
            if (ref != null) {
                result = ctx.executeHandler(ref.script(), ref.handler(), args, null);
            } else {
                result = Datum.VOID;
            }
        }
        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    private static boolean objCall(ExecutionContext ctx) {
        if (!ctx.getScope().getScript().getScriptName().equalsIgnoreCase("String Services API")) {
            if (ctx.getArgument() == 58) {
                var t = 3;
            }
        }
        String methodName = ctx.resolveName(ctx.getArgument());
        Datum argListDatum = ctx.pop();
        boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = getArgs(argListDatum);
        Datum target = args.isEmpty() ? Datum.VOID : args.remove(0);

        Datum result = dispatchMethod(ctx, target, methodName, args);

        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    /**
     * Dispatch a method call to the appropriate handler based on target type.
     */
    private static Datum dispatchMethod(ExecutionContext ctx, Datum target,
                                        String methodName, List<Datum> args) {
        return switch (target) {
            case Datum.List list -> handleListMethod(list, methodName, args);
            case Datum.PropList propList -> handlePropListMethod(propList, methodName, args);
            case Datum.ScriptInstance instance -> handleScriptInstanceMethod(ctx, instance, methodName, args);
            case Datum.ScriptRef scriptRef -> handleScriptRefMethod(ctx, scriptRef, methodName, args);
            case Datum.Point point -> handlePointMethod(point, methodName, args);
            case Datum.Rect rect -> handleRectMethod(rect, methodName, args);
            case Datum.Str str -> handleStringMethod(str, methodName, args);
            default -> {
                // Try to find the method as a global handler (with target as first arg)
                if (ctx.isBuiltin(methodName)) {
                    List<Datum> fullArgs = new ArrayList<>();
                    fullArgs.add(target);
                    fullArgs.addAll(args);
                    yield ctx.invokeBuiltin(methodName, fullArgs);
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Handle method calls on linear lists.
     */
    private static Datum handleListMethod(Datum.List list, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "count" -> {
                // count(list) or count(list, #item)
                yield Datum.of(list.items().size());
            }
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1; // 1-indexed
                if (index >= 0 && index < list.items().size()) {
                    yield list.items().get(index);
                }
                yield Datum.VOID;
            }
            case "setat" -> {
                if (args.size() < 2) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                if (index >= 0 && index < list.items().size()) {
                    list.items().set(index, args.get(1));
                }
                yield Datum.VOID;
            }
            case "append", "add" -> {
                if (args.isEmpty()) yield Datum.VOID;
                list.items().add(args.get(0));
                yield Datum.VOID;
            }
            case "deleteat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                if (index >= 0 && index < list.items().size()) {
                    list.items().remove(index);
                }
                yield Datum.VOID;
            }
            case "getone" -> {
                // Find index of value
                if (args.isEmpty()) yield Datum.ZERO;
                Datum value = args.get(0);
                for (int i = 0; i < list.items().size(); i++) {
                    if (list.items().get(i).equals(value)) {
                        yield Datum.of(i + 1);
                    }
                }
                yield Datum.ZERO;
            }
            case "sort" -> {
                // Sort list in place (simple implementation)
                list.items().sort((a, b) -> {
                    if (a instanceof Datum.Int ai && b instanceof Datum.Int bi) {
                        return Integer.compare(ai.value(), bi.value());
                    }
                    return a.toStr().compareToIgnoreCase(b.toStr());
                });
                yield Datum.VOID;
            }
            case "duplicate" -> {
                yield new Datum.List(new ArrayList<>(list.items()));
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on property lists.
     */
    private static Datum handlePropListMethod(Datum.PropList propList, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "count" -> Datum.of(propList.properties().size());
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                var entries = new ArrayList<>(propList.properties().entrySet());
                if (index >= 0 && index < entries.size()) {
                    yield entries.get(index).getValue();
                }
                yield Datum.VOID;
            }
            case "getprop", "getaprop" -> {
                if (args.isEmpty()) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                yield propList.properties().getOrDefault(key, Datum.VOID);
            }
            case "setprop", "setaprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().put(key, args.get(1));
                yield Datum.VOID;
            }
            case "addprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().put(key, args.get(1));
                yield Datum.VOID;
            }
            case "deleteprop" -> {
                if (args.isEmpty()) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().remove(key);
                yield Datum.VOID;
            }
            case "getpropat" -> {
                // Get the key at position
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                var keys = new ArrayList<>(propList.properties().keySet());
                if (index >= 0 && index < keys.size()) {
                    yield Datum.symbol(keys.get(index));
                }
                yield Datum.VOID;
            }
            case "setat" -> {
                // setAt(propList, key, value) - add or update a property
                if (args.size() < 2) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().put(key, args.get(1));
                yield Datum.VOID;
            }
            case "findpos" -> {
                // Find position of key
                if (args.isEmpty()) yield Datum.ZERO;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                int pos = 1;
                for (String k : propList.properties().keySet()) {
                    if (k.equalsIgnoreCase(key)) {
                        yield Datum.of(pos);
                    }
                    pos++;
                }
                yield Datum.ZERO;
            }
            case "duplicate" -> {
                yield new Datum.PropList(new LinkedHashMap<>(propList.properties()));
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on script instances.
     * Dispatches to handlers defined in the script.
     */
    private static Datum handleScriptInstanceMethod(ExecutionContext ctx, Datum.ScriptInstance instance,
                                                    String methodName, List<Datum> args) {
        // Look up the script reference from the instance properties
        Datum scriptRefDatum = instance.properties().get("__scriptRef__");

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }

        // Find the handler in the script
        var location = provider.findHandler(methodName);
        if (location != null && location.script() != null && location.handler() != null) {
            if (location.script() instanceof ScriptChunk script
                    && location.handler() instanceof ScriptChunk.Handler handler) {
                // Execute with instance as receiver
                return ctx.executeHandler(script, handler, args, instance);
            }
        }

        // Check if the method is getting a property
        String prop = methodName.toLowerCase();
        if (instance.properties().containsKey(prop)) {
            return instance.properties().get(prop);
        }

        return Datum.VOID;
    }

    /**
     * Handle method calls on script references (e.g., calling new() on a script).
     */
    private static Datum handleScriptRefMethod(ExecutionContext ctx, Datum.ScriptRef scriptRef,
                                               String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        if ("new".equals(method)) {
            // Create a new instance of the script
            List<Datum> fullArgs = new ArrayList<>();
            fullArgs.add(scriptRef);
            fullArgs.addAll(args);
            return ctx.invokeBuiltin("new", fullArgs);
        }
        return Datum.VOID;
    }

    /**
     * Handle method calls on point values.
     */
    private static Datum handlePointMethod(Datum.Point point, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(point.x());
                    case 2 -> Datum.of(point.y());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on rect values.
     */
    private static Datum handleRectMethod(Datum.Rect rect, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(rect.left());
                    case 2 -> Datum.of(rect.top());
                    case 3 -> Datum.of(rect.right());
                    case 4 -> Datum.of(rect.bottom());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on string values.
     * Supports Lingo string chunk operations like count, getProp, getPropRef.
     */
    private static Datum handleStringMethod(Datum.Str str, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        // Get the item delimiter (default comma)
        char itemDelimiter = getItemDelimiter();

        return switch (method) {
            case "length" -> Datum.of(str.value().length());
            case "char" -> {
                if (args.isEmpty()) yield Datum.EMPTY_STRING;
                int index = args.get(0).toInt();
                if (index >= 1 && index <= str.value().length()) {
                    yield Datum.of(String.valueOf(str.value().charAt(index - 1)));
                }
                yield Datum.EMPTY_STRING;
            }
            case "count" -> {
                // count(str, #char) or count(str, #word) etc.
                if (args.isEmpty()) yield Datum.of(str.value().length());
                Datum chunkType = args.get(0);
                if (chunkType instanceof Datum.Symbol s) {
                    String type = s.name().toLowerCase();
                    yield switch (type) {
                        case "char" -> Datum.of(str.value().length());
                        case "word" -> Datum.of(countWords(str.value()));
                        case "line" -> Datum.of(countLines(str.value()));
                        case "item" -> Datum.of(countItems(str.value(), itemDelimiter));
                        default -> Datum.of(str.value().length());
                    };
                }
                yield Datum.of(str.value().length());
            }
            case "getpropref" -> {
                // getPropRef(str, #chunkType, index) - gets a single chunk from string
                // e.g., getPropRef(str, #item, 1) gets the first item
                // e.g., getPropRef(str, #word, 2) gets the second word
                if (args.size() < 2) yield Datum.EMPTY_STRING;

                Datum chunkType = args.get(0);
                int index = args.get(1).toInt();

                if (!(chunkType instanceof Datum.Symbol s)) {
                    yield Datum.EMPTY_STRING;
                }

                String type = s.name().toLowerCase();
                String result = getStringChunk(str.value(), type, index, index, itemDelimiter);
                yield Datum.of(result);
            }
            case "getprop" -> {
                // getProp(str, #chunkType, startIndex, endIndex?)
                // e.g., getProp(str, #char, 1, 5) gets chars 1-5
                // e.g., getProp(str, #word, 1, count(str, #word)) gets word 1 to last
                if (args.size() < 2) yield Datum.EMPTY_STRING;

                Datum chunkType = args.get(0);
                int startIndex = args.get(1).toInt();
                int endIndex = args.size() >= 3 ? args.get(2).toInt() : startIndex;

                if (!(chunkType instanceof Datum.Symbol s)) {
                    yield Datum.EMPTY_STRING;
                }

                String type = s.name().toLowerCase();
                String result = getStringChunk(str.value(), type, startIndex, endIndex, itemDelimiter);
                yield Datum.of(result);
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Get a chunk from a string.
     */
    private static String getStringChunk(String str, String chunkType, int start, int end, char itemDelimiter) {
        if (str.isEmpty() || start < 1) return "";

        return switch (chunkType) {
            case "char" -> {
                int s = Math.max(0, start - 1);
                int e = Math.min(str.length(), end);
                if (s >= str.length() || s >= e) yield "";
                yield str.substring(s, e);
            }
            case "word" -> {
                String[] words = str.trim().split("\\s+");
                if (start > words.length) yield "";
                int s = start - 1;
                int e = Math.min(words.length, end);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(words[i]);
                }
                yield sb.toString();
            }
            case "line" -> {
                String[] lines = str.split("\r\n|\r|\n", -1);
                if (start > lines.length) yield "";
                int s = start - 1;
                int e = Math.min(lines.length, end);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i]);
                }
                yield sb.toString();
            }
            case "item" -> {
                String[] items = str.split(String.valueOf(itemDelimiter), -1);
                if (start > items.length) yield "";
                int s = start - 1;
                int e = Math.min(items.length, end);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append(itemDelimiter);
                    sb.append(items[i]);
                }
                yield sb.toString();
            }
            default -> "";
        };
    }

    /**
     * Get the current item delimiter from MoviePropertyProvider.
     */
    private static char getItemDelimiter() {
        var provider = com.libreshockwave.vm.builtin.MoviePropertyProvider.getProvider();
        if (provider != null) {
            return provider.getItemDelimiter();
        }
        return ',';
    }

    private static int countWords(String str) {
        if (str.isEmpty()) return 0;
        return str.trim().split("\\s+").length;
    }

    private static int countLines(String str) {
        if (str.isEmpty()) return 0;
        return str.split("\r\n|\r|\n", -1).length;
    }

    private static int countItems(String str, char delimiter) {
        if (str.isEmpty()) return 0;
        // Split and count - handle consecutive delimiters
        return str.split(String.valueOf(delimiter), -1).length;
    }

    /**
     * Extract arguments from an arglist datum.
     * Arguments are stored directly in the ArgList/ArgListNoRet items.
     */
    private static List<Datum> getArgs(Datum argListDatum) {
        if (argListDatum instanceof Datum.ArgList al) {
            return new java.util.ArrayList<>(al.items());
        } else if (argListDatum instanceof Datum.ArgListNoRet al) {
            return new java.util.ArrayList<>(al.items());
        } else {
            // Fallback - shouldn't happen with correct bytecode
            return new java.util.ArrayList<>();
        }
    }
}
