package com.libreshockwave.vm.util;

import com.libreshockwave.lingo.StringChunkType;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for string chunk operations in Lingo.
 * Handles item/word/char/line extraction and counting.
 */
public final class StringChunkUtils {

    private StringChunkUtils() {}

    // Per-type split caches (WASM is single-threaded, no synchronization needed).
    // Eliminates O(n²) cost when iterating: str.item.count then str.item[1]..str.item[n].
    // ITEM has a two-entry LRU cache because Lingo alternates delimiters within loops
    // (e.g., RETURN for outer iteration, "=" for key-value splitting).

    // ITEM cache: 2 entries to handle alternating delimiters
    private static String _item0Str; private static char _item0Delim; private static List<String> _item0Result;
    private static String _item1Str; private static char _item1Delim; private static List<String> _item1Result;
    private static boolean _item0Mru;

    // WORD, CHAR, LINE: single entry each (no alternation pattern)
    private static String _wordCacheStr; private static List<String> _wordResult;
    private static String _charCacheStr; private static List<String> _charResult;
    private static String _lineCacheStr; private static List<String> _lineResult;

    /**
     * Get the last chunk of a string.
     * @param str The source string
     * @param chunkType The type of chunk (item, word, char, line)
     * @param itemDelimiter The item delimiter character
     * @return The last chunk, or empty string if none
     */
    public static String getLastChunk(String str, StringChunkType chunkType, char itemDelimiter) {
        if (str == null || str.isEmpty()) {
            return "";
        }
        List<String> chunks = splitIntoChunks(str, chunkType, itemDelimiter);
        return chunks.isEmpty() ? "" : chunks.get(chunks.size() - 1);
    }

    /**
     * Get a specific chunk by index (1-based).
     * @param str The source string
     * @param chunkType The type of chunk
     * @param index 1-based index
     * @param itemDelimiter The item delimiter character
     * @return The chunk at the index, or empty string if out of range
     */
    public static String getChunk(String str, StringChunkType chunkType, int index, char itemDelimiter) {
        if (str == null || str.isEmpty() || index < 1) {
            return "";
        }
        List<String> chunks = splitIntoChunks(str, chunkType, itemDelimiter);
        if (index > chunks.size()) {
            return "";
        }
        return chunks.get(index - 1);
    }

    /**
     * Get a range of chunks (1-based, inclusive).
     * @param str The source string
     * @param chunkType The type of chunk
     * @param start 1-based start index
     * @param end 1-based end index (inclusive)
     * @param itemDelimiter The item delimiter character
     * @return The chunks in range joined by appropriate delimiter
     */
    public static String getChunkRange(String str, StringChunkType chunkType, int start, int end, char itemDelimiter) {
        if (str == null || str.isEmpty() || start < 1) {
            return "";
        }
        List<String> chunks = splitIntoChunks(str, chunkType, itemDelimiter);
        if (start > chunks.size()) {
            return "";
        }
        int actualEnd = Math.min(end, chunks.size());
        List<String> subList = chunks.subList(start - 1, actualEnd);
        return String.join(getDelimiter(chunkType, itemDelimiter), subList);
    }

    /**
     * Count the number of chunks in a string.
     * Returns 1 for empty strings when chunk type is ITEM or LINE.
     * @param str The source string
     * @param chunkType The type of chunk
     * @param itemDelimiter The item delimiter character
     * @return The number of chunks
     */
    public static int countChunks(String str, StringChunkType chunkType, char itemDelimiter) {
        if (str == null || str.isEmpty()) {
            // Match dirplayer-rs: items and lines count as 1 even for empty strings
            if (chunkType == StringChunkType.ITEM || chunkType == StringChunkType.LINE) {
                return 1;
            }
            return 0;
        }
        return splitIntoChunks(str, chunkType, itemDelimiter).size();
    }

    /**
     * Split a string into chunks based on chunk type.
     * Results are cached per type: repeated calls with the same string reference
     * return the cached result in O(1). ITEM type uses a two-entry LRU cache
     * to handle the common Lingo pattern of alternating delimiters within loops.
     */
    public static List<String> splitIntoChunks(String str, StringChunkType chunkType, char itemDelimiter) {
        if (str == null || str.isEmpty()) {
            return List.of();
        }

        // Per-type cache lookup
        switch (chunkType) {
            case ITEM: {
                // Two-entry LRU for ITEM (handles alternating RETURN/"=" delimiters)
                if (str == _item0Str && itemDelimiter == _item0Delim && _item0Result != null) {
                    _item0Mru = true;
                    return _item0Result;
                }
                if (str == _item1Str && itemDelimiter == _item1Delim && _item1Result != null) {
                    _item0Mru = false;
                    return _item1Result;
                }
                List<String> result = doSplit(str, chunkType, itemDelimiter);
                // Evict LRU slot
                if (_item0Mru) {
                    _item1Str = str; _item1Delim = itemDelimiter; _item1Result = result;
                    _item0Mru = false;
                } else {
                    _item0Str = str; _item0Delim = itemDelimiter; _item0Result = result;
                    _item0Mru = true;
                }
                return result;
            }
            case WORD: {
                if (str == _wordCacheStr && _wordResult != null) return _wordResult;
                List<String> result = doSplit(str, chunkType, itemDelimiter);
                _wordCacheStr = str; _wordResult = result;
                return result;
            }
            case CHAR: {
                if (str == _charCacheStr && _charResult != null) return _charResult;
                List<String> result = doSplit(str, chunkType, itemDelimiter);
                _charCacheStr = str; _charResult = result;
                return result;
            }
            case LINE: {
                if (str == _lineCacheStr && _lineResult != null) return _lineResult;
                List<String> result = doSplit(str, chunkType, itemDelimiter);
                _lineCacheStr = str; _lineResult = result;
                return result;
            }
            default: {
                return doSplit(str, chunkType, itemDelimiter);
            }
        }
    }

    /**
     * Perform the actual string split without caching.
     */
    private static List<String> doSplit(String str, StringChunkType chunkType, char itemDelimiter) {
        return switch (chunkType) {
            case CHAR -> {
                List<String> chars = new ArrayList<>(str.length());
                for (int i = 0; i < str.length(); i++) {
                    chars.add(String.valueOf(str.charAt(i)));
                }
                yield chars;
            }
            case WORD -> {
                // Words are separated by spaces
                List<String> words = new ArrayList<>();
                StringBuilder current = new StringBuilder();
                for (int i = 0; i < str.length(); i++) {
                    char c = str.charAt(i);
                    if (Character.isWhitespace(c)) {
                        if (current.length() > 0) {
                            words.add(current.toString());
                            current.setLength(0);
                        }
                    } else {
                        current.append(c);
                    }
                }
                if (current.length() > 0) {
                    words.add(current.toString());
                }
                yield words;
            }
            case LINE -> {
                // Match dirplayer-rs: pick ONE delimiter for the whole string
                String lineDelim = pickLineDelimiter(str);
                List<String> lines = new ArrayList<>();
                int start = 0;
                int delimLen = lineDelim.length();
                while (true) {
                    int idx = str.indexOf(lineDelim, start);
                    if (idx == -1) {
                        lines.add(str.substring(start));
                        break;
                    }
                    lines.add(str.substring(start, idx));
                    start = idx + delimLen;
                }
                yield lines;
            }
            case ITEM -> {
                // Items are separated by itemDelimiter
                List<String> items = new ArrayList<>();
                StringBuilder current = new StringBuilder();
                for (int i = 0; i < str.length(); i++) {
                    char c = str.charAt(i);
                    if (c == itemDelimiter) {
                        items.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                // Add remaining content
                items.add(current.toString());
                yield items;
            }
        };
    }

    /**
     * Get the delimiter string for a chunk type.
     */
    private static String getDelimiter(StringChunkType chunkType, char itemDelimiter) {
        return switch (chunkType) {
            case CHAR -> "";
            case WORD -> " ";
            case LINE -> "\r\n";
            case ITEM -> String.valueOf(itemDelimiter);
        };
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
}
