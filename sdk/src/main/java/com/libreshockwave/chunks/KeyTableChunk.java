package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Key table chunk (KEY*).
 * Maps resource IDs to their associated chunks.
 * Provides bidirectional lookups:
 * - Owner to chunks: getEntriesForOwner(castId) returns all chunks owned by a cast member
 * - Chunk to owner: getOwnerCastId(sectionId) returns the cast member that owns a chunk
 */
public record KeyTableChunk(
    DirectorFile file,
    int id,
    List<KeyTableEntry> entries,
    Map<Integer, List<KeyTableEntry>> entriesByOwner,
    Map<Integer, Integer> ownerBySectionId
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

    /**
     * Get the cast member ID that owns a chunk by its section ID.
     * @param sectionId The chunk's section ID (resource ID)
     * @return The owning cast member's ID, or -1 if not found
     */
    public int getOwnerCastId(int sectionId) {
        return ownerBySectionId.getOrDefault(sectionId, -1);
    }

    /**
     * Get the KeyTableEntry for a chunk by its section ID.
     * @param sectionId The chunk's section ID (resource ID)
     * @return The KeyTableEntry, or null if not found
     */
    public KeyTableEntry getEntryBySectionId(int sectionId) {
        int ownerId = ownerBySectionId.get(sectionId);
        if (ownerId == 0) {
            // Check if it's actually in the map vs default
            if (!ownerBySectionId.containsKey(sectionId)) {
                return null;
            }
        }
        for (KeyTableEntry entry : entries) {
            if (entry.sectionId == sectionId) {
                return entry;
            }
        }
        return null;
    }

    public static KeyTableChunk read(DirectorFile vm, BinaryReader reader, int id, int version) {
        ByteOrder originalOrder = reader.getOrder();
        reader.setOrder(ByteOrder.LITTLE_ENDIAN);

        int headerSize = reader.readI16() & 0xFFFF;
        int entrySize = reader.readI16() & 0xFFFF;
        int totalCount = reader.readI32();
        int usedCount = reader.readI32();

        List<KeyTableEntry> entries = new ArrayList<>();
        Map<Integer, List<KeyTableEntry>> byOwner = new HashMap<>();
        Map<Integer, Integer> ownerBySection = new HashMap<>();

        for (int i = 0; i < usedCount; i++) {
            int sectionId = reader.readI32();
            int castId = reader.readI32();
            int fourcc = reader.readFourCC();

            KeyTableEntry entry = new KeyTableEntry(sectionId, castId, fourcc);
            entries.add(entry);

            byOwner.computeIfAbsent(castId, k -> new ArrayList<>()).add(entry);
            ownerBySection.put(sectionId, castId);
        }

        reader.setOrder(originalOrder);

        return new KeyTableChunk(vm, id, entries, byOwner, ownerBySection);
    }
}
