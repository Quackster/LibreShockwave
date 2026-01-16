package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.vm.LingoVM;

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
        registerCoreHandlers(vm);
        registerTypeHandlers(vm);
        registerPointRectHandlers(vm);
    }

    private static void registerCoreHandlers(LingoVM vm) {
        // Output
        vm.registerBuiltin("put", HandlerRegistry::put);
        vm.registerBuiltin("alert", HandlerRegistry::alert);

        // Control
        vm.registerBuiltin("halt", HandlerRegistry::halt);
        vm.registerBuiltin("nothing", HandlerRegistry::nothing);
        vm.registerBuiltin("pass", HandlerRegistry::nothing);
        vm.registerBuiltin("return", HandlerRegistry::returnValue);

        // Timing
        vm.registerBuiltin("delay", HandlerRegistry::delay);
        vm.registerBuiltin("timeout", HandlerRegistry::timeout);

        // Object
        vm.registerBuiltin("new", HandlerRegistry::newObject);
        vm.registerBuiltin("objectP", HandlerRegistry::objectP);
        vm.registerBuiltin("script", HandlerRegistry::script);
    }

    private static void registerTypeHandlers(LingoVM vm) {
        // Type checking
        vm.registerBuiltin("ilk", HandlerRegistry::ilk);
        vm.registerBuiltin("objectP", HandlerRegistry::objectP);
        vm.registerBuiltin("listP", HandlerRegistry::listP);
        vm.registerBuiltin("stringP", HandlerRegistry::stringP);
        vm.registerBuiltin("symbolP", HandlerRegistry::symbolP);
        vm.registerBuiltin("integerP", HandlerRegistry::integerP);
        vm.registerBuiltin("floatP", HandlerRegistry::floatP);
        vm.registerBuiltin("voidP", HandlerRegistry::voidP);

        // Type conversion
        vm.registerBuiltin("value", HandlerRegistry::value);
        vm.registerBuiltin("symbol", HandlerRegistry::symbol);
    }

    private static void registerPointRectHandlers(LingoVM vm) {
        // Point
        vm.registerBuiltin("point", HandlerRegistry::point);

        // Rect
        vm.registerBuiltin("rect", HandlerRegistry::rect);
        vm.registerBuiltin("union", HandlerRegistry::union);
        vm.registerBuiltin("intersect", HandlerRegistry::intersect);
        vm.registerBuiltin("inside", HandlerRegistry::inside);
        vm.registerBuiltin("map", HandlerRegistry::map);
        vm.registerBuiltin("offset", HandlerRegistry::offset);
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
        if (!args.isEmpty()) {
            System.out.println("[ALERT] " + args.get(0).stringValue());
        }
        return Datum.voidValue();
    }

    private static Datum halt(LingoVM vm, List<Datum> args) {
        vm.halt();
        return Datum.voidValue();
    }

    private static Datum nothing(LingoVM vm, List<Datum> args) {
        return Datum.voidValue();
    }

    private static Datum returnValue(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        return args.get(0);
    }

    private static Datum delay(LingoVM vm, List<Datum> args) {
        // Delay is typically handled by the player, not the VM
        return Datum.voidValue();
    }

    private static Datum timeout(LingoVM vm, List<Datum> args) {
        // Timeout creation - would need player support
        return Datum.voidValue();
    }

    private static Datum newObject(LingoVM vm, List<Datum> args) {
        // Object instantiation - requires parent script support
        return Datum.voidValue();
    }

    private static Datum script(LingoVM vm, List<Datum> args) {
        // Script reference - would need cast member lookup
        return Datum.voidValue();
    }

    // Type checking handlers

    private static Datum ilk(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.symbol("void");
        Datum d = args.get(0);

        if (args.size() > 1) {
            // ilk(obj, #type) - check if obj is of type
            String checkType = args.get(1).stringValue().toLowerCase();
            String actualType = d.type().getTypeName().toLowerCase();
            return actualType.equals(checkType) ? Datum.TRUE : Datum.FALSE;
        }

        return Datum.symbol(d.type().getTypeName());
    }

    private static Datum objectP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        Datum d = args.get(0);
        return (d instanceof Datum.ScriptInstanceRef) ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum listP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        Datum d = args.get(0);
        return (d instanceof Datum.DList || d instanceof Datum.PropList) ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum stringP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        return args.get(0).isString() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum symbolP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        return args.get(0).isSymbol() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum integerP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        return args.get(0).isInt() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum floatP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.FALSE;
        return (args.get(0) instanceof Datum.DFloat) ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum voidP(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.TRUE;
        return args.get(0).isVoid() ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum value(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();
        String s = args.get(0).stringValue().trim();

        // Try to parse as integer
        try {
            return Datum.of(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            // Not an integer
        }

        // Try to parse as float
        try {
            return Datum.of(Float.parseFloat(s));
        } catch (NumberFormatException e) {
            // Not a float
        }

        return Datum.voidValue();
    }

    private static Datum symbol(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.symbol("");
        return Datum.symbol(args.get(0).stringValue());
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
                Math.min(r1.left(), r2.left()),
                Math.min(r1.top(), r2.top()),
                Math.max(r1.right(), r2.right()),
                Math.max(r1.bottom(), r2.bottom())
            );
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

            if (right > left && bottom > top) {
                return new Datum.IntRect(left, top, right, bottom);
            }
        }
        return new Datum.IntRect(0, 0, 0, 0);
    }

    private static Datum inside(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.FALSE;

        if (args.get(0) instanceof Datum.IntPoint p && args.get(1) instanceof Datum.IntRect r) {
            boolean isInside = p.x() >= r.left() && p.x() < r.right() &&
                               p.y() >= r.top() && p.y() < r.bottom();
            return isInside ? Datum.TRUE : Datum.FALSE;
        }
        return Datum.FALSE;
    }

    private static Datum map(LingoVM vm, List<Datum> args) {
        // map(destRect, srcRect, srcPoint) -> destPoint
        if (args.size() < 3) return new Datum.IntPoint(0, 0);

        // This is a more complex operation used for coordinate transformation
        return new Datum.IntPoint(0, 0);
    }

    private static Datum offset(LingoVM vm, List<Datum> args) {
        if (args.size() < 3) return Datum.voidValue();

        Datum target = args.get(0);
        int dx = args.get(1).intValue();
        int dy = args.get(2).intValue();

        if (target instanceof Datum.IntPoint p) {
            return new Datum.IntPoint(p.x() + dx, p.y() + dy);
        } else if (target instanceof Datum.IntRect r) {
            return new Datum.IntRect(r.left() + dx, r.top() + dy, r.right() + dx, r.bottom() + dy);
        }
        return target;
    }
}
