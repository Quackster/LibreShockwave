package com.libreshockwave.player.bitmap;

import com.libreshockwave.player.Palette;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for Director bitmap data.
 * Handles RLE decompression and various bit depths.
 */
public class BitmapDecoder {

    /**
     * Decompress RLE-compressed bitmap data.
     * Director uses a simple RLE scheme:
     * - If byte < 0x80: copy (byte + 1) literal bytes
     * - If byte >= 0x80: repeat next byte (0x101 - byte) times
     */
    public static byte[] decompressRLE(byte[] compressed, int expectedSize) {
        // Guard against negative or excessive sizes
        if (expectedSize <= 0) {
            return new byte[0];
        }
        if (expectedSize > 100_000_000) { // 100MB max
            expectedSize = 100_000_000;
        }
        List<Byte> result = new ArrayList<>(Math.min(expectedSize, 65536));
        int pos = 0;

        while (pos < compressed.length && result.size() < expectedSize) {
            int rLen = compressed[pos++] & 0xFF;

            if (rLen < 0x80) {
                // Literal run: copy (rLen + 1) bytes
                int count = rLen + 1;
                for (int i = 0; i < count && pos < compressed.length && result.size() < expectedSize; i++) {
                    result.add(compressed[pos++]);
                }
            } else {
                // Repeat run: repeat next byte (0x101 - rLen) times
                int count = 0x101 - rLen;
                if (pos < compressed.length) {
                    byte val = compressed[pos++];
                    for (int i = 0; i < count && result.size() < expectedSize; i++) {
                        result.add(val);
                    }
                }
            }
        }

        byte[] output = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) {
            output[i] = result.get(i);
        }
        return output;
    }

    /**
     * Calculate the scan width (row stride) for a bitmap.
     * Rows are aligned to word boundaries.
     */
    public static int calculateScanWidth(int width, int bitDepth) {
        int bitsPerRow = width * bitDepth;
        // Align to 16-bit boundary (2 bytes)
        int bytesPerRow = (bitsPerRow + 15) / 16 * 2;
        return bytesPerRow;
    }

    /**
     * Decode a 1-bit bitmap (monochrome).
     */
    public static Bitmap decode1Bit(byte[] data, int width, int height, Palette palette) {
        Bitmap bitmap = new Bitmap(width, height, 1);
        int scanWidth = calculateScanWidth(width, 1);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * scanWidth;
            for (int x = 0; x < width; x++) {
                int byteIndex = rowOffset + x / 8;
                int bitIndex = 7 - (x % 8);

                if (byteIndex < data.length) {
                    int bit = (data[byteIndex] >> bitIndex) & 1;
                    int[] rgb = palette != null ? palette.getRGB(bit == 0 ? 255 : 0) : new int[]{bit * 255, bit * 255, bit * 255};
                    bitmap.setPixelRGB(x, y, rgb[0], rgb[1], rgb[2]);
                }
            }
        }
        return bitmap;
    }

    /**
     * Decode a 2-bit bitmap (4 colors).
     */
    public static Bitmap decode2Bit(byte[] data, int width, int height, Palette palette) {
        Bitmap bitmap = new Bitmap(width, height, 2);
        int scanWidth = calculateScanWidth(width, 2);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * scanWidth;
            for (int x = 0; x < width; x++) {
                int byteIndex = rowOffset + x / 4;
                int shift = 6 - (x % 4) * 2;

                if (byteIndex < data.length) {
                    int colorIndex = (data[byteIndex] >> shift) & 0x03;
                    // Map 2-bit to 8-bit palette range
                    int paletteIndex = colorIndex * 85; // 0, 85, 170, 255
                    int[] rgb = palette != null ? palette.getRGB(paletteIndex) : new int[]{paletteIndex, paletteIndex, paletteIndex};
                    bitmap.setPixelRGB(x, y, rgb[0], rgb[1], rgb[2]);
                }
            }
        }
        return bitmap;
    }

    /**
     * Decode a 4-bit bitmap (16 colors).
     */
    public static Bitmap decode4Bit(byte[] data, int width, int height, Palette palette) {
        Bitmap bitmap = new Bitmap(width, height, 4);
        int scanWidth = calculateScanWidth(width, 4);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * scanWidth;
            for (int x = 0; x < width; x++) {
                int byteIndex = rowOffset + x / 2;
                int shift = (x % 2 == 0) ? 4 : 0;

                if (byteIndex < data.length) {
                    int colorIndex = (data[byteIndex] >> shift) & 0x0F;
                    // Map 4-bit to 8-bit palette range
                    int paletteIndex = colorIndex * 17; // 0, 17, 34, ..., 255
                    int[] rgb = palette != null ? palette.getRGB(paletteIndex) : new int[]{paletteIndex, paletteIndex, paletteIndex};
                    bitmap.setPixelRGB(x, y, rgb[0], rgb[1], rgb[2]);
                }
            }
        }
        return bitmap;
    }

    /**
     * Decode an 8-bit bitmap (256 colors, palette-indexed).
     */
    public static Bitmap decode8Bit(byte[] data, int width, int height, Palette palette) {
        Bitmap bitmap = new Bitmap(width, height, 8);
        int scanWidth = calculateScanWidth(width, 8);

        if (palette == null) {
            palette = Palette.SYSTEM_MAC_PALETTE;
        }

        for (int y = 0; y < height; y++) {
            int rowOffset = y * scanWidth;
            for (int x = 0; x < width; x++) {
                int byteIndex = rowOffset + x;

                if (byteIndex < data.length) {
                    int colorIndex = data[byteIndex] & 0xFF;
                    int[] rgb = palette.getRGB(colorIndex);
                    bitmap.setPixelRGB(x, y, rgb[0], rgb[1], rgb[2]);
                }
            }
        }
        return bitmap;
    }

    /**
     * Decode a 16-bit bitmap (high color).
     * Format is typically 1-5-5-5 ARGB or 5-6-5 RGB.
     */
    public static Bitmap decode16Bit(byte[] data, int width, int height, boolean bigEndian) {
        Bitmap bitmap = new Bitmap(width, height, 16);
        int scanWidth = calculateScanWidth(width, 16);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * scanWidth;
            for (int x = 0; x < width; x++) {
                int byteIndex = rowOffset + x * 2;

                if (byteIndex + 1 < data.length) {
                    int pixel;
                    if (bigEndian) {
                        pixel = ((data[byteIndex] & 0xFF) << 8) | (data[byteIndex + 1] & 0xFF);
                    } else {
                        pixel = (data[byteIndex] & 0xFF) | ((data[byteIndex + 1] & 0xFF) << 8);
                    }

                    // Assume 1-5-5-5 format (common in Director)
                    int r = ((pixel >> 10) & 0x1F) * 255 / 31;
                    int g = ((pixel >> 5) & 0x1F) * 255 / 31;
                    int b = (pixel & 0x1F) * 255 / 31;

                    bitmap.setPixelRGB(x, y, r, g, b);
                }
            }
        }
        return bitmap;
    }

    /**
     * Decode a 32-bit bitmap (true color).
     * Director 4+ stores channels separately (all R, then all G, then all B, then all A).
     */
    public static Bitmap decode32Bit(byte[] data, int width, int height, boolean channelsSeparated) {
        Bitmap bitmap = new Bitmap(width, height, 32);

        if (channelsSeparated) {
            // D4+ format: channels are stored separately
            int pixelCount = width * height;
            int scanWidth = calculateScanWidth(width, 8); // Each channel is 8 bits
            int channelSize = scanWidth * height;

            for (int y = 0; y < height; y++) {
                int rowOffset = y * scanWidth;
                for (int x = 0; x < width; x++) {
                    int byteOffset = rowOffset + x;

                    int a = 255;
                    int r = 0, g = 0, b = 0;

                    // Alpha channel (may not always be present)
                    if (byteOffset < channelSize && byteOffset < data.length) {
                        a = data[byteOffset] & 0xFF;
                    }

                    // Red channel
                    int rOffset = channelSize + byteOffset;
                    if (rOffset < data.length) {
                        r = data[rOffset] & 0xFF;
                    }

                    // Green channel
                    int gOffset = channelSize * 2 + byteOffset;
                    if (gOffset < data.length) {
                        g = data[gOffset] & 0xFF;
                    }

                    // Blue channel
                    int bOffset = channelSize * 3 + byteOffset;
                    if (bOffset < data.length) {
                        b = data[bOffset] & 0xFF;
                    }

                    bitmap.setPixelRGBA(x, y, r, g, b, a);
                }
            }
        } else {
            // Standard ARGB interleaved format
            int scanWidth = calculateScanWidth(width, 32);

            for (int y = 0; y < height; y++) {
                int rowOffset = y * scanWidth;
                for (int x = 0; x < width; x++) {
                    int byteIndex = rowOffset + x * 4;

                    if (byteIndex + 3 < data.length) {
                        int a = data[byteIndex] & 0xFF;
                        int r = data[byteIndex + 1] & 0xFF;
                        int g = data[byteIndex + 2] & 0xFF;
                        int b = data[byteIndex + 3] & 0xFF;

                        bitmap.setPixelRGBA(x, y, r, g, b, a);
                    }
                }
            }
        }
        return bitmap;
    }

    /**
     * Decode bitmap data with automatic bit depth detection.
     *
     * @param data Raw (possibly compressed) bitmap data
     * @param width Bitmap width in pixels
     * @param height Bitmap height in pixels
     * @param bitDepth Bits per pixel (1, 2, 4, 8, 16, or 32)
     * @param palette Color palette for indexed formats
     * @param compressed Whether the data is RLE compressed
     * @param bigEndian Whether multi-byte values are big-endian
     * @param directorVersion Director version (affects 32-bit format)
     * @return Decoded Bitmap
     */
    public static Bitmap decode(byte[] data, int width, int height, int bitDepth,
                                 Palette palette, boolean compressed, boolean bigEndian,
                                 int directorVersion) {
        byte[] decompressed = data;

        if (compressed) {
            int scanWidth = calculateScanWidth(width, bitDepth);
            int expectedSize = scanWidth * height;

            // For 32-bit D4+, channels are stored separately
            if (bitDepth == 32 && directorVersion >= 400) {
                int channelScanWidth = calculateScanWidth(width, 8);
                expectedSize = channelScanWidth * height * 4; // 4 channels
            }

            decompressed = decompressRLE(data, expectedSize);
        }

        return switch (bitDepth) {
            case 1 -> decode1Bit(decompressed, width, height, palette);
            case 2 -> decode2Bit(decompressed, width, height, palette);
            case 4 -> decode4Bit(decompressed, width, height, palette);
            case 8 -> decode8Bit(decompressed, width, height, palette);
            case 16 -> decode16Bit(decompressed, width, height, bigEndian);
            case 32 -> decode32Bit(decompressed, width, height, directorVersion >= 400);
            default -> {
                // Default to 8-bit
                yield decode8Bit(decompressed, width, height, palette);
            }
        };
    }

    /**
     * Simple decode with defaults.
     */
    public static Bitmap decode(byte[] data, int width, int height, int bitDepth, Palette palette) {
        return decode(data, width, height, bitDepth, palette, true, true, 500);
    }
}
