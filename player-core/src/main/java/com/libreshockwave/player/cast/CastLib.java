package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a loaded cast library.
 * Similar to dirplayer-rs player/cast_lib.rs.
 *
 * Cast libraries contain cast members (bitmaps, scripts, sounds, etc.)
 * and are lazily loaded from the DirectorFile when first accessed.
 */
public class CastLib {

    public enum State {
        NONE,
        LOADING,
        LOADED
    }

    private final int number;  // 1-based cast library number
    private String name;
    private String fileName;
    private State state = State.NONE;
    private int preloadMode = 0;

    // Members indexed by member number
    private final Map<Integer, CastMemberChunk> members = new HashMap<>();

    // Scripts indexed by member number
    private final Map<Integer, ScriptChunk> scripts = new HashMap<>();

    // Reference to the source file
    private final DirectorFile sourceFile;
    private final CastChunk castChunk;

    public CastLib(int number, DirectorFile file, CastChunk castChunk, CastListChunk.CastListEntry listEntry) {
        this.number = number;
        this.sourceFile = file;
        this.castChunk = castChunk;

        // Set name and fileName from cast list entry
        if (listEntry != null) {
            this.name = listEntry.name() != null ? listEntry.name() : "";
            this.fileName = listEntry.path() != null ? listEntry.path() : "";
        } else {
            this.name = "";
            this.fileName = "";
        }

        // Default name for internal cast
        if (this.name.isEmpty() && number == 1) {
            this.name = "Internal";
        }
    }

    /**
     * Load the cast library members from the DirectorFile.
     */
    public void load() {
        if (state == State.LOADED) {
            return;
        }

        state = State.LOADING;

        if (sourceFile == null || castChunk == null) {
            state = State.LOADED;
            return;
        }

        // Get minMember offset
        int minMember = getMinMember();

        // Load members from cast chunk
        for (int i = 0; i < castChunk.memberIds().size(); i++) {
            int chunkId = castChunk.memberIds().get(i);
            if (chunkId <= 0) {
                continue; // Empty slot
            }

            int memberNumber = i + minMember;

            // Find the cast member chunk with this ID
            for (CastMemberChunk member : sourceFile.getCastMembers()) {
                if (member.id() == chunkId) {
                    members.put(memberNumber, member);

                    // If it's a script member, also load the script
                    if (member.isScript() && member.scriptId() > 0) {
                        ScriptChunk script = sourceFile.getScriptByContextId(member.scriptId());
                        if (script != null) {
                            scripts.put(memberNumber, script);
                        }
                    }
                    break;
                }
            }
        }

        state = State.LOADED;
    }

    /**
     * Get the minimum member number (offset) for this cast.
     */
    private int getMinMember() {
        if (sourceFile == null) {
            return 1;
        }

        CastListChunk castList = sourceFile.getCastList();
        if (castList != null && number - 1 < castList.entries().size()) {
            int minMember = castList.entries().get(number - 1).minMember();
            return minMember > 0 ? minMember : 1;
        }

        if (sourceFile.getConfig() != null) {
            int minMember = sourceFile.getConfig().minMember();
            return minMember > 0 ? minMember : 1;
        }

        return 1;
    }

    // Accessors

    public int getNumber() {
        return number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public State getState() {
        return state;
    }

    public int getPreloadMode() {
        return preloadMode;
    }

    public void setPreloadMode(int preloadMode) {
        this.preloadMode = preloadMode;
    }

    public boolean isLoaded() {
        return state == State.LOADED;
    }

    /**
     * Get the number of members in this cast library.
     */
    public int getMemberCount() {
        if (!isLoaded()) {
            load();
        }
        return members.size();
    }

    /**
     * Find a member by number.
     */
    public CastMemberChunk findMemberByNumber(int memberNumber) {
        if (!isLoaded()) {
            load();
        }
        return members.get(memberNumber);
    }

    /**
     * Find a member by name.
     */
    public CastMemberChunk findMemberByName(String name) {
        if (!isLoaded()) {
            load();
        }

        for (CastMemberChunk member : members.values()) {
            if (member.name() != null && member.name().equalsIgnoreCase(name)) {
                return member;
            }
        }
        return null;
    }

    /**
     * Get the member number for a member found by name.
     */
    public int getMemberNumber(CastMemberChunk member) {
        if (!isLoaded()) {
            load();
        }

        for (Map.Entry<Integer, CastMemberChunk> entry : members.entrySet()) {
            if (entry.getValue() == member) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /**
     * Get a script for a member.
     */
    public ScriptChunk getScript(int memberNumber) {
        if (!isLoaded()) {
            load();
        }
        return scripts.get(memberNumber);
    }

    /**
     * Get a property value.
     */
    public Datum getProp(String propName) {
        String prop = propName.toLowerCase();

        return switch (prop) {
            case "number" -> Datum.of(number);
            case "name" -> Datum.of(name);
            case "filename" -> Datum.of(fileName);
            case "preloadmode" -> Datum.of(preloadMode);
            default -> {
                if (prop.contains("member")) {
                    yield Datum.of(getMemberCount());
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Set a property value.
     */
    public boolean setProp(String propName, Datum value) {
        String prop = propName.toLowerCase();

        switch (prop) {
            case "name" -> {
                this.name = value.toStr();
                return true;
            }
            case "filename" -> {
                this.fileName = value.toStr();
                // TODO: trigger reload if fileName changes
                return true;
            }
            case "preloadmode" -> {
                this.preloadMode = value.toInt();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public String toString() {
        return "CastLib{number=" + number + ", name='" + name + "', members=" + members.size() + "}";
    }
}
