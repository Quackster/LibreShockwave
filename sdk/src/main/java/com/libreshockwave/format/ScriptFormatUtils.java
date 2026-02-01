package com.libreshockwave.format;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;

/**
 * Shared formatting utilities for scripts, literals, and related data.
 * Used by SDK, VM, player, and cast-extractor modules.
 */
public final class ScriptFormatUtils {

    private ScriptFormatUtils() {}

    // ========== Literal Type Formatting ==========

    /**
     * Get a human-readable name for a literal type code.
     * @param typeCode The literal type code (1=string, 4=int, 9=float)
     * @return The type name
     */
    public static String getLiteralTypeName(int typeCode) {
        return switch (typeCode) {
            case 1 -> "string";
            case 4 -> "int";
            case 9 -> "float";
            default -> "type" + typeCode;
        };
    }

    /**
     * Get a short name for a literal type code (for compact display).
     * @param typeCode The literal type code
     * @return The short type name
     */
    public static String getLiteralTypeNameShort(int typeCode) {
        return switch (typeCode) {
            case 1 -> "str";
            case 4 -> "int";
            case 9 -> "float";
            default -> "lit";
        };
    }

    /**
     * Format a literal entry for display.
     * @param lit The literal entry
     * @return Formatted string like "string: \"hello\"" or "int: 42"
     */
    public static String formatLiteral(ScriptChunk.LiteralEntry lit) {
        String typeName = getLiteralTypeName(lit.type());
        String valueStr = formatLiteralValue(lit.value());
        return typeName + ": " + valueStr;
    }

    /**
     * Format a literal value for display.
     * Strings are wrapped in quotes.
     */
    public static String formatLiteralValue(Object value) {
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }

    /**
     * Format a literal value with optional truncation for strings.
     */
    public static String formatLiteralValue(Object value, int maxStringLength) {
        if (value instanceof String s) {
            String truncated = truncate(s, maxStringLength);
            return "\"" + truncated + "\"";
        }
        return String.valueOf(value);
    }

    // ========== Script Type Formatting ==========

    /**
     * Get a human-readable name for a script type.
     * @param scriptType The script type enum
     * @return Display name like "Movie Script", "Behavior", etc.
     */
    public static String getScriptTypeName(ScriptChunk.ScriptType scriptType) {
        if (scriptType == null) return "Unknown";
        return switch (scriptType) {
            case MOVIE_SCRIPT -> "Movie Script";
            case BEHAVIOR -> "Behavior";
            case PARENT -> "Parent Script";
            case SCORE -> "Score Script";
            default -> "Script";
        };
    }

    // ========== Handler/Name Resolution ==========

    /**
     * Resolve a name ID to a string, with fallback if names unavailable.
     * @param names The script names chunk (may be null)
     * @param nameId The name ID to resolve
     * @return The resolved name or a fallback like "#123"
     */
    public static String resolveName(ScriptNamesChunk names, int nameId) {
        if (names != null && nameId >= 0 && nameId < names.names().size()) {
            return names.getName(nameId);
        }
        return "#" + nameId;
    }

    /**
     * Resolve a handler name with a descriptive fallback.
     */
    public static String resolveHandlerName(ScriptNamesChunk names, int nameId) {
        if (names != null && nameId >= 0 && nameId < names.names().size()) {
            return names.getName(nameId);
        }
        return "handler#" + nameId;
    }

    // ========== String Utilities ==========

    /**
     * Truncate a string if it exceeds the maximum length.
     * @param s The string to truncate
     * @param maxLength Maximum length including ellipsis
     * @return The truncated string with "..." if needed
     */
    public static String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        if (maxLength <= 3) return "...";
        return s.substring(0, maxLength - 3) + "...";
    }

    /**
     * Normalize line endings to spaces (for single-line display).
     */
    public static String normalizeLineEndings(String s) {
        if (s == null) return "";
        return s.replace("\r\n", " ")
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();
    }
}
