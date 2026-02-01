package com.libreshockwave.vm;

/**
 * Utility for formatting Datum values as human-readable strings.
 */
public final class DatumFormatter {

    private static final int DEFAULT_MAX_STRING_LENGTH = 50;

    private DatumFormatter() {}

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
        if (d instanceof Datum.Str s) return "\"" + truncate(s.value(), maxStringLength) + "\"";
        if (d instanceof Datum.Symbol sym) return "#" + sym.name();
        if (d instanceof Datum.List list) return "[list:" + list.items().size() + "]";
        if (d instanceof Datum.PropList pl) return "[propList:" + pl.properties().size() + "]";
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
     * Truncate a string if it exceeds the maximum length.
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
