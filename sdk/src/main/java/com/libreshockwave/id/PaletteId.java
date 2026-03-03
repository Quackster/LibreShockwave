package com.libreshockwave.id;

/**
 * Palette identifier. Negative values indicate built-in system palettes.
 */
public record PaletteId(int value) implements TypedId, Comparable<PaletteId> {

    /**
     * Returns true if this references a built-in system palette.
     */
    public boolean isBuiltIn() {
        return value < 0;
    }

    @Override
    public int compareTo(PaletteId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "PaletteId(" + value + ")";
    }
}
