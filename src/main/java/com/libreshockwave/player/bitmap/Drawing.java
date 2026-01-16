package com.libreshockwave.player.bitmap;

import com.libreshockwave.player.Palette.InkMode;

/**
 * Drawing operations for bitmaps including ink mode blending.
 * Implements Director's copyPixels with various ink effects.
 */
public class Drawing {

    /**
     * Copy pixels from source to destination with ink mode blending.
     *
     * @param dest Destination bitmap
     * @param src Source bitmap
     * @param destX Destination X coordinate
     * @param destY Destination Y coordinate
     * @param srcX Source X coordinate
     * @param srcY Source Y coordinate
     * @param width Width to copy
     * @param height Height to copy
     * @param ink Ink mode for blending
     * @param blend Blend amount (0-255, used for BLEND ink)
     */
    public static void copyPixels(Bitmap dest, Bitmap src,
                                   int destX, int destY,
                                   int srcX, int srcY,
                                   int width, int height,
                                   InkMode ink, int blend) {
        for (int y = 0; y < height; y++) {
            int sy = srcY + y;
            int dy = destY + y;

            if (sy < 0 || sy >= src.getHeight() || dy < 0 || dy >= dest.getHeight()) {
                continue;
            }

            for (int x = 0; x < width; x++) {
                int sx = srcX + x;
                int dx = destX + x;

                if (sx < 0 || sx >= src.getWidth() || dx < 0 || dx >= dest.getWidth()) {
                    continue;
                }

                int srcPixel = src.getPixel(sx, sy);
                int destPixel = dest.getPixel(dx, dy);

                int resultPixel = applyInk(srcPixel, destPixel, ink, blend);
                dest.setPixel(dx, dy, resultPixel);
            }
        }
    }

    /**
     * Copy entire source bitmap to destination.
     */
    public static void copyPixels(Bitmap dest, Bitmap src, int destX, int destY, InkMode ink, int blend) {
        copyPixels(dest, src, destX, destY, 0, 0, src.getWidth(), src.getHeight(), ink, blend);
    }

    /**
     * Apply ink mode to blend source and destination pixels.
     *
     * @param src Source pixel (ARGB)
     * @param dest Destination pixel (ARGB)
     * @param ink Ink mode
     * @param blend Blend factor (0-255)
     * @return Blended pixel (ARGB)
     */
    public static int applyInk(int src, int dest, InkMode ink, int blend) {
        int srcA = (src >> 24) & 0xFF;
        int srcR = (src >> 16) & 0xFF;
        int srcG = (src >> 8) & 0xFF;
        int srcB = src & 0xFF;

        int destA = (dest >> 24) & 0xFF;
        int destR = (dest >> 16) & 0xFF;
        int destG = (dest >> 8) & 0xFF;
        int destB = dest & 0xFF;

        int r, g, b, a;

        switch (ink) {
            case COPY:
                return src;

            case TRANSPARENT:
                // White (255,255,255) is transparent
                if (srcR == 255 && srcG == 255 && srcB == 255) {
                    return dest;
                }
                return src;

            case REVERSE:
                r = destR ^ srcR;
                g = destG ^ srcG;
                b = destB ^ srcB;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case GHOST:
                // Source appears ghosted over destination
                r = (srcR + destR) / 2;
                g = (srcG + destG) / 2;
                b = (srcB + destB) / 2;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case NOT_COPY:
                r = 255 - srcR;
                g = 255 - srcG;
                b = 255 - srcB;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case NOT_TRANSPARENT:
                // Black (0,0,0) is transparent
                if (srcR == 0 && srcG == 0 && srcB == 0) {
                    return dest;
                }
                r = 255 - srcR;
                g = 255 - srcG;
                b = 255 - srcB;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case NOT_REVERSE:
                r = destR ^ (255 - srcR);
                g = destG ^ (255 - srcG);
                b = destB ^ (255 - srcB);
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case NOT_GHOST:
                r = ((255 - srcR) + destR) / 2;
                g = ((255 - srcG) + destG) / 2;
                b = ((255 - srcB) + destB) / 2;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case MATTE:
                // Use alpha channel for transparency
                if (srcA == 0) {
                    return dest;
                }
                return alphaBlend(src, dest, srcA);

            case MASK:
                // Source acts as mask, revealing destination
                a = srcR; // Use red channel as alpha
                return alphaBlend(dest, src, a);

            case BLEND:
                // Blend based on blend parameter
                return alphaBlend(src, dest, blend);

            case ADD_PIN:
                r = Math.min(255, srcR + destR);
                g = Math.min(255, srcG + destG);
                b = Math.min(255, srcB + destB);
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case ADD:
                r = (srcR + destR) & 0xFF; // Wrap around
                g = (srcG + destG) & 0xFF;
                b = (srcB + destB) & 0xFF;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case SUBTRACT_PIN:
                r = Math.max(0, destR - srcR);
                g = Math.max(0, destG - srcG);
                b = Math.max(0, destB - srcB);
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case BACKGROUND_TRANSPARENT:
                // Background color (index 0, usually white) is transparent
                if (srcR >= 250 && srcG >= 250 && srcB >= 250) {
                    return dest;
                }
                return src;

            case LIGHTEST:
                r = Math.max(srcR, destR);
                g = Math.max(srcG, destG);
                b = Math.max(srcB, destB);
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case SUBTRACT:
                r = (destR - srcR) & 0xFF; // Wrap around
                g = (destG - srcG) & 0xFF;
                b = (destB - srcB) & 0xFF;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case DARKEST:
                r = Math.min(srcR, destR);
                g = Math.min(srcG, destG);
                b = Math.min(srcB, destB);
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case LIGHTEN:
                // Only affects if source is lighter
                r = srcR > destR ? srcR : destR;
                g = srcG > destG ? srcG : destG;
                b = srcB > destB ? srcB : destB;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            case DARKEN:
                // Only affects if source is darker
                r = srcR < destR ? srcR : destR;
                g = srcG < destG ? srcG : destG;
                b = srcB < destB ? srcB : destB;
                return 0xFF000000 | (r << 16) | (g << 8) | b;

            default:
                return src;
        }
    }

    /**
     * Alpha blend two pixels.
     */
    private static int alphaBlend(int fg, int bg, int alpha) {
        if (alpha == 0) return bg;
        if (alpha == 255) return fg;

        int fgR = (fg >> 16) & 0xFF;
        int fgG = (fg >> 8) & 0xFF;
        int fgB = fg & 0xFF;

        int bgR = (bg >> 16) & 0xFF;
        int bgG = (bg >> 8) & 0xFF;
        int bgB = bg & 0xFF;

        int invAlpha = 255 - alpha;

        int r = (fgR * alpha + bgR * invAlpha) / 255;
        int g = (fgG * alpha + bgG * invAlpha) / 255;
        int b = (fgB * alpha + bgB * invAlpha) / 255;

        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Draw a filled rectangle.
     */
    public static void fillRect(Bitmap dest, int x, int y, int width, int height, int color) {
        dest.fillRect(x, y, width, height, color);
    }

    /**
     * Draw a rectangle outline.
     */
    public static void drawRect(Bitmap dest, int x, int y, int width, int height, int color) {
        // Top
        for (int i = x; i < x + width; i++) {
            dest.setPixel(i, y, color);
        }
        // Bottom
        for (int i = x; i < x + width; i++) {
            dest.setPixel(i, y + height - 1, color);
        }
        // Left
        for (int i = y; i < y + height; i++) {
            dest.setPixel(x, i, color);
        }
        // Right
        for (int i = y; i < y + height; i++) {
            dest.setPixel(x + width - 1, i, color);
        }
    }

    /**
     * Draw a line using Bresenham's algorithm.
     */
    public static void drawLine(Bitmap dest, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            dest.setPixel(x0, y0, color);

            if (x0 == x1 && y0 == y1) break;

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * Draw a filled ellipse.
     */
    public static void fillEllipse(Bitmap dest, int cx, int cy, int rx, int ry, int color) {
        for (int y = -ry; y <= ry; y++) {
            for (int x = -rx; x <= rx; x++) {
                if ((x * x * ry * ry + y * y * rx * rx) <= (rx * rx * ry * ry)) {
                    dest.setPixel(cx + x, cy + y, color);
                }
            }
        }
    }

    /**
     * Draw an ellipse outline.
     */
    public static void drawEllipse(Bitmap dest, int cx, int cy, int rx, int ry, int color) {
        int x = 0;
        int y = ry;
        int rxSq = rx * rx;
        int rySq = ry * ry;
        int p = (int)(rySq - rxSq * ry + 0.25 * rxSq);

        // Region 1
        while (rySq * x < rxSq * y) {
            dest.setPixel(cx + x, cy + y, color);
            dest.setPixel(cx - x, cy + y, color);
            dest.setPixel(cx + x, cy - y, color);
            dest.setPixel(cx - x, cy - y, color);

            if (p < 0) {
                x++;
                p += 2 * rySq * x + rySq;
            } else {
                x++;
                y--;
                p += 2 * rySq * x - 2 * rxSq * y + rySq;
            }
        }

        // Region 2
        p = (int)(rySq * (x + 0.5) * (x + 0.5) + rxSq * (y - 1) * (y - 1) - rxSq * rySq);
        while (y >= 0) {
            dest.setPixel(cx + x, cy + y, color);
            dest.setPixel(cx - x, cy + y, color);
            dest.setPixel(cx + x, cy - y, color);
            dest.setPixel(cx - x, cy - y, color);

            if (p > 0) {
                y--;
                p -= 2 * rxSq * y + rxSq;
            } else {
                y--;
                x++;
                p += 2 * rySq * x - 2 * rxSq * y + rxSq;
            }
        }
    }
}
