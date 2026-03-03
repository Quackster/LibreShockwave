package com.libreshockwave.id;

/**
 * 0-based frame array index (for internal array access).
 */
public record FrameIndex(int value) implements TypedId, Comparable<FrameIndex> {

    public FrameIndex {
        if (value < 0) {
            throw new IllegalArgumentException("FrameIndex must be >= 0, got " + value);
        }
    }

    /**
     * Convert to a 1-based Director UI frame number.
     */
    public FrameId toFrameId() {
        return new FrameId(value + 1);
    }

    @Override
    public int compareTo(FrameIndex other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "FrameIndex(" + value + ")";
    }
}
