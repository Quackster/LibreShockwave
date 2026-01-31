package com.libreshockwave.tools.model;

import com.libreshockwave.cast.MemberType;

/**
 * Data for a member node in the tree view.
 */
public record MemberNodeData(String filePath, CastMemberInfo memberInfo) {
    @Override
    public String toString() {
        String idPrefix = "#" + memberInfo.memberNum() + " ";

        // For scripts, show the script type from details instead of generic "script"
        if (memberInfo.memberType() == MemberType.SCRIPT && !memberInfo.details().isEmpty()) {
            // Details format: "Movie Script [handler1, handler2]" or just "Movie Script"
            return idPrefix + memberInfo.name() + " - " + memberInfo.details();
        }

        String base = idPrefix + memberInfo.name() + " [" + memberInfo.memberType().getName() + "]";
        if (!memberInfo.details().isEmpty()) {
            return base + " (" + memberInfo.details() + ")";
        }
        return base;
    }
}
