package com.libreshockwave.cast;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Bitmap-specific cast member data.
 * Contains dimensions, bit depth, palette, and registration point.
 */
public record BitmapInfo(
    int width,
    int height,
    int regX,
    int regY,
    int bitDepth,
    int paletteId
) {

    public static BitmapInfo parse(byte[] data) {
        if (data == null || data.length < 20) {
            return new BitmapInfo(0, 0, 0, 0, 1, 0);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        reader.skip(2); // unknown bytes
        reader.skip(4); // unknown

        int height = reader.readU16();
        int width = reader.readU16();

        reader.skip(8); // unknown (4 x u16)

        int regY = reader.readI16();
        int regX = reader.readI16();

        reader.skip(1); // unknown

        int bitDepth = 1;
        int paletteId = 0;

        if (reader.bytesLeft() > 0) {
            bitDepth = reader.readU8();
            reader.skip(2); // unknown
            if (reader.bytesLeft() >= 2) {
                paletteId = reader.readI16() - 1;
            }
        }

        return new BitmapInfo(width, height, regX, regY, bitDepth, paletteId);
    }

    public int bytesPerPixel() {
        return switch (bitDepth) {
            case 1 -> 0; // 1-bit packed
            case 2 -> 0; // 2-bit packed
            case 4 -> 0; // 4-bit packed
            case 8 -> 1;
            case 16 -> 2;
            case 24 -> 3;
            case 32 -> 4;
            default -> 1;
        };
    }

    public boolean isPaletted() {
        return bitDepth <= 8;
    }
}
