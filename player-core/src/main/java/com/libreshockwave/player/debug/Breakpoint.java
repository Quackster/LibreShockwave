package com.libreshockwave.player.debug;

/**
 * Represents a breakpoint at a specific script location.
 * This is an immutable record.
 */
public record Breakpoint(
    int scriptId,
    int offset,
    boolean enabled
) {
    /**
     * Creates a simple enabled breakpoint.
     */
    public static Breakpoint simple(int scriptId, int offset) {
        return new Breakpoint(scriptId, offset, true);
    }

    /**
     * Return a copy with enabled state changed.
     */
    public Breakpoint withEnabled(boolean enabled) {
        return new Breakpoint(scriptId, offset, enabled);
    }

    /**
     * Unique key for this breakpoint (scriptId:offset).
     */
    public String key() {
        return scriptId + ":" + offset;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Breakpoint[").append(scriptId).append(":").append(offset);
        if (!enabled) sb.append(", disabled");
        sb.append("]");
        return sb.toString();
    }
}
