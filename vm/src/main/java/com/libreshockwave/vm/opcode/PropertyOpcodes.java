package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.CastLibBuiltins;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;

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
        String propName = ctx.resolveName(ctx.getArgument());
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();

        if (provider != null) {
            Datum value = provider.getMovieProp(propName);
            ctx.push(value);
        } else {
            // Fallback for common constants when no provider is available
            ctx.push(getBuiltinConstant(propName));
        }
        return true;
    }

    private static boolean setMovieProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum value = ctx.pop();
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();

        if (provider != null) {
            provider.setMovieProp(propName, value);
        }
        return true;
    }

    /**
     * Get built-in constants that don't require a provider.
     */
    private static Datum getBuiltinConstant(String propName) {
        return switch (propName.toLowerCase()) {
            case "pi" -> Datum.of(Math.PI);
            case "true" -> Datum.TRUE;
            case "false" -> Datum.FALSE;
            case "void" -> Datum.VOID;
            case "empty", "emptystring" -> Datum.EMPTY_STRING;
            case "return" -> Datum.of("\r");
            case "enter" -> Datum.of("\n");
            case "tab" -> Datum.of("\t");
            case "quote" -> Datum.of("\"");
            case "backspace" -> Datum.of("\b");
            case "space" -> Datum.of(" ");
            default -> Datum.VOID;
        };
    }

    private static boolean getObjProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum obj = ctx.pop();

        Datum result = switch (obj) {
            case Datum.CastLibRef clr -> CastLibBuiltins.getCastLibProp(clr, propName);
            case Datum.CastMemberRef cmr -> getCastMemberProp(cmr, propName);
            case Datum.ScriptInstance si -> si.properties().getOrDefault(propName, Datum.VOID);
            case Datum.XtraInstance xi -> {
                // TODO: Xtra instance property access
                yield Datum.VOID;
            }
            case Datum.PropList pl -> pl.properties().getOrDefault(propName, Datum.VOID);
            default -> Datum.VOID;
        };

        ctx.push(result);
        return true;
    }

    private static boolean setObjProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum value = ctx.pop();
        Datum obj = ctx.pop();

        switch (obj) {
            case Datum.CastLibRef clr -> CastLibBuiltins.setCastLibProp(clr, propName, value);
            case Datum.CastMemberRef cmr -> setCastMemberProp(cmr, propName, value);
            case Datum.ScriptInstance si -> {
                si.properties().put(propName, value);
                ctx.tracePropertySet(propName, value);
            }
            case Datum.PropList pl -> pl.properties().put(propName, value);
            default -> { /* ignore */ }
        }

        return true;
    }

    /**
     * Get a property from a cast member reference.
     */
    private static Datum getCastMemberProp(Datum.CastMemberRef cmr, String propName) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }

        String prop = propName.toLowerCase();

        // Common cast member properties
        return switch (prop) {
            case "number" -> Datum.of(cmr.member());
            case "castlibnum", "castlib" -> Datum.of(cmr.castLib());
            // Other properties need to be looked up from the actual cast member
            // TODO: implement full cast member property access
            default -> {
                System.err.println("[PropertyOpcodes] Unknown member property: " + propName);
                yield Datum.VOID;
            }
        };
    }

    /**
     * Set a property on a cast member reference.
     */
    private static boolean setCastMemberProp(Datum.CastMemberRef cmr, String propName, Datum value) {
        // Most cast member properties are read-only during playback
        System.err.println("[PropertyOpcodes] Cannot set member property: " + propName);
        return false;
    }

    private static boolean theBuiltin(ExecutionContext ctx) {
        // THE_BUILTIN is used for "the" expressions that take an argument
        // e.g., "the name of member 1"
        String propName = ctx.resolveName(ctx.getArgument());

        // First try movie properties
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider != null) {
            Datum value = provider.getMovieProp(propName);
            if (!value.isVoid()) {
                ctx.push(value);
                return true;
            }
        }

        // Fall back to built-in constants
        ctx.push(getBuiltinConstant(propName));
        return true;
    }
}
