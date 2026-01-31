package com.libreshockwave.player.format;

import com.libreshockwave.vm.Datum;

/**
 * Utility for formatting Datum values as human-readable strings.
 */
public final class DatumFormatter {

    private DatumFormatter() {}

    /**
     * Format a Datum value as a string.
     */
    public static String format(Datum d) {
        if (d == null) return "<null>";
        if (d instanceof Datum.Void) return "<void>";
        if (d instanceof Datum.Int i) return String.valueOf(i.value());
        if (d instanceof Datum.Float f) return String.valueOf(f.value());
        if (d instanceof Datum.Str s) return "\"" + truncate(s.value(), 50) + "\"";
        if (d instanceof Datum.Symbol sym) return "#" + sym.name();
        if (d instanceof Datum.List list) return "[list:" + list.items().size() + "]";
        if (d instanceof Datum.PropList pl) return "[propList:" + pl.properties().size() + "]";
        if (d instanceof Datum.Point p) return "point(" + p.x() + ", " + p.y() + ")";
        if (d instanceof Datum.Rect r) return "rect(" + r.left() + ", " + r.top() + ", " + r.right() + ", " + r.bottom() + ")";
        if (d instanceof Datum.Color c) return "color(" + c.r() + ", " + c.g() + ", " + c.b() + ")";
        if (d instanceof Datum.ScriptInstance si) return "<script#" + si.scriptId() + ">";
        return d.toString();
    }

    /**
     * Truncate a string if it exceeds the maximum length.
     */
    public static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
