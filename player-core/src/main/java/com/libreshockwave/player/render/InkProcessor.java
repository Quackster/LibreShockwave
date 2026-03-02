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
}
