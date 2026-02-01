package com.libreshockwave.tools.scanning;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.format.ScriptFormatUtils;

import java.util.List;

/**
 * Utilities for finding chunks associated with cast members.
 */
public final class MemberResolver {

    private MemberResolver() {}

    /**
     * Finds the ScriptChunk associated with a cast member.
     */
    public static ScriptChunk findScriptForMember(DirectorFile dirFile, CastMemberChunk member) {
        // Scripts are linked via ScriptContextChunk
        // The cast member's scriptId points to an index in the context entries (1-based)
        int scriptId = member.scriptId();
        ScriptContextChunk context = dirFile.getScriptContext();

        if (context != null && scriptId > 0) {
            List<ScriptContextChunk.ScriptEntry> entries = context.entries();
            if (scriptId <= entries.size()) {
                int chunkId = entries.get(scriptId - 1).id();
                // Find the script chunk with this ID
                for (ScriptChunk script : dirFile.getScripts()) {
                    if (script.id() == chunkId) {
                        return script;
                    }
                }
            }
        }

        // Fallback: try to find by member ID directly (for some file formats)
        for (ScriptChunk script : dirFile.getScripts()) {
            if (script.id() == member.id()) {
                return script;
            }
        }

        // Also try via KeyTable
        var keyTable = dirFile.getKeyTable();
        if (keyTable != null) {
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString().trim();
                if (fourcc.equals("Lscr") || fourcc.equals("rcsL")) {
                    for (ScriptChunk script : dirFile.getScripts()) {
                        if (script.id() == entry.sectionId()) {
                            return script;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds the SoundChunk associated with a cast member.
     */
    public static SoundChunk findSoundForMember(DirectorFile dirFile, CastMemberChunk member) {
        var keyTable = dirFile.getKeyTable();
        if (keyTable == null) {
            return null;
        }

        for (var entry : keyTable.getEntriesForOwner(member.id())) {
            var chunk = dirFile.getChunk(entry.sectionId());
            if (chunk instanceof SoundChunk sc) {
                return sc;
            }
            // Also check for MediaChunk (ediM) and convert to SoundChunk
            if (chunk instanceof com.libreshockwave.chunks.MediaChunk mc) {
                return mc.toSoundChunk();
            }
        }

        return null;
    }

    /**
     * Finds the PaletteChunk associated with a cast member.
     */
    public static PaletteChunk findPaletteForMember(DirectorFile dirFile, CastMemberChunk member) {
        // Try to find via KeyTable
        var keyTable = dirFile.getKeyTable();
        if (keyTable != null) {
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString().trim();
                if (fourcc.equals("CLUT") || fourcc.equals("TULC")) {
                    var chunk = dirFile.getChunk(entry.sectionId(), PaletteChunk.class);
                    if (chunk.isPresent()) {
                        return chunk.get();
                    }
                }
            }
        }

        // Also try to match by palette ID in the palettes list
        for (PaletteChunk palette : dirFile.getPalettes()) {
            if (palette.id() == member.id()) {
                return palette;
            }
        }

        return null;
    }

    /**
     * Finds the TextChunk associated with a cast member.
     */
    public static TextChunk findTextForMember(DirectorFile dirFile, CastMemberChunk member) {
        var keyTable = dirFile.getKeyTable();
        if (keyTable != null) {
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString().trim();
                if (fourcc.equals("STXT") || fourcc.equals("TXTS")) {
                    var chunk = dirFile.getChunk(entry.sectionId(), TextChunk.class);
                    if (chunk.isPresent()) {
                        return chunk.get();
                    }
                }
            }

            // Also check for any TextChunk linked to this member
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                var chunk = dirFile.getChunk(entry.sectionId());
                if (chunk instanceof TextChunk tc) {
                    return tc;
                }
            }
        }

        return null;
    }

    /**
     * Gets a human-readable name for a script type.
     * @deprecated Use {@link ScriptFormatUtils#getScriptTypeName(ScriptChunk.ScriptType)} instead
     */
    @Deprecated
    public static String getScriptTypeName(ScriptChunk.ScriptType scriptType) {
        return ScriptFormatUtils.getScriptTypeName(scriptType);
    }
}
