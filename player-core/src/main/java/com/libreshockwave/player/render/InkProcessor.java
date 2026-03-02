package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Processes Director ink effects on bitmaps (matte, background transparent, etc.).
 * Operates on {@link Bitmap} (int[] ARGB) — no Swing/AWT dependency.
 */
public final class InkProcessor {

    private InkProcessor() {}

    /**
     * Returns true if the given ink mode requires per-pixel transparency processing.
     */
    public static boolean shouldProcessInk(int ink) {
        return ink == 7 || ink == 8 || ink == 33 || ink == 35
            || ink == 36 || ink == 40 || ink == 41;
    }

    /**
     * Apply ink-based transparency to a bitmap.
     *
     * @param src       Source bitmap (ARGB pixels)
     * @param ink       Director ink mode
     * @param backColor Sprite backColor property
     * @param useAlpha  Whether the bitmap has native alpha channel (from BitmapInfo updateFlags bit 4)
     * @param palette   Resolved palette (may be null for non-paletted bitmaps)
     * @return A new bitmap with ink applied, or {@code src} unchanged if no processing needed
     */
    public static Bitmap applyInk(Bitmap src, int ink, int backColor,
                                   boolean useAlpha, Palette palette) {
        if (src == null || src.getWidth() == 0 || src.getHeight() == 0) {
            return src;
        }

        if (ink == 8) {
            // Matte ink: flood-fill from edges
            int matteColor = resolveMatteColor(src, ink, backColor, useAlpha, palette);
            if (matteColor < 0) {
                return src; // 32-bit with useAlpha — skip processing
            }
            return applyMatte(src, matteColor);
        } else if (ink == 7 || ink == 33 || ink == 35 || ink == 36
                || ink == 40 || ink == 41) {
            // Background transparent / not-ghost / etc: color-key
            int bgColor = resolveBackColor(src, ink, backColor, useAlpha, palette);
            if (bgColor < 0) {
                return src; // 32-bit with useAlpha — skip processing
            }
            return applyBackgroundTransparent(src, bgColor);
        }

        return src;
    }

    /**
     * Resolve the matte color for ink 8 (Matte).
     * Returns -1 if the bitmap has native alpha and should skip processing.
     */
    static int resolveMatteColor(Bitmap src, int ink, int backColor,
                                  boolean useAlpha, Palette palette) {
        int bitDepth = src.getBitDepth();

        // 32-bit with native alpha: skip matte entirely
        if (bitDepth == 32 && useAlpha) {
            return -1;
        }

        // Paletted (bitDepth <= 8): use palette index 0
        if (bitDepth <= 8 && palette != null) {
            return palette.getColor(0) & 0xFFFFFF;
        }

        // 32-bit ink 8 without alpha: top-left corner pixel
        if (bitDepth == 32) {
            return src.getPixel(0, 0) & 0xFFFFFF;
        }

        // 16-bit or other: white
        return 0xFFFFFF;
    }

    /**
     * Resolve the background color for ink 36/7/33/35/40/41.
     * Returns -1 if the bitmap has native alpha and should skip processing.
     */
    static int resolveBackColor(Bitmap src, int ink, int backColor,
                                 boolean useAlpha, Palette palette) {
        int bitDepth = src.getBitDepth();

        // 32-bit with native alpha: skip processing
        if (bitDepth == 32 && useAlpha) {
            return -1;
        }

        // Packed RGB value
        if (backColor > 255) {
            return backColor & 0xFFFFFF;
        }

        // 32-bit without alpha and ink is not Copy: force white
        if (bitDepth == 32 && !useAlpha && ink != 0) {
            return 0xFFFFFF;
        }

        // Director palette index: 0 = white (255), 255 = black (0)
        int gray = 255 - backColor;
        return (gray << 16) | (gray << 8) | gray;
    }

    /**
     * Apply Background Transparent ink: pixels matching bgColorRGB become fully transparent.
     */
    static Bitmap applyBackgroundTransparent(Bitmap src, int bgColorRGB) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] srcPixels = src.getPixels();
        int[] result = new int[w * h];

        for (int i = 0; i < srcPixels.length; i++) {
            int rgb = srcPixels[i] & 0xFFFFFF;
            if (rgb == bgColorRGB) {
                result[i] = 0x00000000; // Fully transparent
            } else {
                result[i] = srcPixels[i] | 0xFF000000; // Fully opaque
            }
        }

        return new Bitmap(w, h, src.getBitDepth(), result);
    }

    /**
     * Apply Matte ink (8): BFS flood-fill from image edges to remove border-connected
     * pixels matching matteColorRGB. Interior pixels of the same color are preserved.
     */
    static Bitmap applyMatte(Bitmap src, int matteColorRGB) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getPixels();
        boolean[] transparent = new boolean[w * h];
        Queue<Integer> queue = new ArrayDeque<>();

        // Seed border pixels matching matte color
        for (int x = 0; x < w; x++) {
            seedMatte(pixels, transparent, queue, x, 0, w, matteColorRGB);
            seedMatte(pixels, transparent, queue, x, h - 1, w, matteColorRGB);
        }
        for (int y = 1; y < h - 1; y++) {
            seedMatte(pixels, transparent, queue, 0, y, w, matteColorRGB);
            seedMatte(pixels, transparent, queue, w - 1, y, w, matteColorRGB);
        }

        // Flood-fill from seeds
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int px = idx % w;
            int py = idx / w;
            if (px > 0)     seedMatte(pixels, transparent, queue, px - 1, py, w, matteColorRGB);
            if (px < w - 1) seedMatte(pixels, transparent, queue, px + 1, py, w, matteColorRGB);
            if (py > 0)     seedMatte(pixels, transparent, queue, px, py - 1, w, matteColorRGB);
            if (py < h - 1) seedMatte(pixels, transparent, queue, px, py + 1, w, matteColorRGB);
        }

        // Build result
        int[] result = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            if (transparent[i]) {
                result[i] = 0x00000000;
            } else {
                result[i] = pixels[i] | 0xFF000000;
            }
        }

        return new Bitmap(w, h, src.getBitDepth(), result);
    }

    private static void seedMatte(int[] pixels, boolean[] transparent, Queue<Integer> queue,
                                   int x, int y, int w, int matteRgb) {
        int idx = y * w + x;
        if (!transparent[idx] && (pixels[idx] & 0xFFFFFF) == matteRgb) {
            transparent[idx] = true;
            queue.add(idx);
        }
    }

    /**
     * Apply Director's sprite-level foreColor/backColor colorization.
     * <p>
     * In Director, bitmap sprites with Copy (0) or Matte (8) ink have their pixels
     * remapped based on the sprite's foreColor and backColor properties. This is how
     * the window system creates dark backgrounds from white bitmap buffers:
     * <ul>
     *   <li>White pixels (grayscale 255) → foreColor</li>
     *   <li>Black pixels (grayscale 0) → backColor</li>
     *   <li>Gray pixels → interpolated between backColor and foreColor</li>
     * </ul>
     * This mimics Director's paletted bitmap behavior where palette index 0 (white)
     * maps to foreColor and index 255 (black) maps to backColor.
     *
     * @param src       Source bitmap (ARGB pixels)
     * @param foreColor Sprite foreColor as packed RGB (e.g., 0x000000 for black)
     * @param backColor Sprite backColor as packed RGB (e.g., 0xFFFFFF for white)
     * @return A new bitmap with colorization applied
     */
    public static Bitmap applyForeColorRemap(Bitmap src, int foreColor, int backColor) {
        if (src == null || src.getWidth() == 0 || src.getHeight() == 0) {
            return src;
        }

        // Skip if foreColor=BLACK and backColor=WHITE (identity remap for most images)
        // But NOT for paletted-style bitmaps where white→foreColor is important.
        // We always apply to ensure window system bitmaps get correct colors.

        int fr = (foreColor >> 16) & 0xFF;
        int fg = (foreColor >> 8) & 0xFF;
        int fb = foreColor & 0xFF;
        int br = (backColor >> 16) & 0xFF;
        int bg = (backColor >> 8) & 0xFF;
        int bb = backColor & 0xFF;

        int w = src.getWidth();
        int h = src.getHeight();
        int[] srcPixels = src.getPixels();
        int[] result = new int[w * h];

        for (int i = 0; i < srcPixels.length; i++) {
            int alpha = (srcPixels[i] >>> 24);
            if (alpha == 0) {
                result[i] = 0;
                continue;
            }

            int r = (srcPixels[i] >> 16) & 0xFF;
            int g = (srcPixels[i] >> 8) & 0xFF;
            int b = srcPixels[i] & 0xFF;

            // Grayscale intensity: 0=black, 255=white
            int gray = (r + g + b) / 3;

            // Director's palette remap: white (gray=255) → foreColor, black (gray=0) → backColor
            // t = gray/255: 0=black→backColor, 1=white→foreColor
            float t = gray / 255.0f;
            int nr = (int) (t * fr + (1 - t) * br + 0.5f);
            int ng = (int) (t * fg + (1 - t) * bg + 0.5f);
            int nb = (int) (t * fb + (1 - t) * bb + 0.5f);

            result[i] = (alpha << 24) | (nr << 16) | (ng << 8) | nb;
        }

        return new Bitmap(w, h, src.getBitDepth(), result);
    }

    /**
     * Returns true if the given ink mode supports sprite-level foreColor/backColor colorization.
     * Director applies colorization for Copy (0) and Matte (8/9) inks only.
     */
    public static boolean allowsColorize(int ink) {
        return ink == 0 || ink == 8 || ink == 9;
    }
}
