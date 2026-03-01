package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoException;
import com.libreshockwave.vm.builtin.TimeoutBuiltins;
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
            case Datum.TimeoutRef ref -> TimeoutBuiltins.handleMethod(ref, methodName, args);
            case Datum.VarRef varRef -> handleVarRefMethod(ctx, varRef, methodName, args);
            case Datum.ChunkRef chunkRef -> handleChunkRefMethod(ctx, chunkRef, methodName, args);
            case Datum.MovieRef m -> {
                // Method calls on _movie - try as builtin with args
                if (ctx.isBuiltin(methodName)) {
                    yield ctx.invokeBuiltin(methodName, args);
                }
                yield Datum.VOID;
            }
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
     * Handle method calls on a VarRef (mutable variable reference).
     * Supports getPropRef and getProp to create chunk references or extract chunk values.
     */
    private static Datum handleVarRefMethod(ExecutionContext ctx, Datum.VarRef varRef,
                                            String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        // Resolve the variable value
        Datum value = resolveVarRef(ctx, varRef);
        String str = value.toStr();

        return switch (method) {
            case "getpropref" -> {
                // getPropRef(#chunkType, startIdx, endIdx)
                if (args.size() < 2) yield Datum.VOID;
                String chunkType = args.get(0) instanceof Datum.Symbol s ? s.name().toLowerCase() : "char";
                int start = args.get(1).toInt();
                int end = args.size() >= 3 ? args.get(2).toInt() : start;
                yield new Datum.ChunkRef(varRef.varType(), varRef.rawIndex(), chunkType, start, end);
            }
            case "getprop" -> {
                // getProp(#chunkType, startIdx, endIdx) - returns the value (no mutation)
                if (args.size() < 2) yield Datum.EMPTY_STRING;
                String chunkType = args.get(0) instanceof Datum.Symbol s ? s.name().toLowerCase() : "char";
                int start = args.get(1).toInt();
                int end = args.size() >= 3 ? args.get(2).toInt() : start;
                yield Datum.of(getStringChunk(str, chunkType, start, end));
            }
            default -> {
                // Delegate to string dispatch for other methods
                if (value instanceof Datum.Str s) {
                    yield StringMethodDispatcher.dispatch(s, methodName, args);
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Handle method calls on a ChunkRef (chunk range within a variable).
     * Supports delete to remove the referenced chunk from the original variable.
     */
    private static Datum handleChunkRefMethod(ExecutionContext ctx, Datum.ChunkRef chunkRef,
                                              String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "delete" -> {
                // Read current value
                Datum.VarRef varRef = new Datum.VarRef(chunkRef.varType(), chunkRef.rawIndex());
                Datum value = resolveVarRef(ctx, varRef);
                String str = value.toStr();

                // Delete the chunk range
                String newStr = deleteChunkRange(str, chunkRef.chunkType(), chunkRef.start(), chunkRef.end());

                // Write back to the original variable
                setVarRef(ctx, varRef, Datum.of(newStr));
                yield Datum.VOID;
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Resolve a VarRef to its current value.
     */
    private static Datum resolveVarRef(ExecutionContext ctx, Datum.VarRef varRef) {
        int variableMultiplier = ctx.getVariableMultiplier();
        int index = varRef.rawIndex() / variableMultiplier;
        return switch (varRef.varType()) {
            case 0x5 -> ctx.getLocal(index);   // LOCAL
            case 0x4 -> ctx.getParam(index);   // ARG/PARAM
            case 0x3 -> {                       // PROPERTY
                Datum receiver = ctx.getReceiver();
                if (receiver instanceof Datum.ScriptInstance si) {
                    String propName = ctx.resolveName(varRef.rawIndex());
                    Datum v = si.properties().get(propName);
                    yield v != null ? v : Datum.VOID;
                }
                yield Datum.VOID;
            }
            case 0x1, 0x2 -> {                 // GLOBAL
                String name = ctx.resolveName(varRef.rawIndex());
                yield ctx.getGlobal(name);
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Set the value of a VarRef.
     */
    private static void setVarRef(ExecutionContext ctx, Datum.VarRef varRef, Datum value) {
        int variableMultiplier = ctx.getVariableMultiplier();
        int index = varRef.rawIndex() / variableMultiplier;
        switch (varRef.varType()) {
            case 0x5 -> ctx.setLocal(index, value);   // LOCAL
            case 0x4 -> ctx.setParam(index, value);   // ARG/PARAM
            case 0x3 -> {                               // PROPERTY
                Datum receiver = ctx.getReceiver();
                if (receiver instanceof Datum.ScriptInstance si) {
                    String propName = ctx.resolveName(varRef.rawIndex());
                    si.properties().put(propName, value);
                }
            }
            case 0x1, 0x2 -> {                         // GLOBAL
                String name = ctx.resolveName(varRef.rawIndex());
                ctx.setGlobal(name, value);
            }
        }
    }

    /**
     * Get a chunk range from a string.
     */
    private static String getStringChunk(String str, String chunkType, int start, int end) {
        if (str.isEmpty() || start < 1) return "";
        return switch (chunkType) {
            case "char" -> {
                int s = Math.max(0, start - 1);
                int e = Math.min(str.length(), end);
                if (s >= str.length() || s >= e) yield "";
                yield str.substring(s, e);
            }
            default -> str; // Other chunk types can be added as needed
        };
    }

    /**
     * Delete a chunk range from a string and return the result.
     */
    private static String deleteChunkRange(String str, String chunkType, int start, int end) {
        if (str.isEmpty() || start < 1) return str;
        return switch (chunkType) {
            case "char" -> {
                int s = Math.max(0, start - 1);
                int e = Math.min(str.length(), end);
                if (s >= str.length()) yield str;
                yield str.substring(0, s) + str.substring(e);
            }
            default -> str;
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
