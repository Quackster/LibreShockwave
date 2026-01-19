package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.vm.LingoVM;

/**
 * Bitmap data chunk (BITD).
 * Contains raw bitmap pixel data.
 */
public record BitmapChunk(
    DirectorFile file,
    int id,
    byte[] data,
    int width,
    int height,
    int bitDepth,
    int paletteId
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.BITD;
    }

    public static BitmapChunk read(DirectorFile file,BinaryReader reader, int id, int version) {
        // BITD chunk is raw pixel data - dimensions come from CASt chunk
        byte[] data = reader.readBytes(reader.bytesLeft());
        return new BitmapChunk(file, id, data, 0, 0, 0, 0);
    }

    public BitmapChunk withDimensions(DirectorFile file, int width, int height, int bitDepth, int paletteId) {
        return new BitmapChunk(file, id, data, width, height, bitDepth, paletteId);
    }
}
