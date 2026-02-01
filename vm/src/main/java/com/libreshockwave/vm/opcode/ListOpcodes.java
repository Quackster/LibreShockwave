package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * List and property list opcodes.
 */
public final class ListOpcodes {

    private ListOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.PUSH_LIST, ListOpcodes::pushList);
        handlers.put(Opcode.PUSH_PROP_LIST, ListOpcodes::pushPropList);
        handlers.put(Opcode.PUSH_ARG_LIST, ListOpcodes::pushArgList);
        handlers.put(Opcode.PUSH_ARG_LIST_NO_RET, ListOpcodes::pushArgListNoRet);
    }

    private static boolean pushList(ExecutionContext ctx) {
        int count = ctx.getArgument();
        List<Datum> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(0, ctx.pop());
        }
        ctx.push(Datum.list(items));
        return true;
    }

    private static boolean pushPropList(ExecutionContext ctx) {
        int count = ctx.getArgument();
        Map<String, Datum> props = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            Datum value = ctx.pop();
            Datum key = ctx.pop();
            String keyStr = key instanceof Datum.Symbol s ? s.name() : key.toStr();
            props.put(keyStr, value);
        }
        ctx.push(Datum.propList(props));
        return true;
    }

    private static boolean pushArgList(ExecutionContext ctx) {
        int count = ctx.getArgument();
        List<Datum> items = ctx.popArgs(count);
        ctx.push(new Datum.ArgList(items));
        return true;
    }

    private static boolean pushArgListNoRet(ExecutionContext ctx) {
        int count = ctx.getArgument();
        List<Datum> items = ctx.popArgs(count);
        ctx.push(new Datum.ArgListNoRet(items));
        return true;
    }
}
