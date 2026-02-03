package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.Datum;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles method calls on linear lists.
 */
public final class ListMethodDispatcher {

    private ListMethodDispatcher() {}

    public static Datum dispatch(Datum.List list, String methodName, List<Datum> args) {
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
                // setAt(list, position, value) - set value at position (1-indexed)
                // Like dirplayer-rs: pads with VOID if index > current length
                if (args.size() < 2) yield Datum.VOID;
                int index = args.get(0).toInt() - 1; // Convert to 0-indexed
                Datum value = args.get(1);
                if (index < 0) yield Datum.VOID;
                if (index < list.items().size()) {
                    list.items().set(index, value);
                } else {
                    // Pad with VOID values up to the target index
                    while (list.items().size() < index) {
                        list.items().add(Datum.VOID);
                    }
                    list.items().add(value);
                }
                yield Datum.VOID;
            }
            case "append", "add" -> {
                if (args.isEmpty()) yield Datum.VOID;
                list.items().add(args.get(0));
                yield Datum.VOID;
            }
            case "addat" -> {
                // addAt(list, position, value) - insert value at position (1-indexed)
                // If the element at the target position is VOID, replace it instead of inserting
                // This allows VOID to act as a placeholder that gets filled in
                if (args.size() < 2) yield Datum.VOID;
                int index = args.get(0).toInt() - 1; // Convert to 0-indexed
                Datum value = args.get(1);
                if (index < 0) index = 0;
                if (index < list.items().size() && list.items().get(index).isVoid()) {
                    // Replace VOID placeholder instead of inserting
                    list.items().set(index, value);
                } else if (index >= list.items().size()) {
                    // Pad with VOID if needed, then add
                    while (list.items().size() < index) {
                        list.items().add(Datum.VOID);
                    }
                    list.items().add(value);
                } else {
                    list.items().add(index, value);
                }
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
            case "getone", "findpos" -> {
                // Find 1-based index of value, returns 0 if not found
                if (args.isEmpty()) yield Datum.ZERO;
                Datum value = args.get(0);
                for (int i = 0; i < list.items().size(); i++) {
                    if (list.items().get(i).equals(value)) {
                        yield Datum.of(i + 1);
                    }
                }
                yield Datum.ZERO;
            }
            case "getlast" -> {
                // getLast(list) - return the last element
                if (list.items().isEmpty()) yield Datum.VOID;
                yield list.items().get(list.items().size() - 1);
            }
            case "deleteone" -> {
                // deleteOne(list, value) - remove first matching element
                if (args.isEmpty()) yield Datum.VOID;
                Datum value = args.get(0);
                for (int i = 0; i < list.items().size(); i++) {
                    if (list.items().get(i).equals(value)) {
                        list.items().remove(i);
                        break;
                    }
                }
                yield Datum.VOID;
            }
            case "join" -> {
                // join(list, separator) - concatenate elements into string
                String separator = args.isEmpty() ? "" : args.get(0).toStr();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.items().size(); i++) {
                    if (i > 0) sb.append(separator);
                    sb.append(list.items().get(i).toStr());
                }
                yield Datum.of(sb.toString());
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
}
