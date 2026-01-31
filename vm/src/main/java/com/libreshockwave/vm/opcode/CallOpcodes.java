package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;

/**
 * Function call opcodes.
 */
public final class CallOpcodes {

    private CallOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.LOCAL_CALL, CallOpcodes::localCall);
        handlers.put(Opcode.EXT_CALL, CallOpcodes::extCall);
        handlers.put(Opcode.OBJ_CALL, CallOpcodes::objCall);
    }

    private static boolean localCall(ExecutionContext ctx) {
        ScriptChunk.Handler targetHandler = ctx.findLocalHandler(ctx.getArgument());
        if (targetHandler != null) {
            Datum argCountDatum = ctx.pop();
            int argCount = argCountDatum.toInt();
            List<Datum> args = ctx.popArgs(argCount);
            Datum result = ctx.executeHandler(ctx.getScript(), targetHandler, args, ctx.getReceiver());
            ctx.push(result);
        } else {
            ctx.push(Datum.VOID);
        }
        return true;
    }

    private static boolean extCall(ExecutionContext ctx) {
        String handlerName = ctx.resolveName(ctx.getArgument());
        Datum argCountDatum = ctx.pop();
        int argCount = argCountDatum.toInt();
        List<Datum> args = ctx.popArgs(argCount);

        if (ctx.isBuiltin(handlerName)) {
            Datum result = ctx.invokeBuiltin(handlerName, args);
            ctx.push(result);
        } else {
            HandlerRef ref = ctx.findHandler(handlerName);
            if (ref != null) {
                Datum result = ctx.executeHandler(ref.script(), ref.handler(), args, null);
                ctx.push(result);
            } else {
                ctx.push(Datum.VOID);
            }
        }
        return true;
    }

    private static boolean objCall(ExecutionContext ctx) {
        String methodName = ctx.resolveName(ctx.getArgument());
        Datum argCountDatum = ctx.pop();
        int argCount = argCountDatum.toInt();
        List<Datum> args = ctx.popArgs(argCount);
        Datum target = args.isEmpty() ? Datum.VOID : args.remove(0);
        // TODO: implement proper object method calls
        ctx.push(Datum.VOID);
        return true;
    }
}
