package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;

import java.util.Map;

/**
 * Property access opcodes.
 */
public final class PropertyOpcodes {

    private PropertyOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.GET_PROP, PropertyOpcodes::getProp);
        handlers.put(Opcode.SET_PROP, PropertyOpcodes::setProp);
        handlers.put(Opcode.GET_MOVIE_PROP, PropertyOpcodes::getMovieProp);
        handlers.put(Opcode.SET_MOVIE_PROP, PropertyOpcodes::setMovieProp);
        handlers.put(Opcode.GET_OBJ_PROP, PropertyOpcodes::getObjProp);
        handlers.put(Opcode.SET_OBJ_PROP, PropertyOpcodes::setObjProp);
        handlers.put(Opcode.THE_BUILTIN, PropertyOpcodes::theBuiltin);
    }

    private static boolean getProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        if (ctx.getReceiver() instanceof Datum.ScriptInstance si) {
            ctx.push(si.properties().getOrDefault(propName, Datum.VOID));
        } else {
            ctx.push(Datum.VOID);
        }
        return true;
    }

    private static boolean setProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum value = ctx.pop();
        if (ctx.getReceiver() instanceof Datum.ScriptInstance si) {
            si.properties().put(propName, value);
            ctx.tracePropertySet(propName, value);
        }
        return true;
    }

    private static boolean getMovieProp(ExecutionContext ctx) {
        // TODO: implement movie property access
        ctx.push(Datum.VOID);
        return true;
    }

    private static boolean setMovieProp(ExecutionContext ctx) {
        // TODO: implement movie property setting
        ctx.pop();
        return true;
    }

    private static boolean getObjProp(ExecutionContext ctx) {
        Datum obj = ctx.pop();
        // TODO: implement object property access
        ctx.push(Datum.VOID);
        return true;
    }

    private static boolean setObjProp(ExecutionContext ctx) {
        Datum value = ctx.pop();
        Datum obj = ctx.pop();
        // TODO: implement object property setting
        return true;
    }

    private static boolean theBuiltin(ExecutionContext ctx) {
        // TODO: implement the builtin property access
        ctx.push(Datum.VOID);
        return true;
    }
}
