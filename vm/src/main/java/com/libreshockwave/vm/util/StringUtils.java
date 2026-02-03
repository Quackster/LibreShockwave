package com.libreshockwave.vm.util;

/**
 * Utility methods for string manipulation in display/formatting contexts.
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * Truncate a string if it exceeds the maximum length.
     * Appends "..." to indicate truncation.
     *
     * @param s the string to truncate (may be null)
     * @param maxLength maximum length including the "..." suffix
     * @return truncated string, or empty string if input is null
     */
    public static String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Escape special characters for display purposes.
     * Converts newlines, carriage returns, tabs, and backslashes to escape sequences.
     *
     * @param s the string to escape (may be null)
     * @return escaped string, or empty string if input is null
     */
    public static String escapeForDisplay(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Escape special characters for HTML display.
     * Converts &amp;, &lt;, and &gt; to their HTML entity equivalents.
     *
     * @param s the string to escape (may be null)
     * @return HTML-escaped string, or empty string if input is null
     */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
