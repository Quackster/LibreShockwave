package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.lingo.StringChunkType;
import com.libreshockwave.vm.LingoVM;

import java.util.List;

/**
 * Built-in string handlers for Lingo.
 */
public class StringHandlers {

    public static void register(LingoVM vm) {
        vm.registerBuiltin("string", StringHandlers::toString);
        vm.registerBuiltin("length", StringHandlers::length);
        vm.registerBuiltin("chars", StringHandlers::chars);
        vm.registerBuiltin("char", StringHandlers::charAt);
        vm.registerBuiltin("word", StringHandlers::word);
        vm.registerBuiltin("line", StringHandlers::line);
        vm.registerBuiltin("item", StringHandlers::item);
        vm.registerBuiltin("offset", StringHandlers::offset);
        vm.registerBuiltin("charToNum", StringHandlers::charToNum);
        vm.registerBuiltin("numToChar", StringHandlers::numToChar);
    }

    private static Datum toString(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of("");
        return Datum.of(args.get(0).stringValue());
    }

    private static Datum length(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        return Datum.of(args.get(0).stringValue().length());
    }

    private static Datum chars(LingoVM vm, List<Datum> args) {
        if (args.size() < 3) return Datum.of("");
        String s = args.get(0).stringValue();
        int start = args.get(1).intValue();
        int end = args.get(2).intValue();

        if (start < 1) start = 1;
        if (end > s.length()) end = s.length();
        if (start > end) return Datum.of("");

        return Datum.of(s.substring(start - 1, end));
    }

    private static Datum charAt(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of("");
        String s = args.get(0).stringValue();
        int index = args.get(1).intValue();

        if (index < 1 || index > s.length()) return Datum.of("");
        return Datum.of(String.valueOf(s.charAt(index - 1)));
    }

    private static Datum word(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of("");
        String s = args.get(0).stringValue();
        int wordNum = args.get(1).intValue();

        String[] words = s.split("\\s+");
        if (wordNum < 1 || wordNum > words.length) return Datum.of("");
        return Datum.of(words[wordNum - 1]);
    }

    private static Datum line(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of("");
        String s = args.get(0).stringValue();
        int lineNum = args.get(1).intValue();

        String[] lines = s.split("\r|\n|\r\n");
        if (lineNum < 1 || lineNum > lines.length) return Datum.of("");
        return Datum.of(lines[lineNum - 1]);
    }

    private static Datum item(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of("");
        String s = args.get(0).stringValue();
        int itemNum = args.get(1).intValue();
        char delimiter = ',';

        // Item delimiter can be customized in Lingo
        String[] items = s.split(String.valueOf(delimiter));
        if (itemNum < 1 || itemNum > items.length) return Datum.of("");
        return Datum.of(items[itemNum - 1].trim());
    }

    private static Datum offset(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.of(0);
        String needle = args.get(0).stringValue();
        String haystack = args.get(1).stringValue();

        int index = haystack.indexOf(needle);
        return Datum.of(index + 1); // 1-based, 0 if not found
    }

    private static Datum charToNum(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        String s = args.get(0).stringValue();
        if (s.isEmpty()) return Datum.of(0);
        return Datum.of((int) s.charAt(0));
    }

    private static Datum numToChar(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of("");
        int num = args.get(0).intValue();
        if (num < 0 || num > 65535) return Datum.of("");
        return Datum.of(String.valueOf((char) num));
    }

    /**
     * Extract a chunk from a string based on chunk type.
     */
    public static String extractChunk(String source, StringChunkType type, int start, int end, char itemDelimiter) {
        String[] parts = splitByChunkType(source, type, itemDelimiter);

        if (start < 1) start = 1;
        if (end < start) end = start;
        if (start > parts.length) return "";
        if (end > parts.length) end = parts.length;

        StringBuilder result = new StringBuilder();
        String separator = getChunkSeparator(type, itemDelimiter);

        for (int i = start - 1; i < end; i++) {
            if (result.length() > 0) result.append(separator);
            result.append(parts[i]);
        }

        return result.toString();
    }

    private static String[] splitByChunkType(String source, StringChunkType type, char itemDelimiter) {
        return switch (type) {
            case CHAR -> {
                String[] chars = new String[source.length()];
                for (int i = 0; i < source.length(); i++) {
                    chars[i] = String.valueOf(source.charAt(i));
                }
                yield chars;
            }
            case WORD -> source.split("\\s+");
            case LINE -> source.split("\r\n|\r|\n");
            case ITEM -> source.split(String.valueOf(itemDelimiter));
        };
    }

    private static String getChunkSeparator(StringChunkType type, char itemDelimiter) {
        return switch (type) {
            case CHAR -> "";
            case WORD -> " ";
            case LINE -> "\n";
            case ITEM -> String.valueOf(itemDelimiter);
        };
    }

    /**
     * Count chunks in a string.
     */
    public static int countChunks(String source, StringChunkType type, char itemDelimiter) {
        if (source.isEmpty()) return 0;
        return splitByChunkType(source, type, itemDelimiter).length;
    }
}
