package com.libreshockwave.tools.model;

import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;

/**
 * Holds display information about a cast member.
 */
public record CastMemberInfo(
        int memberNum,
        String name,
        CastMemberChunk member,
        MemberType memberType,
        String details
) {
}
