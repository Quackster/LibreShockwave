package com.libreshockwave.id;

/**
 * 1-based member slot number within a cast library.
 */
public record MemberId(int value) implements TypedId, Comparable<MemberId> {

    public MemberId {
        if (value < 1) {
            throw new IllegalArgumentException("MemberId must be >= 1, got " + value);
        }
    }

    @Override
    public int compareTo(MemberId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "MemberId(" + value + ")";
    }
}
