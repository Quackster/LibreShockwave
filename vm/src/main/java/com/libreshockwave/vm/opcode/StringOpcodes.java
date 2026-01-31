package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.Map;

/**
 * String operation opcodes.
 */
public final class StringOpcodes {

    private StringOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.JOIN_STR, StringOpcodes::joinStr);
        handlers.put(Opcode.JOIN_PAD_STR, StringOpcodes::joinPadStr);
        handlers.put(Opcode.CONTAINS_STR, StringOpcodes::containsStr);
    }

    private static boolean joinStr(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(Datum.of(a.toStr() + b.toStr()));
        return true;
    }

    private static boolean joinPadStr(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(Datum.of(a.toStr() + " " + b.toStr()));
        return true;
    }

    private static boolean containsStr(ExecutionContext ctx) {
        Datum needle = ctx.pop();
        Datum haystack = ctx.pop();
        boolean contains = haystack.toStr().toLowerCase()
            .contains(needle.toStr().toLowerCase());
        ctx.push(contains ? Datum.TRUE : Datum.FALSE);
        return true;
    }
}
