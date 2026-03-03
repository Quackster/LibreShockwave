package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;

/**
 * Raw chunk containing unparsed binary data.
 * Used for chunks that haven't been implemented yet.
 */
public record RawChunk(
    DirectorFile file,
    ChunkId id,
    ChunkType type,
    byte[] data
) implements Chunk {

    public int length() {
        return data.length;
    }
}
