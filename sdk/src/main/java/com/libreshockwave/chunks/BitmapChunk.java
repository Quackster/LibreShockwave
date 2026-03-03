package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.id.PaletteId;
import com.libreshockwave.io.BinaryReader;

/**
 * Bitmap data chunk (BITD).
 * Contains raw bitmap pixel data.
 */
public record BitmapChunk(
    DirectorFile file,
    ChunkId id,
    byte[] data,
    int width,
    int height,
    int bitDepth,
    PaletteId paletteId
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.BITD;
    }

    public static BitmapChunk read(DirectorFile file, BinaryReader reader, ChunkId id, int version) {
        // BITD chunk is raw pixel data - dimensions come from CASt chunk
        byte[] data = reader.readBytes(reader.bytesLeft());
        return new BitmapChunk(file, id, data, 0, 0, 0, new PaletteId(0));
    }

    public BitmapChunk withDimensions(DirectorFile file, int width, int height, int bitDepth, PaletteId paletteId) {
        return new BitmapChunk(file, id, data, width, height, bitDepth, paletteId);
    }
}
