package com.libreshockwave.player.format;

import com.libreshockwave.vm.Datum;

/**
 * Utility for formatting Datum values as human-readable strings.
 * Delegates to {@link com.libreshockwave.vm.DatumFormatter}.
 */
public final class DatumFormatter {

    private DatumFormatter() {}

    /**
     * Format a Datum value as a string.
     */
    public static String format(Datum d) {
        return com.libreshockwave.vm.DatumFormatter.format(d);
    }

    /**
     * Format a Datum value with its type prefix.
     */
    public static String formatWithType(Datum d) {
        return com.libreshockwave.vm.DatumFormatter.formatWithType(d);
    }
}
