package com.libreshockwave.lookup;

import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.*;

import java.util.List;
import java.util.function.Function;

/**
 * Resolves palette references to Palette objects.
 * Handles both built-in palettes (negative IDs) and custom cast member palettes (non-negative IDs).
 */
public final class PaletteResolver {

    private final List<CastChunk> casts;
    private final List<CastMemberChunk> castMembers;
    private final List<PaletteChunk> palettes;
    private final CastListChunk castList;
    private final ConfigChunk config;
    private final KeyTableChunk keyTable;
    private final Function<Integer, Chunk> chunkLookup;

    public PaletteResolver(List<CastChunk> casts, List<CastMemberChunk> castMembers,
                           List<PaletteChunk> palettes, CastListChunk castList,
                           ConfigChunk config, KeyTableChunk keyTable,
                           Function<Integer, Chunk> chunkLookup) {
        this.casts = casts;
        this.castMembers = castMembers;
        this.palettes = palettes;
        this.castList = castList;
        this.config = config;
        this.keyTable = keyTable;
        this.chunkLookup = chunkLookup;
    }

    /**
     * Resolve a palette by ID.
     * @param paletteId The palette ID from BitmapInfo
     * @return The resolved Palette, or System Mac palette as fallback
     */
    public Palette resolve(int paletteId) {
        // Negative IDs are built-in palettes
        if (paletteId < 0) {
            return Palette.getBuiltIn(paletteId);
        }

        // Non-negative IDs reference cast member palettes
        // Strategy 1: paletteId might be the member number - 1 (after BitmapInfo's -1 adjustment)
        int memberNumber = paletteId + 1;

        // Try to find the palette cast member by member number in cast arrays
        for (int castIdx = 0; castIdx < casts.size(); castIdx++) {
            CastChunk cast = casts.get(castIdx);

            // Get minMember for this cast library
            int minMember = getMinMember(castIdx);

            List<Integer> memberIds = cast.memberIds();
            // Member numbers use minMember offset, so index = memberNumber - minMember
            int index = memberNumber - minMember;
            if (index >= 0 && index < memberIds.size()) {
                int chunkId = memberIds.get(index);
                if (chunkId > 0) {
                    Palette resolved = resolveFromChunkId(chunkId);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
        }

        // Strategy 2: paletteId might be directly a chunk section ID for the CastMemberChunk
        Palette resolved = resolveFromChunkId(paletteId);
        if (resolved != null) {
            return resolved;
        }

        // Strategy 2b: paletteId might directly reference a PaletteChunk (CLUT) section ID
        for (PaletteChunk pc : palettes) {
            if (pc.id() == paletteId || pc.id() == paletteId + 1) {
                return new Palette(pc.colors(), "Custom Palette #" + pc.id());
            }
        }

        // Strategy 3: paletteId might be the 1-based index among palette members
        int paletteIndex = 0;
        for (CastMemberChunk member : castMembers) {
            if (member.memberType() == MemberType.PALETTE) {
                if (paletteIndex == paletteId) {
                    resolved = resolveFromChunkId(member.id());
                    if (resolved != null) {
                        return resolved;
                    }
                }
                paletteIndex++;
            }
        }

        // Strategy 4: Just return the first available palette if we have any
        for (PaletteChunk pc : palettes) {
            return new Palette(pc.colors(), "Custom Palette");
        }

        // Fallback to System Mac palette
        return Palette.getBuiltIn(Palette.SYSTEM_MAC);
    }

    /**
     * Get the minMember offset for a cast library.
     */
    private int getMinMember(int castIdx) {
        int minMember = 1;
        if (castList != null && castIdx < castList.entries().size()) {
            minMember = castList.entries().get(castIdx).minMember();
        } else if (config != null) {
            minMember = config.minMember();
        }
        if (minMember <= 0) minMember = 1;
        return minMember;
    }

    /**
     * Resolve a palette from a cast member chunk ID.
     * Finds the CLUT chunk owned by the cast member and builds a Palette from it.
     */
    private Palette resolveFromChunkId(int chunkId) {
        if (keyTable == null) {
            return null;
        }

        // Look for CLUT chunk owned by this cast member
        for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(chunkId)) {
            String fourcc = entry.fourccString();
            if (fourcc.equals("CLUT") || fourcc.equals("TULC")) {
                Chunk chunk = chunkLookup.apply(entry.sectionId());
                if (chunk instanceof PaletteChunk pc) {
                    return new Palette(pc.colors(), "Custom Palette");
                }
            }
        }

        return null;
    }
}
