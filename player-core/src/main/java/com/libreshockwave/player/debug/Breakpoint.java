package com.libreshockwave.player.debug;

/**
 * Represents a breakpoint with optional conditions, log messages, and hit count tracking.
 * This is an immutable record - use the with* methods to create modified copies.
 */
public record Breakpoint(
    int scriptId,
    int offset,
    boolean enabled,
    String condition,          // null = unconditional, non-null = Lingo expression
    String logMessage,         // null = normal breakpoint, non-null = log point (doesn't pause)
    int hitCount,              // runtime counter (times hit)
    int hitCountThreshold      // 0 = always break, >0 = break after N hits
) {
    /**
     * Creates a simple enabled breakpoint with no condition.
     */
    public static Breakpoint simple(int scriptId, int offset) {
        return new Breakpoint(scriptId, offset, true, null, null, 0, 0);
    }

    /**
     * Creates a conditional breakpoint.
     */
    public static Breakpoint conditional(int scriptId, int offset, String condition) {
        return new Breakpoint(scriptId, offset, true, condition, null, 0, 0);
    }

    /**
     * Creates a log point that logs a message without pausing.
     */
    public static Breakpoint logPoint(int scriptId, int offset, String logMessage) {
        return new Breakpoint(scriptId, offset, true, null, logMessage, 0, 0);
    }

    /**
     * Check if this is a log point (logs message without pausing).
     */
    public boolean isLogPoint() {
        return logMessage != null && !logMessage.isEmpty();
    }

    /**
     * Check if this breakpoint has a condition.
     */
    public boolean isConditional() {
        return condition != null && !condition.isBlank();
    }

    /**
     * Return a copy with enabled state changed.
     */
    public Breakpoint withEnabled(boolean enabled) {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, hitCount, hitCountThreshold);
    }

    /**
     * Return a copy with condition changed.
     */
    public Breakpoint withCondition(String condition) {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, hitCount, hitCountThreshold);
    }

    /**
     * Return a copy with log message changed.
     */
    public Breakpoint withLogMessage(String logMessage) {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, hitCount, hitCountThreshold);
    }

    /**
     * Return a copy with hit count changed.
     */
    public Breakpoint withHitCount(int hitCount) {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, hitCount, hitCountThreshold);
    }

    /**
     * Return a copy with hit count incremented by 1.
     */
    public Breakpoint withIncrementedHitCount() {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, hitCount + 1, hitCountThreshold);
    }

    /**
     * Return a copy with hit count threshold changed.
     */
    public Breakpoint withHitCountThreshold(int threshold) {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, hitCount, threshold);
    }

    /**
     * Return a copy with hit count reset to 0.
     */
    public Breakpoint withResetHitCount() {
        return new Breakpoint(scriptId, offset, enabled, condition, logMessage, 0, hitCountThreshold);
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
        if (isConditional()) sb.append(", condition=").append(condition);
        if (isLogPoint()) sb.append(", logPoint=").append(logMessage);
        if (hitCountThreshold > 0) sb.append(", threshold=").append(hitCountThreshold);
        sb.append(", hits=").append(hitCount).append("]");
        return sb.toString();
    }
}
