package com.libreshockwave.player.bitmap;

import java.awt.image.BufferedImage;

/**
 * Represents a decoded bitmap with RGBA pixel data.
 * This is the final decoded form ready for rendering.
 */
public class Bitmap {

    private final int width;
    private final int height;
    private final int[] pixels; // ARGB format (0xAARRGGBB)
    private final int bitDepth;

    public Bitmap(int width, int height, int bitDepth) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.pixels = new int[width * height];
    }

    public Bitmap(int width, int height, int bitDepth, int[] pixels) {
        this.width = width;
        this.height = height;
        this.bitDepth = bitDepth;
        this.pixels = pixels;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitDepth() {
        return bitDepth;
    }

    public int[] getPixels() {
        return pixels;
    }

    /**
     * Get pixel at (x, y) in ARGB format.
     */
    public int getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    /**
     * Set pixel at (x, y) in ARGB format.
     */
    public void setPixel(int x, int y, int argb) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            pixels[y * width + x] = argb;
        }
    }

    /**
     * Set pixel from RGB components (alpha = 255).
     */
    public void setPixelRGB(int x, int y, int r, int g, int b) {
        setPixel(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
    }

    /**
     * Set pixel from RGBA components.
     */
    public void setPixelRGBA(int x, int y, int r, int g, int b, int a) {
        setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }

    /**
     * Fill the entire bitmap with a single color.
     */
    public void fill(int argb) {
        java.util.Arrays.fill(pixels, argb);
    }

    /**
     * Fill a rectangular region.
     */
    public void fillRect(int x, int y, int w, int h, int argb) {
        int x2 = Math.min(x + w, width);
        int y2 = Math.min(y + h, height);
        x = Math.max(0, x);
        y = Math.max(0, y);

        for (int py = y; py < y2; py++) {
            for (int px = x; px < x2; px++) {
                pixels[py * width + px] = argb;
            }
        }
    }

    /**
     * Convert to a Java BufferedImage for export/display.
     */
    public BufferedImage toBufferedImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /**
     * Create a copy of this bitmap.
     */
    public Bitmap copy() {
        int[] pixelsCopy = new int[pixels.length];
        System.arraycopy(pixels, 0, pixelsCopy, 0, pixels.length);
        return new Bitmap(width, height, bitDepth, pixelsCopy);
    }

    /**
     * Extract a sub-region as a new bitmap.
     */
    public Bitmap getRegion(int x, int y, int w, int h) {
        Bitmap result = new Bitmap(w, h, bitDepth);
        for (int dy = 0; dy < h; dy++) {
            int srcY = y + dy;
            if (srcY < 0 || srcY >= height) continue;
            for (int dx = 0; dx < w; dx++) {
                int srcX = x + dx;
                if (srcX < 0 || srcX >= width) continue;
                result.pixels[dy * w + dx] = pixels[srcY * width + srcX];
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "Bitmap[" + width + "x" + height + ", " + bitDepth + "-bit]";
    }
}
