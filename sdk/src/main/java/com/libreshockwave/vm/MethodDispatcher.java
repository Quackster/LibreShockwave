package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.lingo.StringChunkType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Method dispatch for various Datum types in the Lingo VM.
 * Handles calling methods on lists, proplists, strings, and other objects.
 */
public class MethodDispatcher {

    /**
     * Callback for executing script handlers.
     */
    @FunctionalInterface
    public interface HandlerExecutor {
        Datum execute(ScriptChunk script, ScriptChunk.Handler handler, Datum[] args);
    }

    private final LingoVM vm;
    private final ScriptResolver resolver;
    private final HandlerExecutor executor;

    public MethodDispatcher(LingoVM vm, ScriptResolver resolver, HandlerExecutor executor) {
        this.vm = vm;
        this.resolver = resolver;
        this.executor = executor;
    }

    /**
     * Call a method on a datum.
     */
    public Datum callMethod(Datum obj, String methodName, List<Datum> args,
                            LingoVM.XtraInstanceCallback xtraCallback) {
        // XtraInstance
        if (obj instanceof Datum.XtraInstance xtra) {
            if (xtraCallback != null) {
                Datum result = xtraCallback.callMethod(xtra.xtraName(), xtra.instanceId(), methodName, args);
                if (result != null) return result;
            }
        }

        // ScriptInstanceRef - call method on script instance
        if (obj instanceof Datum.ScriptInstanceRef instance) {
            return callScriptInstanceMethod(instance, methodName, args);
        }

        // ScriptRef - call static method or create instance
        if (obj instanceof Datum.ScriptRef scriptRef) {
            return callScriptRefMethod(scriptRef, methodName, args);
        }

        // DList
        if (obj instanceof Datum.DList list) {
            return callListMethod(list, methodName, args);
        }

        // PropList
        if (obj instanceof Datum.PropList propList) {
            return callPropListMethod(propList, methodName, args);
        }

        // Stage
        if (obj instanceof Datum.StageRef) {
            return callStageMethod(methodName);
        }

        // String types
        if (obj instanceof Datum.Str str) {
            return callStringMethod(str.value(), methodName, args);
        }
        if (obj instanceof Datum.StringChunk chunk) {
            return callStringMethod(chunk.value(), methodName, args);
        }

        // Try as builtin
        LingoVM.BuiltinHandler builtin = vm.getBuiltin(methodName);
        if (builtin != null) {
            List<Datum> allArgs = new ArrayList<>();
            allArgs.add(obj);
            allArgs.addAll(args);
            return builtin.call(vm, allArgs);
        }

        return Datum.voidValue();
    }

    private Datum callScriptInstanceMethod(Datum.ScriptInstanceRef instance, String methodName, List<Datum> args) {
        ScriptChunk script = null; // resolver.findScriptByName(instance.scriptName());
        if (script != null) {
            for (ScriptChunk.Handler h : script.handlers()) {
                String hName = resolver.getName(h.nameId());
                if (hName != null && hName.equalsIgnoreCase(methodName)) {
                    List<Datum> allArgs = new ArrayList<>();
                    allArgs.add(instance);
                    allArgs.addAll(args);
                    return executor.execute(script, h, allArgs.toArray(new Datum[0]));
                }
            }
        }
        return Datum.voidValue();
    }

    private Datum callScriptRefMethod(Datum.ScriptRef scriptRef, String methodName, List<Datum> args) {
        if ("new".equalsIgnoreCase(methodName)) {
            return vm.createScriptInstance(scriptRef, args);
        }

        ScriptChunk script = resolver.findScriptByCastRef(scriptRef.memberRef());
        if (script != null) {
            for (ScriptChunk.Handler h : script.handlers()) {
                String hName = resolver.getName(h.nameId());
                if (hName != null && hName.equalsIgnoreCase(methodName)) {
                    return executor.execute(script, h, args.toArray(new Datum[0]));
                }
            }
        }
        return Datum.voidValue();
    }

    private Datum callStageMethod(String method) {
        LingoVM.StageCallback stageCallback = vm.getStageCallback();
        return switch (method.toLowerCase()) {
            case "movetofront" -> { if (stageCallback != null) stageCallback.moveToFront(); yield Datum.voidValue(); }
            case "movetoback" -> { if (stageCallback != null) stageCallback.moveToBack(); yield Datum.voidValue(); }
            case "close" -> { if (stageCallback != null) stageCallback.close(); yield Datum.voidValue(); }
            case "forget" -> Datum.voidValue();
            default -> Datum.voidValue();
        };
    }

    private Datum callListMethod(Datum.DList list, String method, List<Datum> args) {
        return switch (method.toLowerCase()) {
            case "count" -> Datum.of(list.count());
            case "getat" -> args.isEmpty() ? Datum.voidValue() : list.getAt(args.get(0).intValue());
            case "setat" -> {
                if (args.size() >= 2) list.setAt(args.get(0).intValue(), args.get(1));
                yield Datum.voidValue();
            }
            case "add", "append" -> {
                if (!args.isEmpty()) list.add(args.get(0));
                yield Datum.voidValue();
            }
            case "deleteat" -> {
                if (!args.isEmpty()) {
                    int idx = args.get(0).intValue();
                    if (idx >= 1 && idx <= list.count()) list.items().remove(idx - 1);
                }
                yield Datum.voidValue();
            }
            case "getlast" -> list.count() > 0 ? list.items().get(list.count() - 1) : Datum.voidValue();
            case "sort" -> {
                list.items().sort((a, b) -> {
                    if (a.isNumber() && b.isNumber()) return Float.compare(a.floatValue(), b.floatValue());
                    return a.stringValue().compareToIgnoreCase(b.stringValue());
                });
                yield Datum.voidValue();
            }
            default -> Datum.voidValue();
        };
    }

    private Datum callPropListMethod(Datum.PropList propList, String method, List<Datum> args) {
        return switch (method.toLowerCase()) {
            case "count" -> Datum.of(propList.count());
            case "getprop", "getaprop", "getat" -> args.isEmpty() ? Datum.voidValue() : propList.get(args.get(0));
            case "setprop", "setaprop", "addprop", "setat" -> {
                if (args.size() >= 2) propList.put(args.get(0), args.get(1));
                yield Datum.voidValue();
            }
            case "deleteprop" -> {
                if (!args.isEmpty()) propList.properties().remove(args.get(0));
                yield Datum.voidValue();
            }
            case "findpos" -> {
                if (args.isEmpty()) yield Datum.of(0);
                int pos = 1;
                for (Datum key : propList.properties().keySet()) {
                    if (key.equals(args.get(0))) yield Datum.of(pos);
                    pos++;
                }
                yield Datum.of(0);
            }
            default -> Datum.voidValue();
        };
    }

    /**
     * Call a method on a string.
     */
    public Datum callStringMethod(String str, String method, List<Datum> args) {
        return switch (method.toLowerCase()) {
            case "count" -> {
                if (args.isEmpty()) yield Datum.of(0);
                String chunkType = args.get(0).stringValue().toLowerCase();
                yield Datum.of(stringGetCount(str, chunkType));
            }
            case "getpropref" -> {
                if (args.size() < 2) yield Datum.voidValue();
                String chunkType = args.get(0).stringValue();
                int start = args.get(1).intValue();
                int end = args.size() > 2 ? args.get(2).intValue() : start;
                yield getStringChunkRef(str, chunkType, start, end);
            }
            case "getprop" -> {
                if (args.size() < 2) yield Datum.voidValue();
                String chunkType = args.get(0).stringValue();
                int start = args.get(1).intValue();
                int end = args.size() > 2 ? args.get(2).intValue() : start;
                Datum chunk = getStringChunkRef(str, chunkType, start, end);
                yield chunk instanceof Datum.StringChunk sc ? Datum.of(sc.value()) : Datum.voidValue();
            }
            case "split" -> {
                String delimiter = args.isEmpty() ? "," : args.get(0).stringValue();
                String[] parts = str.split(Pattern.quote(delimiter), -1);
                List<Datum> items = new ArrayList<>();
                for (String part : parts) items.add(Datum.of(part));
                yield new Datum.DList(items, false);
            }
            default -> Datum.voidValue();
        };
    }

    private int stringGetCount(String str, String chunkType) {
        String itemDelimiter = vm.getItemDelimiter();
        return switch (chunkType.toLowerCase()) {
            case "char", "chars" -> str.length();
            case "word", "words" -> str.isEmpty() ? 0 : str.split("\\s+").length;
            case "item", "items" -> str.split(Pattern.quote(itemDelimiter.isEmpty() ? "," : itemDelimiter), -1).length;
            case "line", "lines" -> str.split("\\r?\\n|\\r", -1).length;
            default -> 0;
        };
    }

    private Datum getStringChunkRef(String str, String chunkTypeName, int start, int end) {
        StringChunkType chunkType = switch (chunkTypeName.toLowerCase()) {
            case "word" -> StringChunkType.WORD;
            case "line" -> StringChunkType.LINE;
            case "item" -> StringChunkType.ITEM;
            default -> StringChunkType.CHAR;
        };

        String itemDelimiter = vm.getItemDelimiter();
        char delimiterChar = itemDelimiter.isEmpty() ? ',' : itemDelimiter.charAt(0);
        String chunkValue = extractStringChunk(str, chunkType, start, end, itemDelimiter);

        return new Datum.StringChunk(Datum.of(str), chunkType, start, end, delimiterChar, chunkValue);
    }

    /**
     * Extract a chunk from a string.
     */
    public String extractStringChunk(String str, StringChunkType type, int start, int end, String itemDelimiter) {
        if (start < 1) start = 1;
        if (end < start) end = start;

        return switch (type) {
            case CHAR -> {
                int startIdx = Math.min(start - 1, str.length());
                int endIdx = Math.min(end, str.length());
                yield startIdx < endIdx ? str.substring(startIdx, endIdx) : "";
            }
            case WORD -> joinParts(str.split("\\s+"), start, end, " ");
            case LINE -> joinParts(str.split("\\r?\\n|\\r", -1), start, end,
                str.contains("\r\n") ? "\r\n" : (str.contains("\n") ? "\n" : "\r"));
            case ITEM -> {
                String delim = itemDelimiter.isEmpty() ? "," : itemDelimiter;
                yield joinParts(str.split(Pattern.quote(delim), -1), start, end, delim);
            }
        };
    }

    private String joinParts(String[] parts, int start, int end, String separator) {
        if (start > parts.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = start - 1; i < Math.min(end, parts.length); i++) {
            if (!sb.isEmpty()) sb.append(separator);
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Collect script instances from a datum (recursively for lists/proplists).
     */
    public void collectScriptInstances(Datum datum, List<Datum.ScriptInstanceRef> instances,
                                       LingoVM.ActiveScriptInstancesCallback callback) {
        if (datum instanceof Datum.ScriptInstanceRef instance) {
            instances.add(instance);
        } else if (datum instanceof Datum.SpriteRef && callback != null) {
            instances.addAll(callback.getActiveScriptInstances());
        } else if (datum instanceof Datum.DList list) {
            for (Datum item : list.items()) {
                collectScriptInstances(item, instances, callback);
            }
        } else if (datum instanceof Datum.PropList propList) {
            for (Datum value : propList.properties().values()) {
                collectScriptInstances(value, instances, callback);
            }
        }
    }
}
