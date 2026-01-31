package com.libreshockwave.tools.model;

import java.util.List;

/**
 * Represents a Director file node in the file tree.
 */
public record FileNode(String filePath, String fileName, List<CastMemberInfo> members) {
    @Override
    public String toString() {
        return fileName + " (" + members.size() + " members)";
    }
}
