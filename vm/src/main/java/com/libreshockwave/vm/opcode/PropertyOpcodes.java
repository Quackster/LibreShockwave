package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.lingo.StringChunkType;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;
import com.libreshockwave.vm.builtin.SpritePropertyProvider;
import com.libreshockwave.vm.builtin.TimeoutBuiltins;
import com.libreshockwave.vm.builtin.XtraBuiltins;
import com.libreshockwave.vm.opcode.dispatch.ImageMethodDispatcher;
import com.libreshockwave.vm.util.AncestorChainWalker;
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
        handlers.put(Opcode.GET_CHAINED_PROP, PropertyOpcodes::getChainedProp);
        handlers.put(Opcode.GET_TOP_LEVEL_PROP, PropertyOpcodes::getTopLevelProp);
    }

    private static boolean getProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        if (ctx.getReceiver() instanceof Datum.ScriptInstance si) {
            // Walk the ancestor chain to find the property
            Datum value = AncestorChainWalker.getProperty(si, propName);
            ctx.push(value);
        } else {
            ctx.push(Datum.VOID);
        }
        return true;
    }

    private static boolean setProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum value = ctx.pop();
        if (ctx.getReceiver() instanceof Datum.ScriptInstance si) {
            AncestorChainWalker.setProperty(si, propName, value);
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
            case Datum.CastLibRef clr -> getCastLibProp(clr, propName);
            case Datum.CastMemberRef cmr -> getCastMemberProp(cmr, propName);
            case Datum.ScriptInstance si -> AncestorChainWalker.getProperty(si, propName);
            case Datum.XtraInstance xi -> XtraBuiltins.getProperty(xi, propName);
            case Datum.TimeoutRef tr -> TimeoutBuiltins.getProperty(tr, propName);
            case Datum.PropList pl -> getPropListProp(pl, propName);
            case Datum.List list -> getListProp(list, propName);
            case Datum.Str str -> getStringProp(str, propName);
            case Datum.MovieRef m -> {
                MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
                yield provider != null ? provider.getMovieProp(propName) : Datum.VOID;
            }
            case Datum.PlayerRef p -> getBuiltinConstant(propName);
            case Datum.SpriteRef sr -> {
                SpritePropertyProvider spriteProvider = SpritePropertyProvider.getProvider();
                yield spriteProvider != null ? spriteProvider.getSpriteProp(sr.channel(), propName) : Datum.VOID;
            }
            case Datum.StageRef s -> {
                MoviePropertyProvider stageProvider = MoviePropertyProvider.getProvider();
                yield stageProvider != null ? stageProvider.getStageProp(propName) : Datum.VOID;
            }
            case Datum.ImageRef ir -> ImageMethodDispatcher.getProperty(ir, propName);
            case Datum.Point point -> {
                yield switch (propName.toLowerCase()) {
                    case "loch", "x" -> Datum.of(point.x());
                    case "locv", "y" -> Datum.of(point.y());
                    default -> Datum.VOID;
                };
            }
            case Datum.Rect rect -> {
                yield switch (propName.toLowerCase()) {
                    case "left" -> Datum.of(rect.left());
                    case "top" -> Datum.of(rect.top());
                    case "right" -> Datum.of(rect.right());
                    case "bottom" -> Datum.of(rect.bottom());
                    case "width" -> Datum.of(rect.right() - rect.left());
                    case "height" -> Datum.of(rect.bottom() - rect.top());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };

        ctx.push(result);
        return true;
    }

    /**
     * Get a built-in property from a list.
     */
    private static Datum getListProp(Datum.List list, String propName) {
        String prop = propName.toLowerCase();
        return switch (prop) {
            case "count", "length" -> Datum.of(list.items().size());
            case "ilk" -> Datum.symbol("list");
            default -> {
                // Try numeric index (1-based)
                try {
                    int index = Integer.parseInt(propName) - 1;
                    if (index >= 0 && index < list.items().size()) {
                        yield list.items().get(index);
                    }
                } catch (NumberFormatException e) {
                    // not a number
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Get a property from a PropList, supporting built-in properties (count, ilk)
     * as well as key lookup.
     */
    private static Datum getPropListProp(Datum.PropList pl, String propName) {
        String prop = propName.toLowerCase();
        return switch (prop) {
            case "count", "length" -> Datum.of(pl.properties().size());
            case "ilk" -> Datum.symbol("propList");
            default -> pl.properties().getOrDefault(propName, Datum.VOID);
        };
    }

    /**
     * Get a built-in property from a string.
     */
    private static Datum getStringProp(Datum.Str str, String propName) {
        String prop = propName.toLowerCase();
        return switch (prop) {
            case "length" -> Datum.of(str.value().length());
            case "ilk" -> Datum.symbol("string");
            default -> Datum.VOID;
        };
    }

    private static boolean setObjProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum value = ctx.pop();
        Datum obj = ctx.pop();

        switch (obj) {
            case Datum.CastLibRef clr -> setCastLibProp(clr, propName, value);
            case Datum.CastMemberRef cmr -> setCastMemberProp(cmr, propName, value);
            case Datum.ScriptInstance si -> {
                AncestorChainWalker.setProperty(si, propName, value);
                ctx.tracePropertySet(propName, value);
            }
            case Datum.XtraInstance xi -> XtraBuiltins.setProperty(xi, propName, value);
            case Datum.TimeoutRef tr -> TimeoutBuiltins.setProperty(tr, propName, value);
            case Datum.PropList pl -> pl.properties().put(propName, value);
            case Datum.MovieRef m -> {
                MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
                if (provider != null) {
                    provider.setMovieProp(propName, value);
                }
            }
            case Datum.SpriteRef sr -> {
                SpritePropertyProvider spriteProvider = SpritePropertyProvider.getProvider();
                if (spriteProvider != null) {
                    spriteProvider.setSpriteProp(sr.channel(), propName, value);
                }
            }
            case Datum.StageRef s -> {
                MoviePropertyProvider stageProvider = MoviePropertyProvider.getProvider();
                if (stageProvider != null) {
                    stageProvider.setStageProp(propName, value);
                }
            }
            case Datum.Rect rect -> {
                int v = value.toInt();
                switch (propName.toLowerCase()) {
                    case "left" -> rect.setLeft(v);
                    case "top" -> rect.setTop(v);
                    case "right" -> rect.setRight(v);
                    case "bottom" -> rect.setBottom(v);
                }
            }
            case Datum.Point point -> {
                int v = value.toInt();
                switch (propName.toLowerCase()) {
                    case "loch", "x" -> point.setX(v);
                    case "locv", "y" -> point.setY(v);
                }
            }
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
        // e.g., "the paramCount", "the name of member 1"
        // An arglist is pushed before this opcode and must be popped
        Datum argListDatum = ctx.pop();

        String propName = ctx.resolveName(ctx.getArgument());

        // Handle paramCount and result directly
        String propLower = propName.toLowerCase();
        if (propLower.equals("paramcount")) {
            ctx.push(Datum.of(ctx.getScope().getArguments().size()));
            return true;
        }
        if (propLower.equals("result")) {
            ctx.push(ctx.getScope().getReturnValue());
            return true;
        }

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
            case 0x08 -> {
                // Anim2 property
                if (propertyId == 0x02) {
                    // "number of castMembers of castLib N" - pops cast lib number from stack
                    int castLibNum = ctx.pop().toInt();
                    CastLibProvider castProvider = CastLibProvider.getProvider();
                    if (castProvider != null) {
                        int count = castProvider.getMemberCount(castLibNum);
                        yield Datum.of(count);
                    }
                    yield Datum.ZERO;
                }
                String propName = PropertyIdMappings.getAnim2PropName(propertyId);
                if (propName != null && movieProvider != null) {
                    yield movieProvider.getMovieProp(propName);
                }
                yield Datum.VOID;
            }
            case 0x09 -> {
                // Alias to 0x07 (animation properties)
                String propName = PropertyIdMappings.getAnimPropName(propertyId);
                if (propName != null && movieProvider != null) {
                    yield movieProvider.getMovieProp(propName);
                }
                yield Datum.VOID;
            }
            case 0x0b -> {
                // Sound channel property
                String propName = PropertyIdMappings.getSoundPropName(propertyId);
                int channelNum = ctx.pop().toInt();
                // TODO: delegate to sound provider when available
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

    /**
     * GET_CHAINED_PROP (0x70) - Get a property from a chained object.
     * Used for expressions like obj.prop, list[1], str.length.
     * Stack: [..., obj] -> [..., value]
     */
    private static boolean getChainedProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());
        Datum obj = ctx.pop();

        // Check if propName is a numeric index
        boolean isNumericIndex = false;
        int numericIndex = 0;
        try {
            numericIndex = Integer.parseInt(propName);
            isNumericIndex = true;
        } catch (NumberFormatException e) {
            // Not numeric
        }

        Datum result = switch (obj) {
            case Datum.ScriptInstance si -> {
                if (isNumericIndex) {
                    yield Datum.VOID;
                }
                yield AncestorChainWalker.getProperty(si, propName);
            }
            case Datum.List list -> {
                if (isNumericIndex) {
                    // Lingo uses 1-based indexing
                    int zeroIndex = numericIndex - 1;
                    if (zeroIndex >= 0 && zeroIndex < list.items().size()) {
                        yield list.items().get(zeroIndex);
                    }
                    yield Datum.VOID;
                }
                yield getChainedObjProp(obj, propName);
            }
            case Datum.PropList pl -> getChainedObjProp(obj, propName);
            case Datum.Str str -> {
                if ("length".equals(propName)) {
                    yield Datum.of(str.value().length());
                }
                yield getChainedObjProp(obj, propName);
            }
            case Datum.SpriteRef sr -> {
                SpritePropertyProvider spriteProvider = SpritePropertyProvider.getProvider();
                if (spriteProvider != null) {
                    yield spriteProvider.getSpriteProp(sr.channel(), propName);
                }
                yield Datum.VOID;
            }
            case Datum.CastMemberRef cmr -> getCastMemberProp(cmr, propName);
            case Datum.CastLibRef clr -> getCastLibProp(clr, propName);
            case Datum.MovieRef m -> {
                MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
                if (provider != null) {
                    yield provider.getMovieProp(propName);
                }
                yield Datum.VOID;
            }
            case Datum.PlayerRef p -> {
                // Player properties - limited set
                yield getBuiltinConstant(propName);
            }
            case Datum.Point point -> {
                yield switch (propName.toLowerCase()) {
                    case "loch", "x" -> Datum.of(point.x());
                    case "locv", "y" -> Datum.of(point.y());
                    default -> Datum.VOID;
                };
            }
            case Datum.Rect rect -> {
                yield switch (propName.toLowerCase()) {
                    case "left" -> Datum.of(rect.left());
                    case "top" -> Datum.of(rect.top());
                    case "right" -> Datum.of(rect.right());
                    case "bottom" -> Datum.of(rect.bottom());
                    case "width" -> Datum.of(rect.right() - rect.left());
                    case "height" -> Datum.of(rect.bottom() - rect.top());
                    default -> Datum.VOID;
                };
            }
            case Datum.Color color -> {
                yield switch (propName.toLowerCase()) {
                    case "red" -> Datum.of(color.r());
                    case "green" -> Datum.of(color.g());
                    case "blue" -> Datum.of(color.b());
                    default -> Datum.VOID;
                };
            }
            case Datum.XtraInstance xi -> XtraBuiltins.getProperty(xi, propName);
            case Datum.TimeoutRef tr -> TimeoutBuiltins.getProperty(tr, propName);
            case Datum.StageRef s -> {
                MoviePropertyProvider stageProvider = MoviePropertyProvider.getProvider();
                yield stageProvider != null ? stageProvider.getStageProp(propName) : Datum.VOID;
            }
            case Datum.ImageRef ir -> ImageMethodDispatcher.getProperty(ir, propName);
            default -> getChainedObjProp(obj, propName);
        };

        ctx.push(result);
        return true;
    }

    /**
     * Helper for GET_CHAINED_PROP: get property from common object types.
     */
    private static Datum getChainedObjProp(Datum obj, String propName) {
        return switch (obj) {
            case Datum.PropList pl -> {
                // Search by key (case-insensitive symbol/string match)
                for (Map.Entry<String, Datum> entry : pl.properties().entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(propName)) {
                        yield entry.getValue();
                    }
                }
                yield Datum.VOID;
            }
            case Datum.ScriptInstance si -> AncestorChainWalker.getProperty(si, propName);
            case Datum.CastMemberRef cmr -> getCastMemberProp(cmr, propName);
            case Datum.CastLibRef clr -> getCastLibProp(clr, propName);
            case Datum.StageRef s -> {
                MoviePropertyProvider stageProvider = MoviePropertyProvider.getProvider();
                yield stageProvider != null ? stageProvider.getStageProp(propName) : Datum.VOID;
            }
            case Datum.ImageRef ir -> ImageMethodDispatcher.getProperty(ir, propName);
            default -> Datum.VOID;
        };
    }

    /**
     * GET_TOP_LEVEL_PROP (0x72) - Get a top-level property.
     * Used for _movie, _player access.
     * Stack: [...] -> [..., value]
     */
    private static boolean getTopLevelProp(ExecutionContext ctx) {
        String propName = ctx.resolveName(ctx.getArgument());

        Datum result = switch (propName) {
            case "_player" -> Datum.PLAYER;
            case "_movie" -> Datum.MOVIE;
            default -> {
                System.err.println("[WARN] GET_TOP_LEVEL_PROP: unknown prop: " + propName);
                yield Datum.VOID;
            }
        };

        ctx.push(result);
        return true;
    }
}
