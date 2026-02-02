package com.libreshockwave.vm;

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
    record SpriteRef(int channel) implements Datum {
        @Override
        public String toString() { return "sprite(" + channel + ")"; }
    }

    /** Cast member reference */
    record CastMemberRef(int castLib, int member) implements Datum {
        @Override
        public String toString() { return "member(" + member + ", " + castLib + ")"; }
    }

    /** Script instance reference */
    record ScriptInstance(int scriptId, Map<String, Datum> properties) implements Datum {
        public ScriptInstance {
            properties = new LinkedHashMap<>(properties);
        }
        @Override
        public String toString() { return "<script instance " + scriptId + ">"; }
    }

    /** Point value */
    record Point(int x, int y) implements Datum {
        @Override
        public String toString() { return "point(" + x + ", " + y + ")"; }
    }

    /** Rectangle value */
    record Rect(int left, int top, int right, int bottom) implements Datum {
        @Override
        public String toString() { return "rect(" + left + ", " + top + ", " + right + ", " + bottom + ")"; }
    }

    /** Color value */
    record Color(int r, int g, int b) implements Datum {
        @Override
        public String toString() { return "color(" + r + ", " + g + ", " + b + ")"; }
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
    record CastLibRef(int castLibNumber) implements Datum {
        @Override
        public String toString() { return "castLib(" + castLibNumber + ")"; }
    }

    /** Stage reference (the stage window) */
    record StageRef() implements Datum {
        @Override
        public String toString() { return "(the stage)"; }
    }

    /** Window reference */
    record WindowRef(String name) implements Datum {
        @Override
        public String toString() { return "window(\"" + name + "\")"; }
    }

    /** Script reference (returned by script() function) */
    record ScriptRef(int castLib, int member) implements Datum {
        @Override
        public String toString() { return "<script " + member + ", " + castLib + ">"; }
    }

    /** Argument list for function calls (expects return value) */
    record ArgList(java.util.List<Datum> items) implements Datum {
        public ArgList {
            items = new ArrayList<>(items);
        }
        public int count() { return items.size(); }
        @Override
        public String toString() { return "<arglist:" + items.size() + ">"; }
    }

    /** Argument list for function calls (no return value expected) */
    record ArgListNoRet(java.util.List<Datum> items) implements Datum {
        public ArgListNoRet {
            items = new ArrayList<>(items);
        }
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
            case XtraRef xr -> "xtra";
            case XtraInstance xi -> "xtraInstance";
            case CastLibRef cl -> "castLib";
            case StageRef st -> "stage";
            case WindowRef w -> "window";
            case ScriptRef sr -> "script";
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
}
