package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Cast chunk (CAS*).
 * Contains array of cast member IDs for a cast library.
 */
public record CastChunk(
    int id,
    List<Integer> memberIds
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.CASp;
    }

    public int memberCount() {
        return memberIds.size();
    }

    public static CastChunk read(BinaryReader reader, int id, int version) {
        List<Integer> memberIds = new ArrayList<>();

        while (reader.bytesLeft() >= 4) {
            memberIds.add(reader.readI32());
        }

        return new CastChunk(id, memberIds);
    }
}
