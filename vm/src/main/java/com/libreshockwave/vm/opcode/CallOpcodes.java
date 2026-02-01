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
            Datum argListDatum = ctx.pop();
            boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
            List<Datum> args = getArgs(argListDatum);
            Datum result = ctx.executeHandler(ctx.getScript(), targetHandler, args, ctx.getReceiver());
            if (!noRet) {
                ctx.push(result);
            }
        }
        return true;
    }

    private static boolean extCall(ExecutionContext ctx) {
        String handlerName = ctx.resolveName(ctx.getArgument());
        Datum argListDatum = ctx.pop();
        boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = getArgs(argListDatum);

        Datum result;
        if (ctx.isBuiltin(handlerName)) {
            result = ctx.invokeBuiltin(handlerName, args);
        } else {
            HandlerRef ref = ctx.findHandler(handlerName);
            if (ref != null) {
                result = ctx.executeHandler(ref.script(), ref.handler(), args, null);
            } else {
                result = Datum.VOID;
            }
        }
        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    private static boolean objCall(ExecutionContext ctx) {
        String methodName = ctx.resolveName(ctx.getArgument());
        Datum argListDatum = ctx.pop();
        boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = getArgs(argListDatum);
        Datum target = args.isEmpty() ? Datum.VOID : args.remove(0);
        // TODO: implement proper object method calls
        Datum result = Datum.VOID;
        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    /**
     * Extract arguments from an arglist datum.
     * Arguments are stored directly in the ArgList/ArgListNoRet items.
     */
    private static List<Datum> getArgs(Datum argListDatum) {
        if (argListDatum instanceof Datum.ArgList al) {
            return new java.util.ArrayList<>(al.items());
        } else if (argListDatum instanceof Datum.ArgListNoRet al) {
            return new java.util.ArrayList<>(al.items());
        } else {
            // Fallback - shouldn't happen with correct bytecode
            return new java.util.ArrayList<>();
        }
    }
}
