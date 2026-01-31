package com.libreshockwave.tools.format;

/**
 * Utility for getting human-readable palette descriptions.
 */
public final class PaletteDescriptions {

    private PaletteDescriptions() {}

    /**
     * Returns a human-readable description for a palette ID.
     */
    public static String get(int paletteId) {
        return switch (paletteId) {
            case -1 -> "System Mac";
            case -2 -> "Rainbow";
            case -3 -> "Grayscale";
            case -4 -> "Pastels";
            case -5 -> "Vivid";
            case -6 -> "NTSC";
            case -7 -> "Metallic";
            case -101 -> "System Windows";
            case -102 -> "System Windows (D4)";
            default -> paletteId >= 0 ? "Cast Member #" + (paletteId + 1) : "Unknown (" + paletteId + ")";
        };
    }
}
