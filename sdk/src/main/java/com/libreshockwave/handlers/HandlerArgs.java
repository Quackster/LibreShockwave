package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;

import java.util.List;

/**
 * Utility class for handler argument validation and extraction.
 * Consolidates repetitive argument checking patterns across all handler classes.
 *
 * Refactoring decision: This class eliminates 60+ duplicate validation checks
 * (e.g., "if (args.isEmpty()) return ...") scattered across handler files.
 */
public final class HandlerArgs {

    private HandlerArgs() {} // Utility class

    // === Argument count checks ===

    /**
     * Check if args list has at least the required count.
     */
    public static boolean hasAtLeast(List<Datum> args, int count) {
        return args.size() >= count;
    }

    /**
     * Returns true if args is empty.
     */
    public static boolean isEmpty(List<Datum> args) {
        return args.isEmpty();
    }

    // === Integer extraction ===

    /**
     * Get int at index, or return defaultValue if index is out of bounds.
     */
    public static int getInt(List<Datum> args, int index, int defaultValue) {
        if (index < 0 || index >= args.size()) return defaultValue;
        return args.get(index).intValue();
    }

    /**
     * Get int at index 0, or return 0 if empty.
     */
    public static int getInt0(List<Datum> args) {
        return getInt(args, 0, 0);
    }

    /**
     * Get int at index 1, or return defaultValue if not present.
     */
    public static int getInt1(List<Datum> args, int defaultValue) {
        return getInt(args, 1, defaultValue);
    }

    // === Float extraction ===

    /**
     * Get float at index, or return defaultValue if index is out of bounds.
     */
    public static float getFloat(List<Datum> args, int index, float defaultValue) {
        if (index < 0 || index >= args.size()) return defaultValue;
        return args.get(index).floatValue();
    }

    /**
     * Get float at index 0, or return 0.0f if empty.
     */
    public static float getFloat0(List<Datum> args) {
        return getFloat(args, 0, 0.0f);
    }

    /**
     * Get float at index 1, or return defaultValue if not present.
     */
    public static float getFloat1(List<Datum> args, float defaultValue) {
        return getFloat(args, 1, defaultValue);
    }

    // === String extraction ===

    /**
     * Get string at index, or return defaultValue if index is out of bounds.
     */
    public static String getString(List<Datum> args, int index, String defaultValue) {
        if (index < 0 || index >= args.size()) return defaultValue;
        return args.get(index).stringValue();
    }

    /**
     * Get string at index 0, or return "" if empty.
     */
    public static String getString0(List<Datum> args) {
        return getString(args, 0, "");
    }

    /**
     * Get string at index 1, or return defaultValue if not present.
     */
    public static String getString1(List<Datum> args, String defaultValue) {
        return getString(args, 1, defaultValue);
    }

    // === Datum extraction ===

    /**
     * Get Datum at index, or return Datum.voidValue() if index is out of bounds.
     */
    public static Datum get(List<Datum> args, int index) {
        if (index < 0 || index >= args.size()) return Datum.voidValue();
        return args.get(index);
    }

    /**
     * Get Datum at index 0, or return Datum.voidValue() if empty.
     */
    public static Datum get0(List<Datum> args) {
        return get(args, 0);
    }

    /**
     * Get Datum at index 1, or return Datum.voidValue() if not present.
     */
    public static Datum get1(List<Datum> args) {
        return get(args, 1);
    }

    /**
     * Get Datum at index 2, or return Datum.voidValue() if not present.
     */
    public static Datum get2(List<Datum> args) {
        return get(args, 2);
    }

    // === Default return patterns ===

    /**
     * Return zero int if args is empty, otherwise null to indicate processing should continue.
     * Usage: var result = requireNonEmpty(args, Datum.of(0)); if (result != null) return result;
     */
    public static Datum requireNonEmpty(List<Datum> args, Datum defaultValue) {
        return args.isEmpty() ? defaultValue : null;
    }

    /**
     * Return defaultValue if args has fewer than required count.
     */
    public static Datum requireAtLeast(List<Datum> args, int count, Datum defaultValue) {
        return args.size() < count ? defaultValue : null;
    }

    // === Type checking helpers ===

    /**
     * Check if the datum at index is a DList.
     */
    public static boolean isList(List<Datum> args, int index) {
        if (index < 0 || index >= args.size()) return false;
        return args.get(index) instanceof Datum.DList;
    }

    /**
     * Check if the datum at index is a PropList.
     */
    public static boolean isPropList(List<Datum> args, int index) {
        if (index < 0 || index >= args.size()) return false;
        return args.get(index) instanceof Datum.PropList;
    }

    /**
     * Get as DList if the datum at index is a list, null otherwise.
     */
    public static Datum.DList asList(List<Datum> args, int index) {
        if (index < 0 || index >= args.size()) return null;
        Datum d = args.get(index);
        return d instanceof Datum.DList list ? list : null;
    }

    /**
     * Get as PropList if the datum at index is a proplist, null otherwise.
     */
    public static Datum.PropList asPropList(List<Datum> args, int index) {
        if (index < 0 || index >= args.size()) return null;
        Datum d = args.get(index);
        return d instanceof Datum.PropList pl ? pl : null;
    }
}
