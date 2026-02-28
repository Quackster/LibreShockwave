package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.builtin.CastLibProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

        // Object creation
        handlers.put(Opcode.NEW_OBJ, StackOpcodes::newObj);
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
        int arg = ctx.getArgument() / ctx.getVariableMultiplier();
        if (arg >= 0 && arg < literals.size()) {
            ScriptChunk.LiteralEntry lit = literals.get(arg);
            Datum value = switch (lit.type()) {
                case 1 -> Datum.of((String) lit.value());
                case 4 -> Datum.of((Integer) lit.value());
                case 9 -> Datum.of((Double) lit.value());
                default -> Datum.VOID;
            };

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
        int count = ctx.getArgument();
        if (count <= 1) {
            ctx.pop();
        } else {
            for (int i = 0; i < count; i++) {
                ctx.pop();
            }
        }
        return true;
    }

    private static boolean peek(ExecutionContext ctx) {
        Datum value = ctx.peek(ctx.getArgument());
        ctx.push(value);
        return true;
    }

    private static int nextInstanceId = 1;

    /**
     * NEW_OBJ (0x73) - Create a new script instance.
     * The argument is the name ID of the object type (typically "script").
     * Stack: [..., arglist] -> [..., scriptInstance]
     */
    private static boolean newObj(ExecutionContext ctx) {
        String objType = ctx.resolveName(ctx.getArgument());

        if (!"script".equalsIgnoreCase(objType)) {
            System.err.println("[WARN] NEW_OBJ: Cannot create non-script: " + objType);
            ctx.push(Datum.VOID);
            return true;
        }

        // Pop the argument list
        Datum argListDatum = ctx.pop();
        List<Datum> args;
        if (argListDatum instanceof Datum.ArgList al) {
            args = al.items();
        } else if (argListDatum instanceof Datum.ArgListNoRet al) {
            args = al.items();
        } else {
            args = List.of();
        }

        if (args.isEmpty()) {
            System.err.println("[WARN] NEW_OBJ: requires at least a script name argument");
            ctx.push(Datum.VOID);
            return true;
        }

        // First arg is the script name (or a script reference)
        Datum firstArg = args.get(0);
        String scriptName;
        if (firstArg instanceof Datum.Str s) {
            scriptName = s.value();
        } else if (firstArg instanceof Datum.Symbol sym) {
            scriptName = sym.name();
        } else {
            scriptName = firstArg.toStr();
        }

        // Find the script by name to get a member reference
        CastLibProvider provider = CastLibProvider.getProvider();
        Datum memberRef = null;
        if (provider != null) {
            memberRef = provider.getMemberByName(0, scriptName);
        }

        // Create the script instance
        int instanceId = nextInstanceId++;
        Map<String, Datum> properties = new LinkedHashMap<>();

        // Store the script reference for method dispatch
        if (memberRef instanceof Datum.CastMemberRef cmr) {
            properties.put(Datum.PROP_SCRIPT_REF, new Datum.ScriptRef(cmr.castLib(), cmr.member()));

            // Pre-initialize declared properties to VOID (matching dirplayer-rs behavior)
            List<String> propNames = provider.getScriptPropertyNames(cmr.castLib(), cmr.member());
            for (String name : propNames) {
                properties.put(name, Datum.VOID);
            }
        }

        Datum.ScriptInstance instance = new Datum.ScriptInstance(instanceId, properties);

        // Get extra args for the "new" handler (skip the script name)
        List<Datum> extraArgs = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            extraArgs.add(args.get(i));
        }

        // Call the "new" handler on the script instance if it exists
        if (memberRef instanceof Datum.CastMemberRef cmr) {
            CastLibProvider.HandlerLocation loc = provider.findHandlerInScript(
                cmr.castLib(), cmr.member(), "new");
            if (loc != null) {
                try {
                    ctx.executeHandler(
                        (com.libreshockwave.chunks.ScriptChunk) loc.script(),
                        (ScriptChunk.Handler) loc.handler(),
                        extraArgs,
                        instance);
                } catch (Exception e) {
                    System.err.println("[WARN] NEW_OBJ: error calling 'new' handler: " + e.getMessage());
                }
            }
        }

        ctx.push(instance);
        return true;
    }
}
