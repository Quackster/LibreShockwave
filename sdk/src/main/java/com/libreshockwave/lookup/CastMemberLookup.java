package com.libreshockwave.lookup;

import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ConfigChunk;

import java.util.List;

/**
 * Provides cast member lookup functionality.
 * Encapsulates the logic for finding cast members by index or member number.
 */
public final class CastMemberLookup {

    private final List<CastChunk> casts;
    private final List<CastMemberChunk> castMembers;
    private final CastListChunk castList;
    private final ConfigChunk config;

    public CastMemberLookup(List<CastChunk> casts, List<CastMemberChunk> castMembers,
                            CastListChunk castList, ConfigChunk config) {
        this.casts = casts;
        this.castMembers = castMembers;
        this.castList = castList;
        this.config = config;
    }

    /**
     * Get a cast member by its score index (castLib, castMemberIndex).
     * Handles the minMember offset from the cast list.
     * @param castLib Cast library (0 for internal, 1+ for external)
     * @param castMemberIndex 0-based cast member index from score
     * @return The cast member, or null if not found
     */
    public CastMemberChunk getByIndex(int castLib, int castMemberIndex) {
        // Get the min member offset from the cast list or config
        int minMember = getMinMember(castLib);

        // Calculate the actual member ID considering the offset
        int adjustedMemberId = castMemberIndex + minMember;

        // Try to find by adjusted ID first
        for (CastMemberChunk member : castMembers) {
            if (member.id() == adjustedMemberId) {
                return member;
            }
        }

        // Try direct match with raw index
        for (CastMemberChunk member : castMembers) {
            if (member.id() == castMemberIndex) {
                return member;
            }
        }

        // Try +1 offset
        for (CastMemberChunk member : castMembers) {
            if (member.id() == castMemberIndex + 1) {
                return member;
            }
        }

        return null;
    }

    /**
     * Get a cast member by its member number (from score behavior references).
     * The member number is the slot position as seen in Director's cast window.
     * This uses the CASp chunk to map member numbers to chunk IDs.
     * @param castLib Cast library (1+)
     * @param memberNumber The member number as stored in the score
     * @return The cast member, or null if not found
     */
    public CastMemberChunk getByNumber(int castLib, int memberNumber) {
        // Get the cast library
        int libIndex = Math.max(0, castLib - 1);
        if (libIndex >= casts.size()) {
            return null;
        }

        CastChunk cast = casts.get(libIndex);
        if (cast == null) {
            return null;
        }

        // Get minMember offset
        int minMember = getMinMember(castLib);

        // Calculate the index into the cast's member ID array
        int arrayIndex = memberNumber - minMember;
        if (arrayIndex < 0 || arrayIndex >= cast.memberIds().size()) {
            return null;
        }

        // Get the chunk ID for this member slot
        int chunkId = cast.memberIds().get(arrayIndex);
        if (chunkId <= 0) {
            return null;  // Empty slot
        }

        // Find the cast member chunk with this ID
        for (CastMemberChunk member : castMembers) {
            if (member.id() == chunkId) {
                return member;
            }
        }

        return null;
    }

    /**
     * Get the minMember offset for a cast library.
     */
    private int getMinMember(int castLib) {
        int minMember = 1;
        if (castList != null && !castList.entries().isEmpty()) {
            int libIndex = Math.max(0, castLib - 1);
            if (libIndex < castList.entries().size()) {
                minMember = castList.entries().get(libIndex).minMember();
            }
        } else if (config != null) {
            // For .cct files without MCsL, use config's minMember
            minMember = config.minMember();
        }
        if (minMember <= 0) minMember = 1;
        return minMember;
    }
}
