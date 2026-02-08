package com.libreshockwave.vm;

import com.libreshockwave.vm.util.StringUtils;

import java.util.Map;

/**
 * Utility for formatting Datum values as human-readable strings.
 */
public final class DatumFormatter {

    private static final int DEFAULT_MAX_STRING_LENGTH = 50;
    private static final int DEFAULT_BRIEF_STRING_LENGTH = 30;

    private DatumFormatter() {}

    /**
     * Escape special characters for detailed display.
     * Uses visible tokens like [CR], [LF], [TAB] instead of backslash sequences.
     */
    private static String escapeForDetailedDisplay(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\r\n", "[CR][LF]")
                .replace("\r", "[CR]")
                .replace("\n", "[LF]")
                .replace("\t", "[TAB]");
    }

    /**
     * Format a Datum value as a string.
     */
    public static String format(Datum d) {
        return format(d, DEFAULT_MAX_STRING_LENGTH);
    }

    /**
     * Format a Datum value as a string with configurable max string length.
     */
    public static String format(Datum d, int maxStringLength) {
        if (d == null) return "<null>";
        if (d instanceof Datum.Void) return "<void>";
        if (d instanceof Datum.Int i) return String.valueOf(i.value());
        if (d instanceof Datum.Float f) return String.valueOf(f.value());
        if (d instanceof Datum.Str s) return "\"" + StringUtils.truncate(s.value(), maxStringLength) + "\"";
        if (d instanceof Datum.Symbol sym) return "#" + sym.name();
        if (d instanceof Datum.List list) return "[list:" + list.items().size() + "]";
        if (d instanceof Datum.PropList pl) return "[propList:" + pl.properties().size() + "]";
        if (d instanceof Datum.ArgList al) return "<arglist:" + al.count() + ">";
        if (d instanceof Datum.ArgListNoRet al) return "<arglist-noret:" + al.count() + ">";
        if (d instanceof Datum.Point p) return "point(" + p.x() + ", " + p.y() + ")";
        if (d instanceof Datum.Rect r) return "rect(" + r.left() + ", " + r.top() + ", " + r.right() + ", " + r.bottom() + ")";
        if (d instanceof Datum.Color c) return "color(" + c.r() + ", " + c.g() + ", " + c.b() + ")";
        if (d instanceof Datum.ScriptInstance si) return "<script#" + si.scriptId() + ">";
        if (d instanceof Datum.SpriteRef sr) return "sprite(" + sr.channel() + ")";
        if (d instanceof Datum.CastMemberRef cm) return "member(" + cm.member() + ", " + cm.castLib() + ")";
        if (d instanceof Datum.CastLibRef cl) return "castLib(" + cl.castLibNumber() + ")";
        if (d instanceof Datum.StageRef) return "(the stage)";
        if (d instanceof Datum.WindowRef w) return "window(\"" + w.name() + "\")";
        if (d instanceof Datum.XtraRef xr) return "<Xtra \"" + xr.xtraName() + "\">";
        if (d instanceof Datum.XtraInstance xi) return "<XtraInstance \"" + xi.xtraName() + "\" #" + xi.instanceId() + ">";
        return d.toString();
    }

    /**
     * Format a Datum value with its type prefix.
     */
    public static String formatWithType(Datum d) {
        if (d == null) return "<null>";
        return d.typeName() + ": " + format(d);
    }

    /**
     * Format a Datum briefly for compact display (e.g., in nested contexts).
     * Uses shorter string truncation and simple type indicators.
     */
    public static String formatBrief(Datum d) {
        if (d == null) return "<null>";

        return switch (d) {
            case Datum.Void v -> "<Void>";
            case Datum.Int i -> String.valueOf(i.value());
            case Datum.Float f -> String.valueOf(f.value());
            case Datum.Str s -> "\"" + StringUtils.truncate(StringUtils.escapeForDisplay(s.value()), DEFAULT_BRIEF_STRING_LENGTH) + "\"";
            case Datum.Symbol sym -> "#" + sym.name();
            case Datum.List list -> "[list:" + list.items().size() + "]";
            case Datum.PropList pl -> "[propList:" + pl.properties().size() + "]";
            case Datum.ArgList al -> "<arglist:" + al.count() + ">";
            case Datum.ArgListNoRet al -> "<arglist-noret:" + al.count() + ">";
            case Datum.ScriptInstance si -> "<script#" + si.scriptId() + ">";
            default -> d.toString();
        };
    }

    /**
     * Format a Datum with full details, expanding arglists and nested structures.
     * Useful for debugging/inspection UIs.
     *
     * @param d the Datum to format
     * @param indent current indentation level (0 for top-level)
     * @return detailed formatted string
     */
    public static String formatDetailed(Datum d, int indent) {
        if (d == null) return "<null>";

        String indentStr = "      " + "  ".repeat(indent);

        return switch (d) {
            case Datum.Void v -> "<Void>";
            case Datum.Int i -> "Int: " + i.value();
            case Datum.Float f -> "Float: " + f.value();
            case Datum.Str s -> "Str: \"" + escapeForDetailedDisplay(s.value()) + "\"";
            case Datum.Symbol sym -> "Symbol: #" + sym.name();

            case Datum.ArgList argList -> {
                StringBuilder sb = new StringBuilder();
                sb.append("ArgList (expects return) [").append(argList.count()).append(" items]");
                if (!argList.items().isEmpty()) {
                    sb.append(" {");
                    for (int i = 0; i < argList.items().size(); i++) {
                        sb.append("\n").append(indentStr).append("  [").append(i).append("] ");
                        sb.append(formatDetailed(argList.items().get(i), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.ArgListNoRet argList -> {
                StringBuilder sb = new StringBuilder();
                sb.append("ArgListNoRet (no return) [").append(argList.count()).append(" items]");
                if (!argList.items().isEmpty()) {
                    sb.append(" {");
                    for (int i = 0; i < argList.items().size(); i++) {
                        sb.append("\n").append(indentStr).append("  [").append(i).append("] ");
                        sb.append(formatDetailed(argList.items().get(i), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.List list -> {
                StringBuilder sb = new StringBuilder();
                sb.append("List [").append(list.items().size()).append(" items]");
                if (!list.items().isEmpty()) {
                    sb.append(" {");
                    for (int i = 0; i < list.items().size(); i++) {
                        sb.append("\n").append(indentStr).append("  [").append(i).append("] ");
                        sb.append(formatDetailed(list.items().get(i), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.PropList propList -> {
                StringBuilder sb = new StringBuilder();
                sb.append("PropList [").append(propList.properties().size()).append(" props]");
                if (!propList.properties().isEmpty()) {
                    sb.append(" {");
                    for (Map.Entry<String, Datum> entry : propList.properties().entrySet()) {
                        sb.append("\n").append(indentStr).append("  #").append(entry.getKey()).append(": ");
                        sb.append(formatDetailed(entry.getValue(), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.ScriptInstance si -> {
                StringBuilder sb = new StringBuilder();
                sb.append("ScriptInstance #").append(si.scriptId());
                if (!si.properties().isEmpty()) {
                    sb.append(" {");
                    for (Map.Entry<String, Datum> entry : si.properties().entrySet()) {
                        sb.append("\n").append(indentStr).append("  .").append(entry.getKey()).append(" = ");
                        sb.append(formatDetailed(entry.getValue(), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.Point p -> "Point: (" + p.x() + ", " + p.y() + ")";
            case Datum.Rect r -> "Rect: (" + r.left() + ", " + r.top() + ", " + r.right() + ", " + r.bottom() + ")";
            case Datum.Color c -> "Color: rgb(" + c.r() + ", " + c.g() + ", " + c.b() + ")";
            case Datum.SpriteRef sr -> "SpriteRef: sprite(" + sr.channel() + ")";
            case Datum.CastMemberRef cm -> "CastMemberRef: member(" + cm.member() + ", " + cm.castLib() + ")";
            case Datum.CastLibRef cl -> "CastLibRef: castLib(" + cl.castLibNumber() + ")";
            case Datum.StageRef sr -> "StageRef: (the stage)";
            case Datum.WindowRef w -> "WindowRef: window(\"" + w.name() + "\")";
            case Datum.XtraRef xr -> "XtraRef: xtra(\"" + xr.xtraName() + "\")";
            case Datum.XtraInstance xi -> "XtraInstance: \"" + xi.xtraName() + "\" #" + xi.instanceId();
            case Datum.ScriptRef sr -> "ScriptRef: script(" + sr.member() + ", " + sr.castLib() + ")";
            default -> d.getClass().getSimpleName() + ": " + d.toString();
        };
    }

    /**
     * Get a simple type name for a Datum suitable for display in tables.
     * Returns the class simple name with nested class notation ($ replaced with .).
     *
     * @param d the Datum (may be null)
     * @return type name string, or "null" if d is null
     */
    public static String getTypeName(Datum d) {
        if (d == null) return "null";
        return d.getClass().getSimpleName().replace("$", ".");
    }
}
