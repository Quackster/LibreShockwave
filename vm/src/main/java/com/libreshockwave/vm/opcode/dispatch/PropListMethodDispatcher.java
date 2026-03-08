package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.Datum;

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
            return Datum.of(propList.size());
        }
        if ("getprop".equalsIgnoreCase(methodName) || "getaprop".equalsIgnoreCase(methodName)
                || "getproperty".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            String key = args.get(0).toKeyName();
            return propList.getOrDefault(key, Datum.VOID);
        }
        if ("setprop".equalsIgnoreCase(methodName) || "setaprop".equalsIgnoreCase(methodName)) {
            if (args.size() < 2) return Datum.VOID;
            String key = args.get(0).toKeyName();
            propList.put(key, args.get(1));
            return Datum.VOID;
        }
        if ("addprop".equalsIgnoreCase(methodName)) {
            if (args.size() < 2) return Datum.VOID;
            String key = args.get(0).toKeyName();
            // addProp always appends — allows duplicate keys
            propList.add(key, args.get(1));
            return Datum.VOID;
        }
        if ("getat".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            Datum keyOrIndex = args.get(0);
            if (keyOrIndex instanceof Datum.Str s) {
                return propList.getOrDefault(s.value(), Datum.VOID);
            } else if (keyOrIndex instanceof Datum.Symbol sym) {
                return propList.getOrDefault(sym.name(), Datum.VOID);
            } else {
                int index = keyOrIndex.toInt() - 1;
                if (index >= 0 && index < propList.size()) {
                    return propList.getValue(index);
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
                if (index >= 0 && index < propList.size()) {
                    propList.setValue(index, value);
                }
            } else {
                propList.put(keyOrIndex.toKeyName(), value);
            }
            return Datum.VOID;
        }
        if ("getone".equalsIgnoreCase(methodName)) {
            // getOne(propList, value) - find the property NAME where the value matches
            // Returns the key (as symbol) or 0 if not found
            if (args.isEmpty()) return Datum.ZERO;
            Datum searchValue = args.get(0);
            for (Datum.PropEntry entry : propList.entries()) {
                if (entry.value() == searchValue || entry.value().equals(searchValue)) {
                    return Datum.symbol(entry.key());
                }
            }
            return Datum.ZERO;
        }
        if ("deleteprop".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            propList.remove(args.get(0).toKeyName());
            return Datum.VOID;
        }
        if ("findpos".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            String key = args.get(0).toKeyName();
            int pos = propList.findPos(key);
            return pos > 0 ? Datum.of(pos) : Datum.VOID;
        }
        if ("getpropat".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            int index = args.get(0).toInt() - 1;
            if (index >= 0 && index < propList.size()) return Datum.symbol(propList.getKey(index));
            return Datum.VOID;
        }
        if ("deleteat".equalsIgnoreCase(methodName)) {
            if (args.isEmpty()) return Datum.VOID;
            int index = args.get(0).toInt() - 1;
            if (index >= 0 && index < propList.size()) propList.removeAt(index);
            return Datum.VOID;
        }
        if ("getlast".equalsIgnoreCase(methodName)) {
            if (propList.isEmpty()) return Datum.VOID;
            return propList.getValue(propList.size() - 1);
        }
        if ("getfirst".equalsIgnoreCase(methodName)) {
            if (propList.isEmpty()) return Datum.VOID;
            return propList.getValue(0);
        }
        if ("duplicate".equalsIgnoreCase(methodName)) {
            // Deep copy: Director's duplicate() creates independent copies of nested structures.
            return propList.deepCopy();
        }
        return Datum.VOID;
    }
}
