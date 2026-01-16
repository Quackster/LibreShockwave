package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

/**
 * Palette/CLUT chunk.
 * Contains color lookup table data.
 */
public record PaletteChunk(
    int id,
    int[] colors
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.CLUT;
    }

    public int getColor(int index) {
        if (index >= 0 && index < colors.length) {
            return colors[index];
        }
        return 0;
    }

    public int colorCount() {
        return colors.length;
    }

    public static PaletteChunk read(BinaryReader reader, int id, int version) {
        int colorCount = reader.bytesLeft() / 6; // 6 bytes per color (RGB * 2)
        int[] colors = new int[colorCount];

        for (int i = 0; i < colorCount; i++) {
            int r = reader.readU8();
            reader.skip(1); // padding
            int g = reader.readU8();
            reader.skip(1); // padding
            int b = reader.readU8();
            reader.skip(1); // padding
            colors[i] = (r << 16) | (g << 8) | b;
        }

        return new PaletteChunk(id, colors);
    }
}
