package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.Datum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Handles method calls on property lists.
 */
public final class PropListMethodDispatcher {

    private PropListMethodDispatcher() {}

    public static Datum dispatch(Datum.PropList propList, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "count" -> Datum.of(propList.properties().size());
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                Datum keyOrIndex = args.get(0);
                // Support both string/symbol key lookup and integer index lookup
                if (keyOrIndex instanceof Datum.Str s) {
                    yield propList.properties().getOrDefault(s.value(), Datum.VOID);
                } else if (keyOrIndex instanceof Datum.Symbol sym) {
                    yield propList.properties().getOrDefault(sym.name(), Datum.VOID);
                } else {
                    // Integer index (1-based)
                    int index = keyOrIndex.toInt() - 1;
                    var entries = new ArrayList<>(propList.properties().entrySet());
                    if (index >= 0 && index < entries.size()) {
                        yield entries.get(index).getValue();
                    }
                    yield Datum.VOID;
                }
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
                // setAt(propList, position/key, value)
                // For integer keys, do positional indexing (1-based → 0-based)
                if (args.size() < 2) yield Datum.VOID;
                Datum keyOrIndex = args.get(0);
                Datum value = args.get(1);
                if (keyOrIndex instanceof Datum.Int intKey) {
                    int index = intKey.value() - 1;
                    var entries = new ArrayList<>(propList.properties().entrySet());
                    if (index >= 0 && index < entries.size()) {
                        String existingKey = entries.get(index).getKey();
                        propList.properties().put(existingKey, value);
                    }
                } else {
                    String key = keyOrIndex instanceof Datum.Symbol s ? s.name() : keyOrIndex.toStr();
                    propList.properties().put(key, value);
                }
                yield Datum.VOID;
            }
            case "findpos" -> {
                // Find position of key, return VOID if not found
                if (args.isEmpty()) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                int pos = 1;
                for (String k : propList.properties().keySet()) {
                    if (k.equalsIgnoreCase(key)) {
                        yield Datum.of(pos);
                    }
                    pos++;
                }
                yield Datum.VOID;
            }
            case "duplicate" -> {
                yield new Datum.PropList(new LinkedHashMap<>(propList.properties()));
            }
            default -> Datum.VOID;
        };
    }
}
