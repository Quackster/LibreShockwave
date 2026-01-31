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
