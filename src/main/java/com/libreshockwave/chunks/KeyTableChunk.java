package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Key table chunk (KEY*).
 * Maps resource IDs to their associated chunks.
 */
public record KeyTableChunk(
    int id,
    List<KeyTableEntry> entries,
    Map<Integer, List<KeyTableEntry>> entriesByOwner
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.KEYp;
    }

    public record KeyTableEntry(
        int sectionId,
        int castId,
        int fourcc
    ) {
        public String fourccString() {
            return BinaryReader.fourCCToString(fourcc);
        }
    }

    public List<KeyTableEntry> getEntriesForOwner(int ownerId) {
        return entriesByOwner.getOrDefault(ownerId, List.of());
    }

    public KeyTableEntry findEntry(int ownerId, int fourcc) {
        List<KeyTableEntry> ownerEntries = entriesByOwner.get(ownerId);
        if (ownerEntries == null) return null;

        for (KeyTableEntry entry : ownerEntries) {
            if (entry.fourcc == fourcc) {
                return entry;
            }
        }
        return null;
    }

    public static KeyTableChunk read(BinaryReader reader, int id, int version) {
        int headerSize = reader.readI16() & 0xFFFF;
        int entrySize = reader.readI16() & 0xFFFF;
        int totalCount = reader.readI32();
        int usedCount = reader.readI32();

        List<KeyTableEntry> entries = new ArrayList<>();
        Map<Integer, List<KeyTableEntry>> byOwner = new HashMap<>();

        for (int i = 0; i < usedCount; i++) {
            int sectionId = reader.readI32();
            int castId = reader.readI32();
            int fourcc = reader.readFourCC();

            KeyTableEntry entry = new KeyTableEntry(sectionId, castId, fourcc);
            entries.add(entry);

            byOwner.computeIfAbsent(castId, k -> new ArrayList<>()).add(entry);
        }

        return new KeyTableChunk(id, entries, byOwner);
    }
}
