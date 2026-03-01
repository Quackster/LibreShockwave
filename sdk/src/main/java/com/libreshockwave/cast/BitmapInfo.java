package com.libreshockwave.cast;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Bitmap-specific cast member data.
 * Contains dimensions, bit depth, palette, pitch, and registration point.
 */
public record BitmapInfo(
    int width,
    int height,
    int regX,
    int regY,
    int bitDepth,
    int paletteId,
    int pitch
) implements Dimensioned {

    /**
     * Parse BitmapInfo without version info (assumes D6+ format).
     */
    public static BitmapInfo parse(byte[] data) {
        return parse(data, 1200); // Default to D6+ parsing
    }

    /**
     * Version-aware BitmapInfo parsing.
     * D4/D5 (directorVersion < 1200) and D6+ (>= 1200) have different field layouts
     * but share the same byte positions for pitch, initialRect, regY, regX.
     */
    public static BitmapInfo parse(byte[] data, int directorVersion) {
        if (data == null || data.length < 10) {
            return new BitmapInfo(0, 0, 0, 0, 1, 0, 0);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        // Bytes 0-1: pitch (u16) — common to all versions
        int rawPitch = reader.readU16();

        // Bytes 2-9: initialRect (top, left, bottom, right as i16)
        int top = reader.bytesLeft() >= 2 ? reader.readI16() : 0;
        int left = reader.bytesLeft() >= 2 ? reader.readI16() : 0;
        int bottom = reader.bytesLeft() >= 2 ? reader.readI16() : 0;
        int right = reader.bytesLeft() >= 2 ? reader.readI16() : 0;
        int height = bottom - top;
        int width = right - left;

        int regX = 0;
        int regY = 0;
        int bitDepth = 1;
        int paletteId = 0;
        int pitch = 0;

        if (directorVersion < 1200) {
            // D4/D5 format
            // Bytes 10-17: boundingRect (skip)
            if (reader.bytesLeft() >= 8) reader.skip(8);

            // Bytes 18-19: regY, bytes 20-21: regX
            if (reader.bytesLeft() >= 2) regY = reader.readI16();
            if (reader.bytesLeft() >= 2) regX = reader.readI16();

            // Byte 22: padding, byte 23: bitsPerPixel
            if (reader.bytesLeft() >= 1) reader.skip(1);
            if (reader.bytesLeft() >= 1) {
                bitDepth = reader.readU8();

                // D5 (>= 1100): clutCastLib (skip)
                if (directorVersion >= 1100 && reader.bytesLeft() >= 2) {
                    reader.skip(2);
                }

                // clutId
                if (reader.bytesLeft() >= 2) {
                    int val = reader.readI16();
                    paletteId = val - 1;
                }
            }

            // D4/D5: pitch mask is 0x0FFF
            pitch = rawPitch & 0x0FFF;

            if (bitDepth == 0) bitDepth = 1;
        } else {
            // D6+ format
            // Bytes 10-17: alphaThreshold+padding, editVersion, scrollPoint
            if (reader.bytesLeft() >= 8) reader.skip(8);

            // Bytes 18-19: regY, bytes 20-21: regX
            if (reader.bytesLeft() >= 2) regY = reader.readI16();
            if (reader.bytesLeft() >= 2) regX = reader.readI16();

            // Byte 22: updateFlags
            if (reader.bytesLeft() >= 1) reader.skip(1); // updateFlags (not currently used)

            // D6+: color image flag is pitch & 0x8000
            if ((rawPitch & 0x8000) != 0) {
                pitch = rawPitch & 0x3FFF;

                // Byte 23: bitsPerPixel
                if (reader.bytesLeft() >= 1) {
                    bitDepth = reader.readU8();
                }

                // clutCastLib (skip)
                if (reader.bytesLeft() >= 2) reader.skip(2);

                // clutId
                if (reader.bytesLeft() >= 2) {
                    int val = reader.readI16();
                    paletteId = val - 1;
                }
            } else {
                // No color flag: 1-bit bitmap
                bitDepth = 1;
                pitch = rawPitch & 0x3FFF;
            }
        }

        // Convert reg point from canvas space to bitmap-local space
        regX -= left;
        regY -= top;

        return new BitmapInfo(width, height, regX, regY, bitDepth, paletteId, pitch);
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
