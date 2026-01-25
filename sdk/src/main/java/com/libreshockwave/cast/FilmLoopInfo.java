package com.libreshockwave.cast;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Film loop-specific cast member data.
 */
public record FilmLoopInfo(
    int regX,
    int regY,
    int width,
    int height,
    boolean center,
    boolean crop,
    boolean sound,
    boolean loops
) implements Dimensioned {

    public static FilmLoopInfo parse(byte[] data) {
        if (data == null || data.length < 11) {
            return new FilmLoopInfo(0, 0, 0, 0, false, true, false, true);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        int regY = reader.readU16();
        int regX = reader.readU16();
        int height = reader.readU16();
        int width = reader.readU16();
        reader.skip(3); // unknown (3 bytes)
        int flags = reader.readU8();

        boolean center = (flags & 0b1) != 0;
        boolean crop = (flags & 0b10) == 0;
        boolean sound = (flags & 0b1000) != 0;
        boolean loops = (flags & 0b100000) == 0;

        return new FilmLoopInfo(regX, regY, width, height, center, crop, sound, loops);
    }
}
