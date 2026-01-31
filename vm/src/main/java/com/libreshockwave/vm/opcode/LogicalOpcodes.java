package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.Map;

/**
 * Logical operation opcodes.
 */
public final class LogicalOpcodes {

    private LogicalOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.AND, LogicalOpcodes::and);
        handlers.put(Opcode.OR, LogicalOpcodes::or);
        handlers.put(Opcode.NOT, LogicalOpcodes::not);
    }

    private static boolean and(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(a.isTruthy() && b.isTruthy() ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean or(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(a.isTruthy() || b.isTruthy() ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    private static boolean not(ExecutionContext ctx) {
        Datum a = ctx.pop();
        ctx.push(a.isTruthy() ? Datum.FALSE : Datum.TRUE);
        return true;
    }
}
