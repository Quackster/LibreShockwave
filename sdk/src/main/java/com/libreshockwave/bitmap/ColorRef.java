package com.libreshockwave.bitmap;

/**
 * Represents a color reference that can be either an RGB value or a palette index.
 * Used for Director sprite colorisation (foreColor/backColor).
 */
public sealed interface ColorRef permits ColorRef.Rgb, ColorRef.PaletteIndex {

    /**
     * RGB color value.
     */
    record Rgb(int r, int g, int b) implements ColorRef {
        public Rgb {
            r = Math.clamp(r, 0, 255);
            g = Math.clamp(g, 0, 255);
            b = Math.clamp(b, 0, 255);
        }

        /**
         * Create from packed RGB (0xRRGGBB).
         */
        public static Rgb fromPacked(int rgb) {
            return new Rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }

        /**
         * Create from hex string (e.g., "#FF0000" or "FF0000").
         */
        public static Rgb fromHex(String hex) {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            int rgb = Integer.parseInt(clean, 16);
            return fromPacked(rgb);
        }

        /**
         * Convert to packed RGB (0xRRGGBB).
         */
        public int toPacked() {
            return (r << 16) | (g << 8) | b;
        }

        /**
         * Convert to packed ARGB (0xAARRGGBB) with full opacity.
         */
        public int toArgb() {
            return 0xFF000000 | toPacked();
        }
    }

    /**
     * Palette index reference.
     */
    record PaletteIndex(int index) implements ColorRef {
        public PaletteIndex {
            index = Math.clamp(index, 0, 255);
        }

        /**
         * Resolve this palette index to RGB using the given palette.
         */
        public Rgb resolve(Palette palette) {
            if (palette == null) {
                palette = Palette.SYSTEM_MAC_PALETTE;
            }
            int[] rgb = palette.getRGB(index);
            return new Rgb(rgb[0], rgb[1], rgb[2]);
        }
    }

    /**
     * Resolve this ColorRef to RGB.
     * For RGB colors, returns self. For palette indices, looks up in the palette.
     */
    default Rgb toRgb(Palette palette) {
        return switch (this) {
            case Rgb rgb -> rgb;
            case PaletteIndex pi -> pi.resolve(palette);
        };
    }

    /**
     * Find the nearest palette index for this color in the given palette.
     */
    default int toNearestPaletteIndex(Palette palette) {
        if (this instanceof PaletteIndex pi) {
            return pi.index();
        }

        Rgb rgb = (Rgb) this;
        if (palette == null) {
            palette = Palette.SYSTEM_MAC_PALETTE;
        }

        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < palette.size(); i++) {
            int[] paletteRgb = palette.getRGB(i);
            int dr = rgb.r() - paletteRgb[0];
            int dg = rgb.g() - paletteRgb[1];
            int db = rgb.b() - paletteRgb[2];
            int distance = dr * dr + dg * dg + db * db;

            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    /**
     * Create a black color reference.
     */
    static Rgb black() {
        return new Rgb(0, 0, 0);
    }

    /**
     * Create a white color reference.
     */
    static Rgb white() {
        return new Rgb(255, 255, 255);
    }
}
