package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.Map;

/**
 * Arithmetic operation opcodes.
 */
public final class ArithmeticOpcodes {

    private ArithmeticOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.ADD, ArithmeticOpcodes::add);
        handlers.put(Opcode.SUB, ArithmeticOpcodes::sub);
        handlers.put(Opcode.MUL, ArithmeticOpcodes::mul);
        handlers.put(Opcode.DIV, ArithmeticOpcodes::div);
        handlers.put(Opcode.MOD, ArithmeticOpcodes::mod);
        handlers.put(Opcode.INV, ArithmeticOpcodes::inv);
    }

    private static boolean add(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();

        // Point + List/Point arithmetic (Director supports this)
        if (a instanceof Datum.Point pa) {
            int dx = 0, dy = 0;
            if (b instanceof Datum.Point pb) {
                dx = pb.x(); dy = pb.y();
            } else if (b instanceof Datum.List list && list.items().size() >= 2) {
                dx = list.items().get(0).toInt();
                dy = list.items().get(1).toInt();
            } else {
                dx = b.toInt(); dy = b.toInt();
            }
            ctx.push(new Datum.Point(pa.x() + dx, pa.y() + dy));
            return true;
        }
        if (b instanceof Datum.Point pb && a instanceof Datum.List list && list.items().size() >= 2) {
            ctx.push(new Datum.Point(list.items().get(0).toInt() + pb.x(), list.items().get(1).toInt() + pb.y()));
            return true;
        }

        // Rect + List/Rect arithmetic
        if (a instanceof Datum.Rect ra) {
            int dl = 0, dt = 0, dr = 0, dbottom = 0;
            if (b instanceof Datum.Rect rb) {
                dl = rb.left(); dt = rb.top(); dr = rb.right(); dbottom = rb.bottom();
            } else if (b instanceof Datum.List list && list.items().size() >= 4) {
                dl = list.items().get(0).toInt(); dt = list.items().get(1).toInt();
                dr = list.items().get(2).toInt(); dbottom = list.items().get(3).toInt();
            }
            ctx.push(new Datum.Rect(ra.left() + dl, ra.top() + dt, ra.right() + dr, ra.bottom() + dbottom));
            return true;
        }

        if (a.isFloat() || b.isFloat()) {
            ctx.push(Datum.of(a.toDouble() + b.toDouble()));
        } else {
            ctx.push(Datum.of(a.toInt() + b.toInt()));
        }
        return true;
    }

    private static boolean sub(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();

        // Point - Point/List arithmetic
        if (a instanceof Datum.Point pa) {
            int dx = 0, dy = 0;
            if (b instanceof Datum.Point pb) {
                dx = pb.x(); dy = pb.y();
            } else if (b instanceof Datum.List list && list.items().size() >= 2) {
                dx = list.items().get(0).toInt();
                dy = list.items().get(1).toInt();
            } else {
                dx = b.toInt(); dy = b.toInt();
            }
            ctx.push(new Datum.Point(pa.x() - dx, pa.y() - dy));
            return true;
        }

        // Rect - Rect/List arithmetic
        if (a instanceof Datum.Rect ra) {
            int dl = 0, dt = 0, dr = 0, dbottom = 0;
            if (b instanceof Datum.Rect rb) {
                dl = rb.left(); dt = rb.top(); dr = rb.right(); dbottom = rb.bottom();
            } else if (b instanceof Datum.List list && list.items().size() >= 4) {
                dl = list.items().get(0).toInt(); dt = list.items().get(1).toInt();
                dr = list.items().get(2).toInt(); dbottom = list.items().get(3).toInt();
            }
            ctx.push(new Datum.Rect(ra.left() - dl, ra.top() - dt, ra.right() - dr, ra.bottom() - dbottom));
            return true;
        }

        if (a.isFloat() || b.isFloat()) {
            ctx.push(Datum.of(a.toDouble() - b.toDouble()));
        } else {
            ctx.push(Datum.of(a.toInt() - b.toInt()));
        }
        return true;
    }

    private static boolean mul(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        if (a.isFloat() || b.isFloat()) {
            ctx.push(Datum.of(a.toDouble() * b.toDouble()));
        } else {
            ctx.push(Datum.of(a.toInt() * b.toInt()));
        }
        return true;
    }

    private static boolean div(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        double bVal = b.toDouble();
        if (bVal == 0) {
            throw ctx.error("Division by zero");
        }
        ctx.push(Datum.of(a.toDouble() / bVal));
        return true;
    }

    private static boolean mod(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        int bVal = b.toInt();
        if (bVal == 0) {
            throw ctx.error("Modulo by zero");
        }
        ctx.push(Datum.of(a.toInt() % bVal));
        return true;
    }

    private static boolean inv(ExecutionContext ctx) {
        Datum a = ctx.pop();
        if (a.isFloat()) {
            ctx.push(Datum.of(-a.toDouble()));
        } else {
            ctx.push(Datum.of(-a.toInt()));
        }
        return true;
    }
}
