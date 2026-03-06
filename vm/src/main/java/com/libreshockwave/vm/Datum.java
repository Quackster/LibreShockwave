package com.libreshockwave.vm;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.id.CastLibId;
import com.libreshockwave.id.ChannelId;
import com.libreshockwave.id.MemberId;
import com.libreshockwave.id.VarType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime value in the Lingo VM.
 * Represents all possible value types in the Director scripting language.
 * Similar to dirplayer-rs datum.rs.
 */
public sealed interface Datum {

    /** Void/null value */
    record Void() implements Datum {
        @Override
        public String toString() { return "<Void>"; }
    }

    /** Integer value */
    record Int(int value) implements Datum {
        @Override
        public String toString() { return String.valueOf(value); }
    }

    /** Floating point value */
    record Float(double value) implements Datum {
        @Override
        public String toString() { return String.valueOf(value); }
    }

    /** String value */
    record Str(String value) implements Datum {
        @Override
        public String toString() { return "\"" + value + "\""; }
    }

    /** Symbol value (like #symbol in Lingo) */
    record Symbol(String name) implements Datum {
        @Override
        public String toString() { return "#" + name; }
    }

    /** Linear list [a, b, c] */
    record List(java.util.List<Datum> items) implements Datum {
        public List {
            items = new ArrayList<>(items);
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(items.get(i));
            }
            return sb.append("]").toString();
        }
    }

    /** Property list [#a: 1, #b: 2] */
    record PropList(Map<String, Datum> properties) implements Datum {
        public PropList {
            properties = new LinkedHashMap<>(properties);
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Map.Entry<String, Datum> entry : properties.entrySet()) {
                if (!first) sb.append(", ");
                sb.append("#").append(entry.getKey()).append(": ").append(entry.getValue());
                first = false;
            }
            return sb.append("]").toString();
        }
    }

    /** Sprite reference */
    record SpriteRef(ChannelId channel) implements Datum {
        public static SpriteRef of(int channel) { return new SpriteRef(new ChannelId(channel)); }
        public int channelNum() { return channel.value(); }
        @Override
        public String toString() { return "sprite(" + channel.value() + ")"; }
    }

    /** Cast member reference */
    record CastMemberRef(CastLibId castLib, MemberId member) implements Datum {
        /** Safe factory: returns VOID for invalid castLib/member (0 means "no member" in Director). */
        public static Datum of(int castLib, int member) {
            if (castLib < 1 || member < 1) return VOID;
            return new CastMemberRef(new CastLibId(castLib), new MemberId(member));
        }
        public int castLibNum() { return castLib.value(); }
        public int memberNum() { return member.value(); }
        @Override
        public String toString() { return "member(" + member.value() + ", " + castLib.value() + ")"; }
    }

    /** Script instance reference */
    record ScriptInstance(int scriptId, Map<String, Datum> properties) implements Datum {
        public ScriptInstance {
            properties = new LinkedHashMap<>(properties);
        }
        @Override
        public String toString() { return "<script instance " + scriptId + ">"; }
    }

    /** Point value - mutable to match Director's value semantics (setAt support) */
    final class Point implements Datum {
        private int x, y;
        public Point(int x, int y) { this.x = x; this.y = y; }
        public int x() { return x; }
        public int y() { return y; }
        public void setX(int x) { this.x = x; }
        public void setY(int y) { this.y = y; }
        public void setComponent(int index, int value) {
            switch (index) { case 1 -> x = value; case 2 -> y = value; }
        }
        @Override public String toString() { return "point(" + x + ", " + y + ")"; }
        @Override public boolean equals(Object o) {
            return o instanceof Point p && x == p.x && y == p.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
    }

    /** Rectangle value - mutable to match Director's value semantics (setAt support) */
    final class Rect implements Datum {
        private int left, top, right, bottom;
        public Rect(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public int left() { return left; }
        public int top() { return top; }
        public int right() { return right; }
        public int bottom() { return bottom; }
        public void setLeft(int v) { left = v; }
        public void setTop(int v) { top = v; }
        public void setRight(int v) { right = v; }
        public void setBottom(int v) { bottom = v; }
        public void setComponent(int index, int value) {
            switch (index) { case 1 -> left = value; case 2 -> top = value; case 3 -> right = value; case 4 -> bottom = value; }
        }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        @Override public String toString() { return "rect(" + left + ", " + top + ", " + right + ", " + bottom + ")"; }
        @Override public boolean equals(Object o) {
            return o instanceof Rect r && left == r.left && top == r.top && right == r.right && bottom == r.bottom;
        }
        @Override public int hashCode() { return Objects.hash(left, top, right, bottom); }
    }

    /** Color value */
    record Color(int r, int g, int b) implements Datum {
        @Override
        public String toString() { return "color(" + r + ", " + g + ", " + b + ")"; }
    }

    /** Image reference (wraps a bitmap for Director's image API) */
    record ImageRef(Bitmap bitmap) implements Datum {
        @Override
        public String toString() { return "(image " + bitmap.getWidth() + "x" + bitmap.getHeight() + ")"; }
    }

    /** Xtra reference (the Xtra class itself) */
    record XtraRef(String xtraName) implements Datum {
        @Override
        public String toString() { return "<Xtra \"" + xtraName + "\">"; }
    }

    /** Xtra instance reference */
    record XtraInstance(String xtraName, int instanceId) implements Datum {
        @Override
        public String toString() { return "<XtraInstance \"" + xtraName + "\" #" + instanceId + ">"; }
    }

    /** Cast library reference */
    record CastLibRef(CastLibId castLibNumber) implements Datum {
        public static CastLibRef of(int castLibNumber) { return new CastLibRef(new CastLibId(castLibNumber)); }
        public int castLibNum() { return castLibNumber.value(); }
        @Override
        public String toString() { return "castLib(" + castLibNumber.value() + ")"; }
    }

    /** Stage reference (the stage window) */
    record StageRef() implements Datum {
        @Override
        public String toString() { return "(the stage)"; }
    }

    /** Movie reference (_movie) */
    record MovieRef() implements Datum {
        @Override
        public String toString() { return "(the movie)"; }
    }

    /** Player reference (_player) */
    record PlayerRef() implements Datum {
        @Override
        public String toString() { return "(the player)"; }
    }

    /** Script reference (returned by script() function) */
    record ScriptRef(CastLibId castLib, MemberId member) implements Datum {
        /** Safe factory: returns VOID for invalid castLib/member (0 means "no script" in Director). */
        public static Datum of(int castLib, int member) {
            if (castLib < 1 || member < 1) return VOID;
            return new ScriptRef(new CastLibId(castLib), new MemberId(member));
        }
        public int castLibNum() { return castLib.value(); }
        public int memberNum() { return member.value(); }
        @Override
        public String toString() { return "<script " + member.value() + ", " + castLib.value() + ">"; }
    }

    /** Timeout reference (returned by timeout() builtin) */
    record TimeoutRef(String name) implements Datum {
        @Override
        public String toString() { return "timeout(\"" + name + "\")"; }
    }

    /** Mutable reference to a VM variable (for chunk mutation operations like delete) */
    record VarRef(VarType varType, int rawIndex) implements Datum {
        @Override
        public String toString() { return "<varref:" + varType + "," + rawIndex + ">"; }
    }

    /** Reference to a chunk range within a variable (used by delete/put chunk operations) */
    record ChunkRef(VarType varType, int rawIndex, String chunkType, int start, int end) implements Datum {
        @Override
        public String toString() { return "<chunkref:" + chunkType + "[" + start + ".." + end + "]>"; }
    }

    /** Argument list for function calls (expects return value).
     *  No defensive copy — popArgs() already creates a fresh ArrayList. */
    record ArgList(java.util.List<Datum> items) implements Datum {
        public int count() { return items.size(); }
        @Override
        public String toString() { return "<arglist:" + items.size() + ">"; }
    }

    /** Argument list for function calls (no return value expected).
     *  No defensive copy — popArgs() already creates a fresh ArrayList. */
    record ArgListNoRet(java.util.List<Datum> items) implements Datum {
        public int count() { return items.size(); }
        @Override
        public String toString() { return "<arglist-noret:" + items.size() + ">"; }
    }

    // Singleton instances for common values
    Datum VOID = new Void();
    Datum ZERO = new Int(0);
    Datum ONE = new Int(1);
    Datum TRUE = new Int(1);
    Datum FALSE = new Int(0);
    Datum EMPTY_STRING = new Str("");
    Datum STAGE = new StageRef();
    Datum MOVIE = new MovieRef();
    Datum PLAYER = new PlayerRef();

    // Common property key constants
    String PROP_ANCESTOR = "ancestor";
    String PROP_SCRIPT_REF = "__scriptRef__";

    // Factory methods
    static Datum of(int value) {
        return switch (value) {
            case 0 -> ZERO;
            case 1 -> ONE;
            default -> new Int(value);
        };
    }

    static Datum of(double value) {
        return new Float(value);
    }

    static Datum of(String value) {
        if (value == null || value.isEmpty()) return EMPTY_STRING;
        return new Str(value);
    }

    static Datum symbol(String name) {
        return new Symbol(name);
    }

    static Datum list(Datum... items) {
        return new List(java.util.List.of(items));
    }

    static Datum list(java.util.List<Datum> items) {
        return new List(items);
    }

    static Datum propList() {
        return new PropList(Map.of());
    }

    static Datum propList(Map<String, Datum> props) {
        return new PropList(props);
    }

    // Type checking
    default boolean isVoid() { return this instanceof Void; }
    default boolean isInt() { return this instanceof Int; }
    default boolean isFloat() { return this instanceof Float; }
    default boolean isNumber() { return isInt() || isFloat(); }
    default boolean isString() { return this instanceof Str; }
    default boolean isSymbol() { return this instanceof Symbol; }
    default boolean isList() { return this instanceof List; }
    default boolean isPropList() { return this instanceof PropList; }
    default boolean isImage() { return this instanceof ImageRef; }

    default String typeName() {
        return switch (this) {
            case Void v -> "void";
            case Int i -> "int";
            case Float f -> "float";
            case Str s -> "string";
            case Symbol sym -> "symbol";
            case List l -> "list";
            case PropList pl -> "propList";
            case SpriteRef sr -> "sprite";
            case CastMemberRef cm -> "member";
            case ScriptInstance si -> "script";
            case Point p -> "point";
            case Rect r -> "rect";
            case Color c -> "color";
            case ImageRef ir -> "image";
            case XtraRef xr -> "xtra";
            case XtraInstance xi -> "xtraInstance";
            case CastLibRef cl -> "castLib";
            case StageRef st -> "stage";
            case MovieRef m -> "movie";
            case PlayerRef p -> "player";
            case ScriptRef sr -> "script";
            case TimeoutRef tr -> "timeout";
            default -> getClass().getSimpleName().toLowerCase();
        };
    }

    default boolean isTruthy() {
        return switch (this) {
            case Void v -> false;
            case Int i -> i.value() != 0;
            case Float f -> f.value() != 0.0;
            case Str s -> !s.value().isEmpty();
            default -> true;
        };
    }

    // Type coercion
    default int toInt() {
        return switch (this) {
            case Int i -> i.value();
            case Float f -> (int) f.value();
            case Str s -> {
                try {
                    yield Integer.parseInt(s.value().trim());
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
            case Color c -> (c.r() << 16) | (c.g() << 8) | c.b();
            default -> 0;
        };
    }

    default double toDouble() {
        return switch (this) {
            case Int i -> i.value();
            case Float f -> f.value();
            case Str s -> {
                try {
                    yield Double.parseDouble(s.value().trim());
                } catch (NumberFormatException e) {
                    yield 0.0;
                }
            }
            default -> 0.0;
        };
    }

    default String toStr() {
        return switch (this) {
            case Void v -> "";
            case Int i -> String.valueOf(i.value());
            case Float f -> String.valueOf(f.value());
            case Str s -> s.value();
            case Symbol s -> s.name();
            default -> toString();
        };
    }

    /**
     * Convert this Datum to a property-key name string.
     * Symbols yield their name directly; all other types use toStr().
     */
    default String toKeyName() {
        return this instanceof Symbol s ? s.name() : toStr();
    }

    /**
     * Create a deep copy of this Datum.
     * For immutable types (Int, Float, Str, Symbol, etc.), returns the same instance.
     * For mutable types (List, PropList, ScriptInstance, ArgList, ArgListNoRet),
     * creates a new instance with deep-copied contents.
     */
    default Datum deepCopy() {
        return switch (this) {
            // Immutable types - return same instance
            case Void v -> v;
            case Int i -> i;
            case Float f -> f;
            case Str s -> s;
            case Symbol sym -> sym;
            case Point p -> new Point(p.x(), p.y());
            case Rect r -> new Rect(r.left(), r.top(), r.right(), r.bottom());
            case Color c -> c;
            case ImageRef ir -> new ImageRef(ir.bitmap().copy());
            case SpriteRef sr -> sr;
            case CastMemberRef cm -> cm;
            case CastLibRef cl -> cl;
            case StageRef st -> st;
            case MovieRef m -> m;
            case PlayerRef p -> p;
            case ScriptRef sr -> sr;
            case XtraRef xr -> xr;
            case XtraInstance xi -> xi;
            case TimeoutRef tr -> tr;
            case VarRef vr -> vr;
            case ChunkRef cr -> cr;

            // Mutable types - deep copy
            case List list -> {
                java.util.List<Datum> copiedItems = new ArrayList<>(list.items().size());
                for (Datum item : list.items()) {
                    copiedItems.add(item.deepCopy());
                }
                yield new List(copiedItems);
            }
            case PropList pl -> {
                Map<String, Datum> copiedProps = new LinkedHashMap<>();
                for (Map.Entry<String, Datum> entry : pl.properties().entrySet()) {
                    copiedProps.put(entry.getKey(), entry.getValue().deepCopy());
                }
                yield new PropList(copiedProps);
            }
            case ScriptInstance si -> {
                Map<String, Datum> copiedProps = new LinkedHashMap<>();
                for (Map.Entry<String, Datum> entry : si.properties().entrySet()) {
                    // Don't deep copy property values to avoid infinite recursion
                    // with circular references like 'ancestor' chains
                    copiedProps.put(entry.getKey(), entry.getValue());
                }
                yield new ScriptInstance(si.scriptId(), copiedProps);
            }
            case ArgList al -> {
                java.util.List<Datum> copiedItems = new ArrayList<>(al.items().size());
                for (Datum item : al.items()) {
                    copiedItems.add(item.deepCopy());
                }
                yield new ArgList(copiedItems);
            }
            case ArgListNoRet al -> {
                java.util.List<Datum> copiedItems = new ArrayList<>(al.items().size());
                for (Datum item : al.items()) {
                    copiedItems.add(item.deepCopy());
                }
                yield new ArgListNoRet(copiedItems);
            }
        };
    }
}
