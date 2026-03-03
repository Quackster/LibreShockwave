package com.libreshockwave.id;

/**
 * 1-based Director UI frame number (as shown in the Score window).
 */
public record FrameId(int value) implements TypedId, Comparable<FrameId> {

    public FrameId {
        if (value < 1) {
            throw new IllegalArgumentException("FrameId must be >= 1, got " + value);
        }
    }

    /**
     * Convert to a 0-based array index.
     */
    public FrameIndex toIndex() {
        return new FrameIndex(value - 1);
    }

    @Override
    public int compareTo(FrameId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "FrameId(" + value + ")";
    }
}
