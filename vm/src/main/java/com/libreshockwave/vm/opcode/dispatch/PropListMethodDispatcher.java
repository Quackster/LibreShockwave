package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.Datum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Handles method calls on property lists.
 * Uses equalsIgnoreCase to avoid toLowerCase String allocation.
 */
public final class PropListMethodDispatcher {

    private PropListMethodDispatcher() {}

    public static Datum dispatch(Datum.PropList propList, String methodName, List<Datum> args) {
        // Fast path for most common operations (no allocation)
        if ("count".equalsIgnoreCase(methodName)) {
            return Datum.of(propList.properties().size());
        }
        if ("getprop".equalsIgnoreCase(methodName) || "getaprop".equalsIgnoreCase(methodName)
                || "getproperty".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            String key = args.get(0).toKeyName();
            return propList.properties().getOrDefault(key, Datum.VOID);
        }
        if ("setprop".equalsIgnoreCase(methodName) || "setaprop".equalsIgnoreCase(methodName)) {
            if (args.size() < 2) return Datum.VOID;
            String key = args.get(0).toKeyName();
            propList.properties().put(key, args.get(1));
            return Datum.VOID;
        }
        if ("addprop".equalsIgnoreCase(methodName)) {
            if (args.size() < 2) return Datum.VOID;
            String key = args.get(0).toKeyName();
            propList.properties().put(key, args.get(1));
            return Datum.VOID;
        }
        if ("getat".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            Datum keyOrIndex = args.get(0);
            if (keyOrIndex instanceof Datum.Str s) {
                return propList.properties().getOrDefault(s.value(), Datum.VOID);
            } else if (keyOrIndex instanceof Datum.Symbol sym) {
                return propList.properties().getOrDefault(sym.name(), Datum.VOID);
            } else {
                int index = keyOrIndex.toInt() - 1;
                var entries = new ArrayList<>(propList.properties().entrySet());
                if (index >= 0 && index < entries.size()) {
                    return entries.get(index).getValue();
                }
                return Datum.VOID;
            }
        }
        if ("setat".equalsIgnoreCase(methodName)) {
            if (args.size() < 2) return Datum.VOID;
            Datum keyOrIndex = args.get(0);
            Datum value = args.get(1);
            if (keyOrIndex instanceof Datum.Int intKey) {
                int index = intKey.value() - 1;
                var entries = new ArrayList<>(propList.properties().entrySet());
                if (index >= 0 && index < entries.size()) {
                    propList.properties().put(entries.get(index).getKey(), value);
                }
            } else {
                propList.properties().put(keyOrIndex.toKeyName(), value);
            }
            return Datum.VOID;
        }
        if ("getone".equalsIgnoreCase(methodName)) {
            // getOne(propList, value) - find the property NAME where the value matches
            // Returns the key (as symbol) or 0 if not found
            if (args.isEmpty()) return Datum.ZERO;
            Datum searchValue = args.get(0);
            for (var entry : propList.properties().entrySet()) {
                if (entry.getValue() == searchValue || entry.getValue().equals(searchValue)) {
                    return Datum.symbol(entry.getKey());
                }
            }
            return Datum.ZERO;
        }
        if ("deleteprop".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            propList.properties().remove(args.get(0).toKeyName());
            return Datum.VOID;
        }
        if ("findpos".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            String key = args.get(0).toKeyName();
            int pos = 1;
            for (String k : propList.properties().keySet()) {
                if (k.equalsIgnoreCase(key)) return Datum.of(pos);
                pos++;
            }
            return Datum.VOID;
        }
        if ("getpropat".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            int index = args.get(0).toInt() - 1;
            var keys = new ArrayList<>(propList.properties().keySet());
            if (index >= 0 && index < keys.size()) return Datum.symbol(keys.get(index));
            return Datum.VOID;
        }
        if ("deleteat".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            int index = args.get(0).toInt() - 1;
            var keys = new ArrayList<>(propList.properties().keySet());
            if (index >= 0 && index < keys.size()) propList.properties().remove(keys.get(index));
            return Datum.VOID;
        }
        if ("getlast".equalsIgnoreCase(methodName)) {
            if (propList.properties().isEmpty()) return Datum.VOID;
            Datum last = Datum.VOID;
            for (Datum v : propList.properties().values()) last = v;
            return last;
        }
        if ("getfirst".equalsIgnoreCase(methodName)) {
            if (propList.properties().isEmpty()) return Datum.VOID;
            return propList.properties().values().iterator().next();
        }
        if ("duplicate".equalsIgnoreCase(methodName)) {
            // Deep copy: Director's duplicate() creates independent copies of nested structures.
            return propList.deepCopy();
        }
        return Datum.VOID;
    }
}
