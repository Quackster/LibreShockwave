package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.lingo.LingoException;
import com.libreshockwave.vm.LingoVM;

import java.util.*;

import static com.libreshockwave.handlers.HandlerArgs.*;

/**
 * Built-in list and propList handlers for Lingo.
 * Refactored: Uses HandlerArgs for argument extraction, reducing boilerplate.
 */
public class ListHandlers {

    public static void register(LingoVM vm) {
        // List operations
        vm.registerBuiltin("list", ListHandlers::createList);
        vm.registerBuiltin("count", ListHandlers::count);
        vm.registerBuiltin("getAt", ListHandlers::getAt);
        vm.registerBuiltin("setAt", ListHandlers::setAt);
        vm.registerBuiltin("add", ListHandlers::add);
        vm.registerBuiltin("addAt", ListHandlers::addAt);
        vm.registerBuiltin("append", ListHandlers::append);
        vm.registerBuiltin("deleteAt", ListHandlers::deleteAt);
        vm.registerBuiltin("deleteOne", ListHandlers::deleteOne);
        vm.registerBuiltin("getOne", ListHandlers::getOne);
        vm.registerBuiltin("getPos", ListHandlers::getPos);
        vm.registerBuiltin("sort", ListHandlers::sort);
        vm.registerBuiltin("duplicate", ListHandlers::duplicate);
        vm.registerBuiltin("getLast", ListHandlers::getLast);
        vm.registerBuiltin("deleteLast", ListHandlers::deleteLast);

        // PropList operations
        vm.registerBuiltin("getProp", ListHandlers::getProp);
        vm.registerBuiltin("setProp", ListHandlers::setProp);
        vm.registerBuiltin("addProp", ListHandlers::addProp);
        vm.registerBuiltin("deleteProp", ListHandlers::deleteProp);
        vm.registerBuiltin("findPos", ListHandlers::findPos);
        vm.registerBuiltin("findPosNear", ListHandlers::findPosNear);
        vm.registerBuiltin("getPropAt", ListHandlers::getPropAt);
        vm.registerBuiltin("getaProp", ListHandlers::getaProp);
        vm.registerBuiltin("setaProp", ListHandlers::setaProp);
    }

    private static Datum createList(LingoVM vm, List<Datum> args) {
        Datum.DList list = Datum.list();
        for (Datum arg : args) {
            list.add(arg);
        }
        return list;
    }

    private static Datum count(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0);
        Datum d = get0(args);

        // Two-argument form: count(string, #chunkType) or count(string, chunkTypeSymbol)
        if (hasAtLeast(args, 2) && d.isString()) {
            String str = d.stringValue();
            String chunkType = getString(args, 1, "").toLowerCase();
            char itemDelim = vm.getItemDelimiter().isEmpty() ? ',' : vm.getItemDelimiter().charAt(0);
            return switch (chunkType) {
                case "char", "chars" -> Datum.of(str.length());
                case "word", "words" -> Datum.of(str.isEmpty() ? 0 : str.trim().split("\\s+").length);
                case "item", "items" -> Datum.of(str.split(java.util.regex.Pattern.quote(String.valueOf(itemDelim)), -1).length);
                case "line", "lines" -> Datum.of(str.split("\\r?\\n|\\r", -1).length);
                default -> Datum.of(0);
            };
        }

        if (d instanceof Datum.DList list) {
            return Datum.of(list.count());
        } else if (d instanceof Datum.PropList propList) {
            return Datum.of(propList.count());
        } else if (d instanceof Datum.Str s) {
            return Datum.of(s.value().length());
        }
        return Datum.of(0);
    }

    private static Datum getAt(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list == null) return Datum.voidValue();
        return list.getAt(getInt(args, 1, 0));
    }

    private static Datum setAt(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 3)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null) {
            list.setAt(getInt(args, 1, 0), get2(args));
        }
        return Datum.voidValue();
    }

    private static Datum add(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null) {
            list.add(get1(args));
        }
        return Datum.voidValue();
    }

    private static Datum addAt(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 3)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null) {
            int index = getInt(args, 1, 0);
            if (index >= 1 && index <= list.count() + 1) {
                list.items().add(index - 1, get2(args));
            }
        }
        return Datum.voidValue();
    }

    private static Datum append(LingoVM vm, List<Datum> args) {
        return add(vm, args);
    }

    private static Datum deleteAt(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null) {
            int index = getInt(args, 1, 0);
            if (index >= 1 && index <= list.count()) {
                list.items().remove(index - 1);
            }
        }
        return Datum.voidValue();
    }

    private static Datum deleteOne(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null) {
            list.items().remove(get1(args));
        }
        return Datum.voidValue();
    }

    private static Datum getOne(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.of(0);
        Datum.DList list = asList(args, 0);
        if (list == null) return Datum.of(0);

        Datum value = get1(args);
        for (int i = 0; i < list.count(); i++) {
            if (list.items().get(i).equals(value)) {
                return Datum.of(i + 1);
            }
        }
        return Datum.of(0);
    }

    private static Datum getPos(LingoVM vm, List<Datum> args) {
        return getOne(vm, args);
    }

    private static Datum sort(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null) {
            list.items().sort((a, b) -> {
                if (a.isNumber() && b.isNumber()) {
                    return Float.compare(a.floatValue(), b.floatValue());
                }
                return a.stringValue().compareToIgnoreCase(b.stringValue());
            });
        }
        return Datum.voidValue();
    }

    private static Datum duplicate(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.list();
        Datum d = get0(args);

        if (d instanceof Datum.DList list) {
            Datum.DList copy = Datum.list();
            for (Datum item : list.items()) {
                copy.add(item);
            }
            return copy;
        } else if (d instanceof Datum.PropList propList) {
            Datum.PropList copy = Datum.propList();
            for (var entry : propList.properties().entrySet()) {
                copy.put(entry.getKey(), entry.getValue());
            }
            return copy;
        }
        return d;
    }

    private static Datum getLast(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null && list.count() > 0) {
            return list.items().get(list.count() - 1);
        }
        return Datum.voidValue();
    }

    private static Datum deleteLast(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.voidValue();
        Datum.DList list = asList(args, 0);
        if (list != null && list.count() > 0) {
            list.items().remove(list.count() - 1);
        }
        return Datum.voidValue();
    }

    // PropList operations

    private static Datum getProp(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.PropList propList = asPropList(args, 0);
        if (propList == null) return Datum.voidValue();
        return propList.get(get1(args));
    }

    private static Datum setProp(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 3)) return Datum.voidValue();
        Datum.PropList propList = asPropList(args, 0);
        if (propList != null) {
            propList.put(get1(args), get2(args));
        }
        return Datum.voidValue();
    }

    private static Datum addProp(LingoVM vm, List<Datum> args) {
        return setProp(vm, args);
    }

    private static Datum deleteProp(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.PropList propList = asPropList(args, 0);
        if (propList != null) {
            propList.properties().remove(get1(args));
        }
        return Datum.voidValue();
    }

    private static Datum findPos(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.of(0);
        Datum.PropList propList = asPropList(args, 0);
        if (propList == null) return Datum.of(0);

        Datum key = get1(args);
        int pos = 1;
        for (Datum k : propList.properties().keySet()) {
            if (k.equals(key)) {
                return Datum.of(pos);
            }
            pos++;
        }
        return Datum.of(0);
    }

    private static Datum findPosNear(LingoVM vm, List<Datum> args) {
        return findPos(vm, args);
    }

    private static Datum getPropAt(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();
        Datum.PropList propList = asPropList(args, 0);
        if (propList == null) return Datum.voidValue();

        int index = getInt(args, 1, 0);
        int pos = 1;
        for (Datum key : propList.properties().keySet()) {
            if (pos == index) {
                return key;
            }
            pos++;
        }
        return Datum.voidValue();
    }

    private static Datum getaProp(LingoVM vm, List<Datum> args) {
        return getProp(vm, args);
    }

    private static Datum setaProp(LingoVM vm, List<Datum> args) {
        return setProp(vm, args);
    }
}
