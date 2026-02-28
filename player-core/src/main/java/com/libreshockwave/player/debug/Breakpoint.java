package com.libreshockwave.player.debug;

/**
 * Represents a breakpoint at a specific script location.
 * This is an immutable record.
 */
public record Breakpoint(
    int scriptId,
    String handlerName,
    int offset,
    boolean enabled
) {
    /**
     * Creates a simple enabled breakpoint.
     */
    public static Breakpoint simple(int scriptId, String handlerName, int offset) {
        return new Breakpoint(scriptId, handlerName, offset, true);
    }

    /**
     * Return a copy with enabled state changed.
     */
    public Breakpoint withEnabled(boolean enabled) {
        return new Breakpoint(scriptId, handlerName, offset, enabled);
    }

    /**
     * Unique key for this breakpoint (scriptId:handlerName:offset).
     */
    public String key() {
        return scriptId + ":" + handlerName + ":" + offset;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Breakpoint[").append(scriptId).append(":").append(handlerName).append(":").append(offset);
        if (!enabled) sb.append(", disabled");
        sb.append("]");
        return sb.toString();
    }
}
