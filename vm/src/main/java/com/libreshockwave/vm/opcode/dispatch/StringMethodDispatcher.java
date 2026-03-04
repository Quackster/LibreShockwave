package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles method calls on string values.
 * Supports Lingo string chunk operations like count, getProp, getPropRef.
 */
public final class StringMethodDispatcher {

    private StringMethodDispatcher() {}

    // Per-type split caches (WASM is single-threaded).
    // ITEM has a two-entry LRU cache to handle alternating delimiters
    // (e.g., RETURN for outer loop, "=" for key-value splitting).

    // ITEM cache: 2 entries
    private static String _item0Str; private static char _item0Delim; private static String[] _item0Result;
    private static String _item1Str; private static char _item1Delim; private static String[] _item1Result;
    private static boolean _item0Mru;

    // WORD, LINE: single entry each
    private static String _wordCacheStr;
    private static String[] _wordCacheResult;

    private static String _lineCacheStr;
    private static String[] _lineCacheResult;

    public static Datum dispatch(Datum.Str str, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        // Get the item delimiter (default comma)
        char itemDelimiter = getItemDelimiter();

        // Use if-else instead of switch to avoid TeaVM compiler bug where
        // case "char" in a string switch is silently ignored (Java keyword conflict)
        if ("length".equals(method)) {
            return Datum.of(str.value().length());
        } else if ("char".equals(method)) {
            if (args.isEmpty()) return Datum.EMPTY_STRING;
            int index = args.get(0).toInt();
            if (index >= 1 && index <= str.value().length()) {
                return Datum.of(String.valueOf(str.value().charAt(index - 1)));
            }
            return Datum.EMPTY_STRING;
        } else if ("count".equals(method)) {
            // count(str, #char) or count(str, #word) etc.
            if (args.isEmpty()) return Datum.of(str.value().length());
            Datum chunkType = args.get(0);
            if (chunkType instanceof Datum.Symbol s) {
                String type = s.name().toLowerCase();
                // if-else to avoid TeaVM "char" keyword bug in switch
                if ("char".equals(type)) return Datum.of(str.value().length());
                else if ("word".equals(type)) return Datum.of(countWords(str.value()));
                else if ("line".equals(type)) return Datum.of(countLines(str.value()));
                else if ("item".equals(type)) return Datum.of(countItems(str.value(), itemDelimiter));
                else return Datum.of(str.value().length());
            }
            return Datum.of(str.value().length());
        } else if ("getpropref".equals(method)) {
            // getPropRef(str, #chunkType, index) - gets a single chunk from string
            if (args.size() < 2) return Datum.EMPTY_STRING;
            Datum chunkType = args.get(0);
            int index = args.get(1).toInt();
            if (!(chunkType instanceof Datum.Symbol s)) return Datum.EMPTY_STRING;
            String type = s.name().toLowerCase();
            return Datum.of(getStringChunk(str.value(), type, index, index, itemDelimiter));
        } else if ("getprop".equals(method)) {
            // getProp(str, #chunkType, startIndex, endIndex?)
            if (args.size() < 2) return Datum.EMPTY_STRING;
            Datum chunkType = args.get(0);
            int startIndex = args.get(1).toInt();
            int endIndex = args.size() >= 3 ? args.get(2).toInt() : startIndex;
            if (!(chunkType instanceof Datum.Symbol s)) return Datum.EMPTY_STRING;
            String type = s.name().toLowerCase();
            return Datum.of(getStringChunk(str.value(), type, startIndex, endIndex, itemDelimiter));
        }
        return Datum.VOID;
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
     * Uses cached splits to avoid O(n²) re-splitting on iteration.
     */
    private static String getStringChunk(String str, String chunkType, int start, int end, char itemDelimiter) {
        if (str.isEmpty() || start < 1) return "";

        // Use if-else instead of switch to avoid TeaVM compiler bug where
        // case "char" in a string switch is silently ignored (keyword conflict)
        if ("char".equals(chunkType)) {
            int actualEnd = resolveEnd(end, start, str.length());
            int s = Math.max(0, start - 1);
            int e = Math.min(str.length(), actualEnd);
            if (s >= str.length() || s >= e) return "";
            return str.substring(s, e);
        } else if ("word".equals(chunkType)) {
            String[] words = cachedSplitWords(str);
            int actualEnd = resolveEnd(end, start, words.length);
            if (start > words.length) return "";
            int s = start - 1;
            int e = Math.min(words.length, actualEnd);
            StringBuilder sb = new StringBuilder();
            for (int i = s; i < e; i++) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(words[i]);
            }
            return sb.toString();
        } else if ("line".equals(chunkType)) {
            String[] lines = cachedSplitLines(str);
            int actualEnd = resolveEnd(end, start, lines.length);
            if (start > lines.length) return "";
            int s = start - 1;
            int e = Math.min(lines.length, actualEnd);
            StringBuilder sb = new StringBuilder();
            for (int i = s; i < e; i++) {
                if (sb.length() > 0) sb.append("\r\n");
                sb.append(lines[i]);
            }
            return sb.toString();
        } else if ("item".equals(chunkType)) {
            String[] items = cachedSplitItems(str, itemDelimiter);
            int actualEnd = resolveEnd(end, start, items.length);
            if (start > items.length) return "";
            int s = start - 1;
            int e = Math.min(items.length, actualEnd);
            StringBuilder sb = new StringBuilder();
            for (int i = s; i < e; i++) {
                if (sb.length() > 0) sb.append(itemDelimiter);
                sb.append(items[i]);
            }
            return sb.toString();
        }
        return "";
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
        return cachedSplitWords(str).length;
    }

    private static int countLines(String str) {
        if (str.isEmpty()) return 1;
        return cachedSplitLines(str).length;
    }

    private static int countItems(String str, char delimiter) {
        if (str.isEmpty()) return 1;
        return cachedSplitItems(str, delimiter).length;
    }

    // ========================================================================
    // Cached split methods — avoid O(n²) re-splitting when iterating chunks.
    // Simple char loops instead of regex for WASM performance.
    // ========================================================================

    /** Split by item delimiter with two-entry LRU cache (handles alternating delimiters). */
    private static String[] cachedSplitItems(String str, char delimiter) {
        // Check slot 0
        if (str == _item0Str && delimiter == _item0Delim && _item0Result != null) {
            _item0Mru = true;
            return _item0Result;
        }
        // Check slot 1
        if (str == _item1Str && delimiter == _item1Delim && _item1Result != null) {
            _item0Mru = false;
            return _item1Result;
        }
        // Cache miss — do split with simple char loop (no regex overhead)
        ArrayList<String> items = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == delimiter) {
                items.add(str.substring(start, i));
                start = i + 1;
            }
        }
        items.add(str.substring(start));
        String[] result = items.toArray(new String[0]);
        // Evict LRU slot
        if (_item0Mru) {
            _item1Str = str; _item1Delim = delimiter; _item1Result = result;
            _item0Mru = false;
        } else {
            _item0Str = str; _item0Delim = delimiter; _item0Result = result;
            _item0Mru = true;
        }
        return result;
    }

    /** Split by whitespace with single-entry cache. */
    private static String[] cachedSplitWords(String str) {
        if (str == _wordCacheStr && _wordCacheResult != null) {
            return _wordCacheResult;
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty()) {
            _wordCacheStr = str;
            _wordCacheResult = new String[0];
            return _wordCacheResult;
        }
        // Simple whitespace split — no regex overhead
        ArrayList<String> words = new ArrayList<>();
        int wordStart = 0;
        boolean inWord = false;
        for (int i = 0; i <= trimmed.length(); i++) {
            boolean isSpace = i == trimmed.length() || Character.isWhitespace(trimmed.charAt(i));
            if (isSpace && inWord) {
                words.add(trimmed.substring(wordStart, i));
                inWord = false;
            } else if (!isSpace && !inWord) {
                wordStart = i;
                inWord = true;
            }
        }
        String[] result = words.toArray(new String[0]);
        _wordCacheStr = str;
        _wordCacheResult = result;
        return result;
    }

    /** Split by line delimiter with single-entry cache. */
    private static String[] cachedSplitLines(String str) {
        if (str == _lineCacheStr && _lineCacheResult != null) {
            return _lineCacheResult;
        }
        String lineDelimiter = pickLineDelimiter(str);
        int delimLen = lineDelimiter.length();
        ArrayList<String> lines = new ArrayList<>();
        int start = 0;
        while (true) {
            int idx = str.indexOf(lineDelimiter, start);
            if (idx == -1) {
                lines.add(str.substring(start));
                break;
            }
            lines.add(str.substring(start, idx));
            start = idx + delimLen;
        }
        String[] result = lines.toArray(new String[0]);
        _lineCacheStr = str;
        _lineCacheResult = result;
        return result;
    }
}
