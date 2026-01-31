package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.Map;

/**
 * Comparison operation opcodes.
 */
public final class ComparisonOpcodes {

    private ComparisonOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.LT, ComparisonOpcodes::lt);
        handlers.put(Opcode.LT_EQ, ComparisonOpcodes::ltEq);
        handlers.put(Opcode.GT, ComparisonOpcodes::gt);
        handlers.put(Opcode.GT_EQ, ComparisonOpcodes::gtEq);
        handlers.put(Opcode.EQ, ComparisonOpcodes::eq);
        handlers.put(Opcode.NT_EQ, ComparisonOpcodes::ntEq);
    }

    private static boolean lt(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(a.toDouble() < b.toDouble() ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean ltEq(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(a.toDouble() <= b.toDouble() ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean gt(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(a.toDouble() > b.toDouble() ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean gtEq(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(a.toDouble() >= b.toDouble() ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean eq(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(datumEquals(a, b) ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean ntEq(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(!datumEquals(a, b) ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean datumEquals(Datum a, Datum b) {
        if (a.isNumber() && b.isNumber()) {
            return a.toDouble() == b.toDouble();
        }
        if (a.isString() && b.isString()) {
            return a.toStr().equalsIgnoreCase(b.toStr());
        }
        if (a.isSymbol() && b.isSymbol()) {
            return ((Datum.Symbol) a).name().equalsIgnoreCase(((Datum.Symbol) b).name());
        }
        return a.equals(b);
    }
}
