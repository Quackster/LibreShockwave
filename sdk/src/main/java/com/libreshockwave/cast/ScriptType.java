package com.libreshockwave.cast;

/**
 * Types of Lingo scripts.
 */
public enum ScriptType {
    INVALID(0, "invalid"),
    SCORE(1, "score"),        // Behavior attached to sprite
    MOVIE(3, "movie"),        // Movie script
    PARENT(7, "parent"),      // Parent script (object factory)
    UNKNOWN(255, "unknown");

    private final int code;
    private final String name;

    ScriptType(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static ScriptType fromCode(int code) {
        for (ScriptType type : values()) {
            if (type.code == code) return type;
        }
        return UNKNOWN;
    }

    public boolean isBehavior() {
        return this == SCORE;
    }

    public boolean isMovieScript() {
        return this == MOVIE;
    }

    public boolean isParentScript() {
        return this == PARENT;
    }

    @Override
    public String toString() {
        return name;
    }
}
