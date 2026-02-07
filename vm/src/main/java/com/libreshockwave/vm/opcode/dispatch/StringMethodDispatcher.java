package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;

import java.util.List;

/**
 * Handles method calls on string values.
 * Supports Lingo string chunk operations like count, getProp, getPropRef.
 */
public final class StringMethodDispatcher {

    private StringMethodDispatcher() {}

    public static Datum dispatch(Datum.Str str, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        // Get the item delimiter (default comma)
        char itemDelimiter = getItemDelimiter();

        return switch (method) {
            case "length" -> Datum.of(str.value().length());
            case "char" -> {
                if (args.isEmpty()) yield Datum.EMPTY_STRING;
                int index = args.get(0).toInt();
                if (index >= 1 && index <= str.value().length()) {
                    yield Datum.of(String.valueOf(str.value().charAt(index - 1)));
                }
                yield Datum.EMPTY_STRING;
            }
            case "count" -> {
                // count(str, #char) or count(str, #word) etc.
                if (args.isEmpty()) yield Datum.of(str.value().length());
                Datum chunkType = args.get(0);
                if (chunkType instanceof Datum.Symbol s) {
                    String type = s.name().toLowerCase();
                    yield switch (type) {
                        case "char" -> Datum.of(str.value().length());
                        case "word" -> Datum.of(countWords(str.value()));
                        case "line" -> Datum.of(countLines(str.value()));
                        case "item" -> Datum.of(countItems(str.value(), itemDelimiter));
                        default -> Datum.of(str.value().length());
                    };
                }
                yield Datum.of(str.value().length());
            }
            case "getpropref" -> {
                // getPropRef(str, #chunkType, index) - gets a single chunk from string
                // e.g., getPropRef(str, #item, 1) gets the first item
                // e.g., getPropRef(str, #word, 2) gets the second word
                if (args.size() < 2) yield Datum.EMPTY_STRING;

                Datum chunkType = args.get(0);
                int index = args.get(1).toInt();

                if (!(chunkType instanceof Datum.Symbol s)) {
                    yield Datum.EMPTY_STRING;
                }

                String type = s.name().toLowerCase();
                String result = getStringChunk(str.value(), type, index, index, itemDelimiter);
                yield Datum.of(result);
            }
            case "getprop" -> {
                // getProp(str, #chunkType, startIndex, endIndex?)
                // e.g., getProp(str, #char, 1, 5) gets chars 1-5
                // e.g., getProp(str, #word, 1, count(str, #word)) gets word 1 to last
                if (args.size() < 2) yield Datum.EMPTY_STRING;

                Datum chunkType = args.get(0);
                int startIndex = args.get(1).toInt();
                int endIndex = args.size() >= 3 ? args.get(2).toInt() : startIndex;

                if (!(chunkType instanceof Datum.Symbol s)) {
                    yield Datum.EMPTY_STRING;
                }

                String type = s.name().toLowerCase();
                String result = getStringChunk(str.value(), type, startIndex, endIndex, itemDelimiter);
                yield Datum.of(result);
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Get the current item delimiter from MoviePropertyProvider.
     */
    private static char getItemDelimiter() {
        var provider = MoviePropertyProvider.getProvider();
        if (provider != null) {
            return provider.getItemDelimiter();
        }
        return ',';
    }

    /**
     * Get a chunk from a string.
     * Handles edge cases: end == 0 means single element (set end = start),
     * end == -1 means to-end (set end = chunk count).
     */
    private static String getStringChunk(String str, String chunkType, int start, int end, char itemDelimiter) {
        if (str.isEmpty() || start < 1) return "";

        return switch (chunkType) {
            case "char" -> {
                int actualEnd = resolveEnd(end, start, str.length());
                int s = Math.max(0, start - 1);
                int e = Math.min(str.length(), actualEnd);
                if (s >= str.length() || s >= e) yield "";
                yield str.substring(s, e);
            }
            case "word" -> {
                String[] words = str.trim().split("\\s+");
                int actualEnd = resolveEnd(end, start, words.length);
                if (start > words.length) yield "";
                int s = start - 1;
                int e = Math.min(words.length, actualEnd);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(words[i]);
                }
                yield sb.toString();
            }
            case "line" -> {
                String lineDelimiter = pickLineDelimiter(str);
                String[] lines = str.split(java.util.regex.Pattern.quote(lineDelimiter), -1);
                int actualEnd = resolveEnd(end, start, lines.length);
                if (start > lines.length) yield "";
                int s = start - 1;
                int e = Math.min(lines.length, actualEnd);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append("\r\n");
                    sb.append(lines[i]);
                }
                yield sb.toString();
            }
            case "item" -> {
                // Simple split like dirplayer-rs - no bracket/quote awareness
                String[] items = str.split(String.valueOf(itemDelimiter), -1);
                int actualEnd = resolveEnd(end, start, items.length);
                if (start > items.length) yield "";
                int s = start - 1;
                int e = Math.min(items.length, actualEnd);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append(itemDelimiter);
                    sb.append(items[i]);
                }
                yield sb.toString();
            }
            default -> "";
        };
    }

    /**
     * Resolve the end parameter for chunk ranges.
     * end == 0 means single element (return start), end == -1 means to-end (return count).
     */
    private static int resolveEnd(int end, int start, int count) {
        if (end == 0) return start;
        if (end == -1) return count;
        return end;
    }

    /**
     * Pick ONE line delimiter for the entire string, matching dirplayer-rs algorithm.
     * Check for \r\n first, then \n, then \r.
     */
    private static String pickLineDelimiter(String str) {
        if (str.contains("\r\n")) return "\r\n";
        if (str.contains("\n")) return "\n";
        if (str.contains("\r")) return "\r";
        return "\r\n"; // default
    }

    private static int countWords(String str) {
        if (str.isEmpty()) return 0;
        return str.trim().split("\\s+").length;
    }

    private static int countLines(String str) {
        if (str.isEmpty()) return 1;
        String lineDelimiter = pickLineDelimiter(str);
        return str.split(java.util.regex.Pattern.quote(lineDelimiter), -1).length;
    }

    private static int countItems(String str, char delimiter) {
        if (str.isEmpty()) return 1;
        // Simple split like dirplayer-rs
        return str.split(String.valueOf(delimiter), -1).length;
    }
}
