package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoException;
import com.libreshockwave.vm.opcode.dispatch.ListMethodDispatcher;
import com.libreshockwave.vm.opcode.dispatch.PropListMethodDispatcher;
import com.libreshockwave.vm.opcode.dispatch.ScriptInstanceMethodDispatcher;
import com.libreshockwave.vm.opcode.dispatch.StringMethodDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Function call opcodes.
 */
public final class CallOpcodes {

    private CallOpcodes() {}

    /**
     * Safely execute a handler, catching exceptions and returning VOID on error.
     * This matches dirplayer-rs behavior where errors stop execution but don't propagate
     * as exceptions that could trigger recursive error handling.
     */
    static Datum safeExecuteHandler(ExecutionContext ctx, ScriptChunk script,
                                     ScriptChunk.Handler handler, List<Datum> args, Datum receiver) {
        try {
            return ctx.executeHandler(script, handler, args, receiver);
        } catch (LingoException e) {
            // Log the error and set error state to prevent further handler execution
            // This matches dirplayer-rs stop() behavior
            System.err.println("[Lingo] Error in " + script.getHandlerName(handler) + ": " + e.getMessage());
            ctx.setErrorState(true);
            return Datum.VOID;
        }
    }

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
            Datum result = safeExecuteHandler(ctx, ctx.getScript(), targetHandler, args, ctx.getReceiver());
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
                result = safeExecuteHandler(ctx, ref.script(), ref.handler(), args, null);
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

        Datum result = dispatchMethod(ctx, target, methodName, args);

        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    /**
     * Dispatch a method call to the appropriate handler based on target type.
     */
    private static Datum dispatchMethod(ExecutionContext ctx, Datum target,
                                        String methodName, List<Datum> args) {
        return switch (target) {
            case Datum.List list -> ListMethodDispatcher.dispatch(list, methodName, args);
            case Datum.PropList propList -> PropListMethodDispatcher.dispatch(propList, methodName, args);
            case Datum.ScriptInstance instance -> ScriptInstanceMethodDispatcher.dispatch(ctx, instance, methodName, args);
            case Datum.ScriptRef scriptRef -> handleScriptRefMethod(ctx, scriptRef, methodName, args);
            case Datum.Point point -> handlePointMethod(point, methodName, args);
            case Datum.Rect rect -> handleRectMethod(rect, methodName, args);
            case Datum.Str str -> StringMethodDispatcher.dispatch(str, methodName, args);
            default -> {
                // Try to find the method as a global handler (with target as first arg)
                if (ctx.isBuiltin(methodName)) {
                    List<Datum> fullArgs = new ArrayList<>();
                    fullArgs.add(target);
                    fullArgs.addAll(args);
                    yield ctx.invokeBuiltin(methodName, fullArgs);
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Handle method calls on script references (e.g., calling new() on a script).
     */
    private static Datum handleScriptRefMethod(ExecutionContext ctx, Datum.ScriptRef scriptRef,
                                               String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        if ("new".equals(method)) {
            // Create a new instance of the script
            List<Datum> fullArgs = new ArrayList<>();
            fullArgs.add(scriptRef);
            fullArgs.addAll(args);
            return ctx.invokeBuiltin("new", fullArgs);
        }
        return Datum.VOID;
    }

    /**
     * Handle method calls on point values.
     */
    private static Datum handlePointMethod(Datum.Point point, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(point.x());
                    case 2 -> Datum.of(point.y());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on rect values.
     */
    private static Datum handleRectMethod(Datum.Rect rect, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(rect.left());
                    case 2 -> Datum.of(rect.top());
                    case 3 -> Datum.of(rect.right());
                    case 4 -> Datum.of(rect.bottom());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Extract arguments from an arglist datum.
     * Arguments are stored directly in the ArgList/ArgListNoRet items.
     */
    private static List<Datum> getArgs(Datum argListDatum) {
        if (argListDatum instanceof Datum.ArgList al) {
            return new ArrayList<>(al.items());
        } else if (argListDatum instanceof Datum.ArgListNoRet al) {
            return new ArrayList<>(al.items());
        } else {
            // Fallback - shouldn't happen with correct bytecode
            return new ArrayList<>();
        }
    }
}
