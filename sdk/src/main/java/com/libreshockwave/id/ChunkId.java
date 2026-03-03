package com.libreshockwave.id;

/**
 * File-level mmap resource ID (chunk identifier within a Director file).
 */
public record ChunkId(int value) implements TypedId, Comparable<ChunkId> {

    public ChunkId {
        if (value < 0) {
            throw new IllegalArgumentException("ChunkId must be >= 0, got " + value);
        }
    }

    @Override
    public int compareTo(ChunkId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "ChunkId(" + value + ")";
    }
}
