package com.libreshockwave.id;

/**
 * Lingo VM variable types (matching dirplayer-rs ContextVars).
 */
public enum VarType {
    GLOBAL(0x1),
    GLOBAL2(0x2),
    PROPERTY(0x3),
    PARAM(0x4),
    LOCAL(0x5),
    FIELD(0x6);

    private final int code;

    VarType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static VarType fromCode(int code) {
        for (VarType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown VarType code: 0x" + Integer.toHexString(code));
    }
}
