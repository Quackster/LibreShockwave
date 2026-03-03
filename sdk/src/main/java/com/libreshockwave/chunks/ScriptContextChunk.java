package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Script context chunk (Lctx/LctX).
 * Contains information about scripts and their handlers.
 */
public record ScriptContextChunk(
    DirectorFile file,
    ChunkId id,
    int unknown1,
    int unknown2,
    int entryCount,
    ChunkId lnamSectionId,
    int validCount,
    int flags,
    int freePtr,
    List<ScriptEntry> entries
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.Lctx;
    }

    public record ScriptEntry(
        int unknown,
        ChunkId id,
        int flags
    ) {}

    public static ScriptContextChunk read(DirectorFile file, BinaryReader reader, ChunkId id, int version) {
        // Lingo scripts are ALWAYS big endian regardless of file byte order
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        int unknown1 = reader.readI32();
        int unknown2 = reader.readI32();
        int entryCount = reader.readI32();
        int entryCount2 = reader.readI32();
        int entriesOffset = reader.readU16();
        reader.skip(2); // unknown2
        reader.skip(4); // unknown3
        reader.skip(4); // unknown4
        reader.skip(4); // unknown5
        ChunkId lnamSectionId = new ChunkId(Math.max(0, reader.readI32()));
        int validCount = reader.readU16();
        int flags = reader.readU16();
        int freePtr = reader.readI16();

        List<ScriptEntry> entries = new ArrayList<>();

        if (entriesOffset > 0 && entriesOffset < reader.length()) {
            reader.setPosition(entriesOffset);
            for (int i = 0; i < entryCount; i++) {
                int entryUnknown = reader.readI32();
                // Lctx entries use negative IDs (-1) for free/empty slots; clamp to 0
                ChunkId entryId = new ChunkId(Math.max(0, reader.readI32()));
                int entryFlags = reader.readU16();
                int entryLink = reader.readI16();

                entries.add(new ScriptEntry(entryUnknown, entryId, entryFlags));
            }
        }

        return new ScriptContextChunk(
            file,
            id,
            unknown1,
            unknown2,
            entryCount,
            lnamSectionId,
            validCount,
            flags,
            freePtr,
            entries
        );
    }
}
