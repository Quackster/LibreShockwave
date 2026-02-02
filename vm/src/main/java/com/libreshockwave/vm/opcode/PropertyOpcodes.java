package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.lingo.StringChunkType;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;
import com.libreshockwave.vm.builtin.SpritePropertyProvider;
import com.libreshockwave.vm.builtin.XtraBuiltins;
import com.libreshockwave.vm.util.StringChunkUtils;

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
        handlers.put(Opcode.GET, PropertyOpcodes::get);
        handlers.put(Opcode.SET, PropertyOpcodes::set);
        handlers.put(Opcode.GET_FIELD, PropertyOpcodes::getField);
    }

    private static boolean getProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        if (ctx.getReceiver() instanceof Datum.ScriptInstance si) {
            // Walk the ancestor chain to find the property
            Datum value = getPropertyFromAncestorChain(si, propName);
            ctx.push(value);
        } else {
            ctx.push(Datum.VOID);
        }
        return true;
    }

    /**
     * Get a property from a script instance, walking the ancestor chain if not found.
     */
    private static Datum getPropertyFromAncestorChain(Datum.ScriptInstance instance, String propName) {
        Datum.ScriptInstance current = instance;
        for (int i = 0; i < 100; i++) { // Safety limit
            if (current.properties().containsKey(propName)) {
                return current.properties().get(propName);
            }

            // Try ancestor
            Datum ancestor = current.properties().get("ancestor");
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }
        return Datum.VOID;
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

        // Handle special execution-context-dependent properties first
        if ("paramCount".equalsIgnoreCase(propName)) {
            // paramCount returns the number of arguments passed to the current handler
            ctx.push(Datum.of(ctx.getScope().getArguments().size()));
            return true;
        }

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
            case Datum.CastLibRef clr -> getCastLibProp(clr, propName);
            case Datum.CastMemberRef cmr -> getCastMemberProp(cmr, propName);
            case Datum.ScriptInstance si -> getPropertyFromAncestorChain(si, propName);
            case Datum.XtraInstance xi -> XtraBuiltins.getProperty(xi, propName);
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
            case Datum.CastLibRef clr -> setCastLibProp(clr, propName, value);
            case Datum.CastMemberRef cmr -> setCastMemberProp(cmr, propName, value);
            case Datum.ScriptInstance si -> {
                si.properties().put(propName, value);
                ctx.tracePropertySet(propName, value);
            }
            case Datum.XtraInstance xi -> XtraBuiltins.setProperty(xi, propName, value);
            case Datum.PropList pl -> pl.properties().put(propName, value);
            default -> { /* ignore */ }
        }

        return true;
    }

    /**
     * Get a property from a cast library reference.
     */
    private static Datum getCastLibProp(Datum.CastLibRef clr, String propName) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }
        return provider.getCastLibProp(clr.castLibNumber(), propName);
    }

    /**
     * Set a property on a cast library reference.
     */
    private static boolean setCastLibProp(Datum.CastLibRef clr, String propName, Datum value) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return false;
        }
        return provider.setCastLibProp(clr.castLibNumber(), propName, value);
    }

    /**
     * Get a property from a cast member reference.
     */
    private static Datum getCastMemberProp(Datum.CastMemberRef cmr, String propName) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            // Return basic reference properties without provider
            String prop = propName.toLowerCase();
            return switch (prop) {
                case "number" -> Datum.of((cmr.castLib() << 16) | (cmr.member() & 0xFFFF));
                case "membernum" -> Datum.of(cmr.member());
                case "castlibnum" -> Datum.of(cmr.castLib());
                case "castlib" -> new Datum.CastLibRef(cmr.castLib());
                default -> Datum.VOID;
            };
        }

        // Delegate to provider for full property access with lazy loading
        return provider.getMemberProp(cmr.castLib(), cmr.member(), propName);
    }

    /**
     * Set a property on a cast member reference.
     */
    private static boolean setCastMemberProp(Datum.CastMemberRef cmr, String propName, Datum value) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return false;
        }

        return provider.setMemberProp(cmr.castLib(), cmr.member(), propName, value);
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

    /**
     * GET opcode (0x5C) - Get a property by ID.
     * Stack: [..., propertyId] -> [..., value]
     * For some property types, also pops a target (sprite number, etc.)
     */
    private static boolean get(ExecutionContext ctx) {
        int propertyId = ctx.pop().toInt();
        int propertyType = ctx.getArgument();

        MoviePropertyProvider movieProvider = MoviePropertyProvider.getProvider();
        SpritePropertyProvider spriteProvider = SpritePropertyProvider.getProvider();

        Datum result = switch (propertyType) {
            case 0x00 -> {
                // Movie property or last chunk
                if (propertyId <= 0x0b) {
                    String propName = PropertyIdMappings.getMoviePropName(propertyId);
                    if (propName != null && movieProvider != null) {
                        yield movieProvider.getMovieProp(propName);
                    }
                    yield Datum.VOID;
                } else {
                    // Last chunk: propertyId 0x0c=item, 0x0d=word, 0x0e=char, 0x0f=line
                    String str = ctx.pop().toStr();
                    int chunkTypeCode = propertyId - 0x0b;
                    try {
                        StringChunkType chunkType = StringChunkType.fromCode(chunkTypeCode);
                        char delimiter = movieProvider != null ? movieProvider.getItemDelimiter() : ',';
                        String lastChunk = StringChunkUtils.getLastChunk(str, chunkType, delimiter);
                        yield Datum.of(lastChunk);
                    } catch (IllegalArgumentException e) {
                        yield Datum.VOID;
                    }
                }
            }
            case 0x01 -> {
                // Number of chunks: propertyId 0x01=item, 0x02=word, 0x03=char, 0x04=line
                String str = ctx.pop().toStr();
                try {
                    StringChunkType chunkType = StringChunkType.fromCode(propertyId);
                    char delimiter = movieProvider != null ? movieProvider.getItemDelimiter() : ',';
                    int count = StringChunkUtils.countChunks(str, chunkType, delimiter);
                    yield Datum.of(count);
                } catch (IllegalArgumentException e) {
                    yield Datum.VOID;
                }
            }
            case 0x06 -> {
                // Sprite property
                String propName = PropertyIdMappings.getSpritePropName(propertyId);
                int spriteNum = ctx.pop().toInt();
                if (propName != null && spriteProvider != null) {
                    yield spriteProvider.getSpriteProp(spriteNum, propName);
                }
                yield Datum.VOID;
            }
            case 0x07 -> {
                // Animation property
                String propName = PropertyIdMappings.getAnimPropName(propertyId);
                if (propName != null && movieProvider != null) {
                    yield movieProvider.getMovieProp(propName);
                }
                yield Datum.VOID;
            }
            default -> Datum.VOID;
        };

        ctx.push(result);
        return true;
    }

    /**
     * GET_FIELD opcode (0x1B) - Get the text content of a field.
     * Stack: [..., fieldNameOrNum, castId?] -> [..., fieldText]
     * For Director 5+, pops both castId and fieldNameOrNum.
     * For earlier versions, just pops fieldNameOrNum.
     */
    private static boolean getField(ExecutionContext ctx) {
        // Pop the cast ID first (for D5+), then the field identifier
        // Note: The order depends on how the bytecode was compiled
        Datum castIdDatum = ctx.pop();
        Datum fieldNameOrNum = ctx.pop();

        // Determine cast ID (0 means search all casts)
        int castId = castIdDatum.toInt();

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            ctx.push(Datum.EMPTY_STRING);
            return true;
        }

        // Get the field value
        Object identifier = fieldNameOrNum instanceof Datum.Str s ? s.value()
                : fieldNameOrNum instanceof Datum.Int i ? i.value()
                : fieldNameOrNum.toStr();

        String fieldValue = provider.getFieldValue(identifier, castId);
        ctx.push(Datum.of(fieldValue));
        return true;
    }

    /**
     * SET opcode (0x5D) - Set a property by ID.
     * Stack: [..., value, propertyId] -> [...]
     * For some property types, also pops a target (sprite number, etc.)
     */
    private static boolean set(ExecutionContext ctx) {
        int propertyId = ctx.pop().toInt();
        Datum value = ctx.pop();
        int propertyType = ctx.getArgument();

        MoviePropertyProvider movieProvider = MoviePropertyProvider.getProvider();
        SpritePropertyProvider spriteProvider = SpritePropertyProvider.getProvider();

        switch (propertyType) {
            case 0x00 -> {
                // Movie property
                if (propertyId <= 0x0b) {
                    String propName = PropertyIdMappings.getMoviePropName(propertyId);
                    if (propName != null && movieProvider != null) {
                        movieProvider.setMovieProp(propName, value);
                    }
                }
            }
            case 0x04 -> {
                // Sound channel property
                String propName = PropertyIdMappings.getSoundPropName(propertyId);
                int channelNum = ctx.pop().toInt();
                // Sound channel properties not yet implemented
            }
            case 0x06 -> {
                // Sprite property
                String propName = PropertyIdMappings.getSpritePropName(propertyId);
                int spriteNum = ctx.pop().toInt();
                if (propName != null && spriteProvider != null) {
                    spriteProvider.setSpriteProp(spriteNum, propName, value);
                }
            }
            case 0x07 -> {
                // Animation property
                String propName = PropertyIdMappings.getAnimPropName(propertyId);
                if (propName != null && movieProvider != null) {
                    movieProvider.setMovieProp(propName, value);
                }
            }
        }

        return true;
    }
}
