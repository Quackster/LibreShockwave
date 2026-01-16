package com.libreshockwave.lingo;

/**
 * Types of string chunks in Lingo.
 * Used for operations like "word 3 of line 2 of myString".
 */
public enum StringChunkType {
    ITEM(0x01, "item"),
    WORD(0x02, "word"),
    CHAR(0x03, "char"),
    LINE(0x04, "line");

    private final int code;
    private final String name;

    StringChunkType(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static StringChunkType fromCode(int code) {
        return switch (code) {
            case 0x01 -> ITEM;
            case 0x02 -> WORD;
            case 0x03 -> CHAR;
            case 0x04 -> LINE;
            default -> throw new IllegalArgumentException("Unknown chunk type code: " + code);
        };
    }

    public static StringChunkType fromName(String name) {
        return switch (name.toLowerCase()) {
            case "item" -> ITEM;
            case "word" -> WORD;
            case "char" -> CHAR;
            case "line" -> LINE;
            default -> throw new IllegalArgumentException("Unknown chunk type: " + name);
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
