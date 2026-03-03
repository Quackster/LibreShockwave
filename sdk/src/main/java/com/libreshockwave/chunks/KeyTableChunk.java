package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
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
    ChunkId id,
    List<KeyTableEntry> entries,
    Map<ChunkId, List<KeyTableEntry>> entriesByOwner,
    Map<ChunkId, ChunkId> ownerBySectionId
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.KEYp;
    }

    public record KeyTableEntry(
        ChunkId sectionId,
        ChunkId castId,
        int fourcc
    ) {
        public String fourccString() {
            return BinaryReader.fourCCToString(fourcc);
        }
    }

    public List<KeyTableEntry> getEntriesForOwner(ChunkId ownerId) {
        return entriesByOwner.getOrDefault(ownerId, List.of());
    }

    public KeyTableEntry findEntry(ChunkId ownerId, int fourcc) {
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
     * @return The owning cast member's ID, or null if not found
     */
    public ChunkId getOwnerCastId(ChunkId sectionId) {
        return ownerBySectionId.get(sectionId);
    }

    /**
     * Get the KeyTableEntry for a chunk by its section ID.
     * @param sectionId The chunk's section ID (resource ID)
     * @return The KeyTableEntry, or null if not found
     */
    public KeyTableEntry getEntryBySectionId(ChunkId sectionId) {
        ChunkId ownerId = ownerBySectionId.get(sectionId);
        if (ownerId == null) {
            return null;
        }
        for (KeyTableEntry entry : entries) {
            if (entry.sectionId.equals(sectionId)) {
                return entry;
            }
        }
        return null;
    }

    public static KeyTableChunk read(DirectorFile vm, BinaryReader reader, ChunkId id, int version) {
        ByteOrder originalOrder = reader.getOrder();
        reader.setOrder(ByteOrder.LITTLE_ENDIAN);

        int headerSize = reader.readI16() & 0xFFFF;
        int entrySize = reader.readI16() & 0xFFFF;
        int totalCount = reader.readI32();
        int usedCount = reader.readI32();

        List<KeyTableEntry> entries = new ArrayList<>();
        Map<ChunkId, List<KeyTableEntry>> byOwner = new HashMap<>();
        Map<ChunkId, ChunkId> ownerBySection = new HashMap<>();

        for (int i = 0; i < usedCount; i++) {
            // Key table entries may have negative sentinel values; clamp to 0
            ChunkId sectionId = new ChunkId(Math.max(0, reader.readI32()));
            ChunkId castId = new ChunkId(Math.max(0, reader.readI32()));
            // FourCC in key table is stored as a u32 in the file's byte order (little-endian).
            // In LE files, tags are byte-swapped (e.g., "ALFA" stored as "AFLA" bytes).
            // readI32() with LE byte order produces the correct integer for fourCCToString.
            int fourcc = reader.readI32();

            KeyTableEntry entry = new KeyTableEntry(sectionId, castId, fourcc);
            entries.add(entry);

            byOwner.computeIfAbsent(castId, k -> new ArrayList<>()).add(entry);
            ownerBySection.put(sectionId, castId);
        }

        reader.setOrder(originalOrder);

        return new KeyTableChunk(vm, id, entries, byOwner, ownerBySection);
    }
}
