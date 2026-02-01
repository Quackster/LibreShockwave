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
     * @param str The source string
     * @param chunkType The type of chunk
     * @param itemDelimiter The item delimiter character
     * @return The number of chunks
     */
    public static int countChunks(String str, StringChunkType chunkType, char itemDelimiter) {
        if (str == null || str.isEmpty()) {
            return 0;
        }
        return splitIntoChunks(str, chunkType, itemDelimiter).size();
    }

    /**
     * Split a string into chunks based on chunk type.
     */
    public static List<String> splitIntoChunks(String str, StringChunkType chunkType, char itemDelimiter) {
        if (str == null || str.isEmpty()) {
            return List.of();
        }

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
                // Lines are separated by CR, LF, or CRLF
                List<String> lines = new ArrayList<>();
                StringBuilder current = new StringBuilder();
                for (int i = 0; i < str.length(); i++) {
                    char c = str.charAt(i);
                    if (c == '\r') {
                        lines.add(current.toString());
                        current.setLength(0);
                        // Skip LF if CRLF
                        if (i + 1 < str.length() && str.charAt(i + 1) == '\n') {
                            i++;
                        }
                    } else if (c == '\n') {
                        lines.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                // Add remaining content as final line
                if (current.length() > 0) {
                    lines.add(current.toString());
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
            case LINE -> "\n";
            case ITEM -> String.valueOf(itemDelimiter);
        };
    }
}
