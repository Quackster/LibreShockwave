package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.List;
import java.util.Map;

/**
 * Stack manipulation opcodes.
 */
public final class StackOpcodes {

    private StackOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        // Push constants
        handlers.put(Opcode.PUSH_ZERO, StackOpcodes::pushZero);
        handlers.put(Opcode.PUSH_INT8, StackOpcodes::pushInt);
        handlers.put(Opcode.PUSH_INT16, StackOpcodes::pushInt);
        handlers.put(Opcode.PUSH_INT32, StackOpcodes::pushInt);
        handlers.put(Opcode.PUSH_FLOAT32, StackOpcodes::pushFloat);
        handlers.put(Opcode.PUSH_CONS, StackOpcodes::pushCons);
        handlers.put(Opcode.PUSH_SYMB, StackOpcodes::pushSymb);

        // Stack manipulation
        handlers.put(Opcode.SWAP, StackOpcodes::swap);
        handlers.put(Opcode.POP, StackOpcodes::pop);
        handlers.put(Opcode.PEEK, StackOpcodes::peek);
    }

    private static boolean pushZero(ExecutionContext ctx) {
        ctx.push(Datum.ZERO);
        return true;
    }

    private static boolean pushInt(ExecutionContext ctx) {
        ctx.push(Datum.of(ctx.getArgument()));
        return true;
    }

    private static boolean pushFloat(ExecutionContext ctx) {
        ctx.push(Datum.of(Float.intBitsToFloat(ctx.getArgument())));
        return true;
    }

    private static boolean pushCons(ExecutionContext ctx) {
        List<ScriptChunk.LiteralEntry> literals = ctx.getLiterals();
        int arg = ctx.getArgument();
        if (arg >= 0 && arg < literals.size()) {
            ScriptChunk.LiteralEntry lit = literals.get(arg);
            Datum value = switch (lit.type()) {
                case 1 -> Datum.of((String) lit.value());
                case 4 -> Datum.of((Integer) lit.value());
                case 9 -> Datum.of((Double) lit.value());
                default -> Datum.VOID;
            };

            if (value.toStr().equalsIgnoreCase("object.manager.class")) {
                var te = 3;
            }

            ctx.push(value);
        } else {
            ctx.push(Datum.VOID);
        }
        return true;
    }

    private static boolean pushSymb(ExecutionContext ctx) {
        String name = ctx.resolveName(ctx.getArgument());
        ctx.push(Datum.symbol(name));
        return true;
    }

    private static boolean swap(ExecutionContext ctx) {
        ctx.swap();
        return true;
    }

    private static boolean pop(ExecutionContext ctx) {
        ctx.pop();
        return true;
    }

    private static boolean peek(ExecutionContext ctx) {
        Datum value = ctx.peek(ctx.getArgument());
        ctx.push(value);
        return true;
    }
}
