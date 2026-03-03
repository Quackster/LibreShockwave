package com.libreshockwave.id;

/**
 * 0-based index into a ScriptNamesChunk name table.
 */
public record NameId(int value) implements TypedId, Comparable<NameId> {

    public NameId {
        if (value < 0) {
            throw new IllegalArgumentException("NameId must be >= 0, got " + value);
        }
    }

    @Override
    public int compareTo(NameId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "NameId(" + value + ")";
    }
}
