package com.libreshockwave.lookup;

import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptContextChunk;

import java.util.List;

/**
 * Provides script lookup functionality.
 * Encapsulates the logic for finding scripts by context ID and getting script types.
 */
public final class ScriptLookup {

    private final List<ScriptChunk> scripts;
    private final List<ScriptContextChunk> scriptContexts;
    private final List<CastMemberChunk> castMembers;

    public ScriptLookup(List<ScriptChunk> scripts, List<ScriptContextChunk> scriptContexts,
                        List<CastMemberChunk> castMembers) {
        this.scripts = scripts;
        this.scriptContexts = scriptContexts;
        this.castMembers = castMembers;
    }

    /**
     * Get a script by its context ID (the scriptId stored in cast members).
     * This uses the ScriptContextChunk (Lctx) to map scriptId to chunk ID.
     * @param scriptId The script ID from the cast member
     * @return The script chunk, or null if not found
     */
    public ScriptChunk getByContextId(int scriptId) {
        // scriptId from cast members is 1-based, Lctx entries are 0-based
        int index = scriptId - 1;

        // Search through all script contexts (there can be one per cast library)
        for (ScriptContextChunk ctx : scriptContexts) {
            if (index >= 0 && index < ctx.entries().size()) {
                var entry = ctx.entries().get(index);
                int chunkId = entry.id();
                if (chunkId > 0) {
                    for (ScriptChunk script : scripts) {
                        if (script.id() == chunkId) {
                            return script;
                        }
                    }
                }
            }
        }

        // Fallback: try direct match by ID
        for (ScriptChunk script : scripts) {
            if (script.id() == scriptId) {
                return script;
            }
        }

        return null;
    }

    /**
     * Get the reliable script type for a script by looking up its associated cast member.
     * The script type stored in the cast member's specificData is the authoritative source.
     * @param script The script chunk to get the type for
     * @return The script type from the cast member, or null if not found
     */
    public ScriptChunk.ScriptType getScriptType(ScriptChunk script) {
        if (script == null) return null;

        // Find the cast member that references this script
        // We need to find which Lctx index maps to this script's chunk ID,
        // then find the cast member with that scriptId
        for (int ctxIdx = 0; ctxIdx < scriptContexts.size(); ctxIdx++) {
            ScriptContextChunk ctx = scriptContexts.get(ctxIdx);
            for (int i = 0; i < ctx.entries().size(); i++) {
                if (ctx.entries().get(i).id() == script.id()) {
                    // Found the entry - scriptId is 1-based
                    int scriptId = i + 1;

                    // Find the cast member with this scriptId
                    for (CastMemberChunk member : castMembers) {
                        if (member.isScript() && member.scriptId() == scriptId) {
                            return member.getScriptType();
                        }
                    }
                }
            }
        }

        return null;
    }
}
