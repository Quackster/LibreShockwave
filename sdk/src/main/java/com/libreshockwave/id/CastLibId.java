package com.libreshockwave.id;

/**
 * 1-based cast library number.
 */
public record CastLibId(int value) implements TypedId, Comparable<CastLibId> {

    public CastLibId {
        if (value < 1) {
            throw new IllegalArgumentException("CastLibId must be >= 1, got " + value);
        }
    }

    @Override
    public int compareTo(CastLibId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "CastLibId(" + value + ")";
    }
}
