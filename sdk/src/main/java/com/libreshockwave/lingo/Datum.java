package com.libreshockwave.lingo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lingo runtime value type.
 * This is a sealed interface hierarchy representing all possible Lingo values.
 */
public sealed interface Datum {

    DatumType type();

    default String typeString() {
        return type().getTypeName();
    }

    // Type checking

    default boolean isNull() { return this instanceof Null; }
    default boolean isVoid() { return this instanceof Void; }
    default boolean isInt() { return this instanceof Int; }
    default boolean isFloat() { return this instanceof DFloat; }
    default boolean isNumber() { return isInt() || isFloat(); }
    default boolean isString() { return this instanceof Str || this instanceof StringChunk; }
    default boolean isSymbol() { return this instanceof Symbol; }
    default boolean isList() { return this instanceof DList; }
    default boolean isPropList() { return this instanceof PropList; }

    // Value extraction with defaults

    default int intValue() {
        return switch (this) {
            case Int i -> i.value;
            case DFloat f -> (int) f.value;
            case Str s -> parseIntSafe(s.value);
            case Void v -> 0;
            default -> throw new LingoException("Cannot convert " + typeString() + " to int");
        };
    }

    default float floatValue() {
        return switch (this) {
            case DFloat f -> f.value;
            case Int i -> (float) i.value;
            case Str s -> parseFloatSafe(s.value);
            case Void v -> 0.0f;
            default -> throw new LingoException("Cannot convert " + typeString() + " to float");
        };
    }

    default String stringValue() {
        return switch (this) {
            case Str s -> s.value;
            case StringChunk sc -> sc.value;
            case Int i -> String.valueOf(i.value);
            case DFloat f -> String.valueOf(f.value);
            case Symbol sym -> sym.name;
            case Void v -> "VOID";
            case Null n -> "";
            case IntPoint p -> String.format("point(%d, %d)", p.x, p.y);
            case IntRect r -> String.format("rect(%d, %d, %d, %d)", r.left, r.top, r.right, r.bottom);
            case Vector3 v -> String.format("vector(%f, %f, %f)", v.x, v.y, v.z);
            default -> throw new LingoException("Cannot convert " + typeString() + " to string");
        };
    }

    default boolean boolValue() {
        return switch (this) {
            case Int i -> i.value != 0;
            case DFloat f -> f.value != 0.0f;
            case Str s -> !s.value.isEmpty();
            case Symbol sym -> true;
            case Void v -> false;
            case Null n -> false;
            default -> throw new LingoException("Cannot convert " + typeString() + " to bool");
        };
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static float parseFloatSafe(String s) {
        try { return Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return 0.0f; }
    }

    // Factory methods

    static Null nullValue() { return Null.INSTANCE; }
    static Void voidValue() { return Void.INSTANCE; }
    static Int of(int value) { return new Int(value); }
    static DFloat of(float value) { return new DFloat(value); }
    static Str of(String value) { return new Str(value); }
    static Symbol symbol(String name) { return new Symbol(name); }
    static DList list() { return new DList(new ArrayList<>(), false); }
    static PropList propList() { return new PropList(new LinkedHashMap<>(), false); }

    // Constants
    Int TRUE = new Int(1);
    Int FALSE = new Int(0);

    // Datum types

    record Null() implements Datum {
        static final Null INSTANCE = new Null();
        @Override public DatumType type() { return DatumType.NULL; }
    }

    record Void() implements Datum {
        static final Void INSTANCE = new Void();
        @Override public DatumType type() { return DatumType.VOID; }
    }

    record Int(int value) implements Datum {
        @Override public DatumType type() { return DatumType.INT; }
    }

    record DFloat(float value) implements Datum {
        @Override public DatumType type() { return DatumType.FLOAT; }
    }

    record Str(String value) implements Datum {
        @Override public DatumType type() { return DatumType.STRING; }
    }

    record Symbol(String name) implements Datum {
        @Override public DatumType type() { return DatumType.SYMBOL; }
    }

    record DList(List<Datum> items, boolean sorted) implements Datum {
        @Override public DatumType type() { return DatumType.LIST; }

        public void add(Datum item) {
            items.add(item);
        }

        public Datum getAt(int index) {
            if (index < 1 || index > items.size()) {
                throw new LingoException("Index out of bounds: " + index);
            }
            return items.get(index - 1); // Lingo uses 1-based indexing
        }

        public void setAt(int index, Datum value) {
            if (index < 1 || index > items.size()) {
                throw new LingoException("Index out of bounds: " + index);
            }
            items.set(index - 1, value);
        }

        public int count() {
            return items.size();
        }
    }

    record PropList(Map<Datum, Datum> properties, boolean sorted) implements Datum {
        @Override public DatumType type() { return DatumType.PROP_LIST; }

        public void put(Datum key, Datum value) {
            properties.put(key, value);
        }

        public Datum get(Datum key) {
            return properties.getOrDefault(key, Void.INSTANCE);
        }

        public boolean contains(Datum key) {
            return properties.containsKey(key);
        }

        public int count() {
            return properties.size();
        }
    }

    record StringChunk(
        Datum source,
        StringChunkType chunkType,
        int start,
        int end,
        char itemDelimiter,
        String value
    ) implements Datum {
        @Override public DatumType type() { return DatumType.STRING_CHUNK; }
    }

    // Reference types

    record CastMemberRef(int castLib, int castMember) implements Datum {
        @Override public DatumType type() { return DatumType.CAST_MEMBER_REF; }

        public int memberNum() { return castMember; }
    }

    record CastLibRef(int castLib) implements Datum {
        @Override public DatumType type() { return DatumType.CAST_LIB_REF; }
    }

    record SpriteRef(int channel) implements Datum {
        @Override public DatumType type() { return DatumType.SPRITE_REF; }

        public int spriteNum() { return channel; }
    }

    /**
     * Reference to the stage window.
     */
    record StageRef() implements Datum {
        @Override public DatumType type() { return DatumType.STAGE_REF; }

        @Override
        public String toString() { return "(the stage)"; }
    }

    record ScriptRef(CastMemberRef memberRef) implements Datum {
        @Override public DatumType type() { return DatumType.SCRIPT_REF; }
    }

    /**
     * Runtime script instance with property storage.
     */
    final class ScriptInstanceRef implements Datum {
        private final String scriptName;
        private final Map<String, Datum> properties;

        public ScriptInstanceRef(String scriptName, Map<String, Datum> initialProps) {
            this.scriptName = scriptName;
            this.properties = new LinkedHashMap<>(initialProps);
        }

        public String scriptName() { return scriptName; }

        public Datum getProperty(String name) {
            return properties.getOrDefault(name, Void.INSTANCE);
        }

        public void setProperty(String name, Datum value) {
            properties.put(name, value);
        }

        public Map<String, Datum> properties() {
            return properties;
        }

        @Override public DatumType type() { return DatumType.SCRIPT_INSTANCE_REF; }

        @Override
        public boolean equals(Object obj) {
            return this == obj; // Instance identity
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    record Stage() implements Datum {
        static final Stage INSTANCE = new Stage();
        @Override public DatumType type() { return DatumType.STAGE_REF; }
    }

    record PlayerRef() implements Datum {
        static final PlayerRef INSTANCE = new PlayerRef();
        @Override public DatumType type() { return DatumType.PLAYER_REF; }
    }

    record MovieRef() implements Datum {
        static final MovieRef INSTANCE = new MovieRef();
        @Override public DatumType type() { return DatumType.MOVIE_REF; }
    }

    // Geometry types

    record IntPoint(int x, int y) implements Datum {
        @Override public DatumType type() { return DatumType.INT_POINT; }
    }

    record IntRect(int left, int top, int right, int bottom) implements Datum {
        @Override public DatumType type() { return DatumType.INT_RECT; }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }

    record Vector3(float x, float y, float z) implements Datum {
        @Override public DatumType type() { return DatumType.VECTOR; }
    }

    // Color and graphics types

    record ColorRef(int r, int g, int b) implements Datum {
        @Override public DatumType type() { return DatumType.COLOR_REF; }

        public static ColorRef fromPaletteIndex(int index) {
            // Placeholder - actual palette lookup would be needed
            return new ColorRef(index, index, index);
        }

        public static ColorRef fromRgb(int r, int g, int b) {
            return new ColorRef(r, g, b);
        }
    }

    record BitmapRef(int bitmapId) implements Datum {
        @Override public DatumType type() { return DatumType.BITMAP_REF; }
    }

    record PaletteRef(int paletteId) implements Datum {
        @Override public DatumType type() { return DatumType.PALETTE_REF; }
    }

    record Matte(Object mask) implements Datum {
        @Override public DatumType type() { return DatumType.MATTE; }
    }

    // Sound types

    record SoundRef(int soundNum) implements Datum {
        @Override public DatumType type() { return DatumType.SOUND_REF; }
    }

    record SoundChannel(int channel) implements Datum {
        @Override public DatumType type() { return DatumType.SOUND_CHANNEL; }
    }

    // Other types

    record CursorRef(int cursorId) implements Datum {
        @Override public DatumType type() { return DatumType.CURSOR_REF; }
    }

    record TimeoutRef(String name) implements Datum {
        @Override public DatumType type() { return DatumType.TIMEOUT_REF; }
    }

    record Xtra(String name) implements Datum {
        @Override public DatumType type() { return DatumType.XTRA; }
    }

    record XtraInstance(String xtraName, int instanceId) implements Datum {
        @Override public DatumType type() { return DatumType.XTRA_INSTANCE; }
    }

    record XmlRef(int xmlId) implements Datum {
        @Override public DatumType type() { return DatumType.XML_REF; }
    }

    record DateRef(int dateId) implements Datum {
        @Override public DatumType type() { return DatumType.DATE_REF; }
    }

    record MathRef(int mathId) implements Datum {
        @Override public DatumType type() { return DatumType.MATH_REF; }
    }

    record VarRef(String varName) implements Datum {
        @Override public DatumType type() { return DatumType.VAR_REF; }
    }

    // Argument lists (used for function calls)

    record ArgList(List<Datum> args) implements Datum {
        @Override public DatumType type() { return DatumType.ARG_LIST; }
    }

    record ArgListNoRet(List<Datum> args) implements Datum {
        @Override public DatumType type() { return DatumType.ARG_LIST_NO_RET; }
    }
}
