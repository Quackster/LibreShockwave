package com.libreshockwave.bitmap;

/**
 * Utility for colorising bitmaps using Director's foreColor/backColor system.
 *
 * Director allows sprites to have foreground and background colors that are
 * interpolated across the image based on pixel intensity (for 32-bit) or
 * palette index (for indexed color formats).
 *
 * The algorithm:
 * - For 32-bit bitmaps: Uses grayscale intensity (average RGB) as interpolation factor
 *   - Dark pixels (intensity 0) become foreColor
 *   - Light pixels (intensity 255) become backColor
 *
 * - For indexed bitmaps (1-8 bit): Uses normalized palette index as interpolation factor
 *   - Index 0 becomes foreColor
 *   - Max index becomes backColor
 */
public class BitmapColorizer {

    /**
     * Colorise a bitmap using foreground and background colors.
     * Creates a new bitmap with the colorisation applied.
     *
     * @param source The source bitmap to colorise
     * @param foreColor The foreground color (applied to dark pixels/low indices)
     * @param backColor The background color (applied to light pixels/high indices)
     * @param palette The palette to use for resolving ColorRef.PaletteIndex values
     * @return A new colorised bitmap
     */
    public static Bitmap colorize(Bitmap source, ColorRef foreColor, ColorRef backColor, Palette palette) {
        if (source == null) {
            throw new IllegalArgumentException("Source bitmap cannot be null");
        }
        if (foreColor == null && backColor == null) {
            return source.copy();
        }

        // Resolve colors to RGB
        ColorRef.Rgb fg = foreColor != null ? foreColor.toRgb(palette) : null;
        ColorRef.Rgb bg = backColor != null ? backColor.toRgb(palette) : null;

        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap result = new Bitmap(width, height, source.getBitDepth());

        int bitDepth = source.getBitDepth();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = source.getPixel(x, y);
                int alpha = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;

                int newR, newG, newB;

                if (bitDepth == 32) {
                    // 32-bit: Use grayscale intensity for interpolation
                    int intensity = (r + g + b) / 3;
                    float t = intensity / 255.0f;

                    if (fg != null && bg != null) {
                        // Interpolate between foreColor and backColor
                        newR = Math.round((1 - t) * fg.r() + t * bg.r());
                        newG = Math.round((1 - t) * fg.g() + t * bg.g());
                        newB = Math.round((1 - t) * fg.b() + t * bg.b());
                    } else if (fg != null && intensity <= 1) {
                        // Only foreColor set: apply to near-black pixels
                        newR = fg.r();
                        newG = fg.g();
                        newB = fg.b();
                    } else {
                        // Keep original color
                        newR = r;
                        newG = g;
                        newB = b;
                    }
                } else {
                    // Indexed bitmap: determine the effective palette index from color
                    // For already-decoded bitmaps, we estimate the index from the grayscale value
                    // since the original index is not preserved after decoding
                    int maxIndex = (1 << bitDepth) - 1;
                    int intensity = (r + g + b) / 3;
                    int estimatedIndex = (intensity * maxIndex) / 255;
                    float t = (float) estimatedIndex / maxIndex;

                    if (fg != null && bg != null) {
                        // Interpolate between foreColor and backColor
                        newR = Math.round((1 - t) * fg.r() + t * bg.r());
                        newG = Math.round((1 - t) * fg.g() + t * bg.g());
                        newB = Math.round((1 - t) * fg.b() + t * bg.b());
                    } else if (fg != null && estimatedIndex == 0) {
                        // Only foreColor set: apply to index 0 pixels
                        newR = fg.r();
                        newG = fg.g();
                        newB = fg.b();
                    } else {
                        // Keep original color
                        newR = r;
                        newG = g;
                        newB = b;
                    }
                }

                result.setPixelRGBA(x, y, newR, newG, newB, alpha);
            }
        }

        return result;
    }

    /**
     * Colorise a bitmap with only foreground color (applied to darkest pixels only).
     *
     * @param source The source bitmap
     * @param foreColor The foreground color
     * @param palette The palette for color resolution
     * @return A new colorised bitmap
     */
    public static Bitmap colorize(Bitmap source, ColorRef foreColor, Palette palette) {
        return colorize(source, foreColor, null, palette);
    }

    /**
     * Colorise using packed RGB values.
     *
     * @param source The source bitmap
     * @param foreColorRgb Foreground color as 0xRRGGBB
     * @param backColorRgb Background color as 0xRRGGBB
     * @return A new colorised bitmap
     */
    public static Bitmap colorize(Bitmap source, int foreColorRgb, int backColorRgb) {
        return colorize(source,
            ColorRef.Rgb.fromPacked(foreColorRgb),
            ColorRef.Rgb.fromPacked(backColorRgb),
            null);
    }

    /**
     * Colorise using palette indices.
     *
     * @param source The source bitmap
     * @param foreColorIndex Foreground palette index
     * @param backColorIndex Background palette index
     * @param palette The palette for color resolution
     * @return A new colorised bitmap
     */
    public static Bitmap colorizeWithPaletteIndices(Bitmap source, int foreColorIndex, int backColorIndex, Palette palette) {
        return colorize(source,
            new ColorRef.PaletteIndex(foreColorIndex),
            new ColorRef.PaletteIndex(backColorIndex),
            palette);
    }

    /**
     * Check if colorisation is supported for the given ink mode and bit depth.
     * Based on Director's behavior, colorisation works with:
     * - Ink mode 0 (copy) for all bit depths
     * - Ink mode 8 (matte) for all bit depths
     * - Ink mode 9 (mask) for all bit depths
     *
     * @param bitDepth The bitmap bit depth
     * @param inkMode The ink mode
     * @return true if colorisation can be applied
     */
    public static boolean allowsColorization(int bitDepth, int inkMode) {
        return switch (inkMode) {
            case 0, 8, 9 -> bitDepth == 32 || bitDepth <= 8;
            default -> false;
        };
    }

    /**
     * Check if back color should be used for the given ink mode and bit depth.
     * Background color interpolation only applies with ink mode 0 (copy).
     *
     * @param bitDepth The bitmap bit depth
     * @param inkMode The ink mode
     * @return true if backColor should be used
     */
    public static boolean usesBackColor(int bitDepth, int inkMode) {
        return inkMode == 0 && (bitDepth == 32 || bitDepth <= 8);
    }

    /**
     * Colorise raw indexed bitmap data before decoding.
     * This is more accurate than colorising after decoding because it
     * preserves the original palette indices.
     *
     * @param indexedData Raw palette-indexed pixel data (one byte per pixel for 8-bit)
     * @param bitDepth Original bit depth (1, 2, 4, or 8)
     * @param foreColor Foreground color
     * @param backColor Background color
     * @param palette Source palette for color resolution
     * @return Array of ARGB colors corresponding to each input pixel
     */
    public static int[] colorizeIndexedData(byte[] indexedData, int bitDepth,
                                            ColorRef foreColor, ColorRef backColor, Palette palette) {
        if (indexedData == null || indexedData.length == 0) {
            return new int[0];
        }

        ColorRef.Rgb fg = foreColor != null ? foreColor.toRgb(palette) : null;
        ColorRef.Rgb bg = backColor != null ? backColor.toRgb(palette) : null;

        int maxIndex = (1 << bitDepth) - 1;
        int[] result;

        if (bitDepth == 8) {
            result = new int[indexedData.length];
            for (int i = 0; i < indexedData.length; i++) {
                int index = indexedData[i] & 0xFF;
                result[i] = colorizeIndex(index, maxIndex, fg, bg);
            }
        } else {
            // For bit depths < 8, unpack the indices
            int pixelsPerByte = 8 / bitDepth;
            result = new int[indexedData.length * pixelsPerByte];
            int pixelIdx = 0;

            for (byte b : indexedData) {
                for (int p = 0; p < pixelsPerByte; p++) {
                    int shift = (pixelsPerByte - 1 - p) * bitDepth;
                    int mask = (1 << bitDepth) - 1;
                    int index = (b >> shift) & mask;
                    result[pixelIdx++] = colorizeIndex(index, maxIndex, fg, bg);
                }
            }
        }

        return result;
    }

    /**
     * Colorise a single palette index.
     */
    private static int colorizeIndex(int index, int maxIndex, ColorRef.Rgb fg, ColorRef.Rgb bg) {
        float t = maxIndex > 0 ? (float) index / maxIndex : 0;

        int r, g, b;
        if (fg != null && bg != null) {
            r = Math.round((1 - t) * fg.r() + t * bg.r());
            g = Math.round((1 - t) * fg.g() + t * bg.g());
            b = Math.round((1 - t) * fg.b() + t * bg.b());
        } else if (fg != null && index == 0) {
            r = fg.r();
            g = fg.g();
            b = fg.b();
        } else {
            // Grayscale fallback
            int gray = Math.round(t * 255);
            r = g = b = gray;
        }

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
