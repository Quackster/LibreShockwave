package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;

import java.util.Collection;
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
        FETCHING,  // External cast fetch in progress
        LOADING,
        LOADED
    }

    private final int number;  // 1-based cast library number
    private String name;
    private String fileName;
    private State state = State.NONE;
    private int preloadMode = 0;
    private Datum selection = Datum.list(); // Selected members as [[start, end], ...]

    // Raw member chunks indexed by member number
    private final Map<Integer, CastMemberChunk> memberChunks = new HashMap<>();

    // Loaded CastMember objects indexed by member number (lazy)
    private final Map<Integer, CastMember> members = new HashMap<>();

    // Scripts indexed by member number
    private final Map<Integer, ScriptChunk> scripts = new HashMap<>();

    // Total slot count (including empty slots) - for "the number of castMembers"
    private int totalSlotCount = 0;

    // Reference to the source file
    private DirectorFile sourceFile;
    private final CastChunk castChunk;

    public CastLib(int number, CastChunk castChunk, CastListChunk.CastListEntry listEntry) {
        this.number = number;
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
     * Set the source file for this cast library.
     */
    public void setSourceFile(DirectorFile file) {
        this.sourceFile = file;
    }

    /**
     * Check if this is an external cast (has a fileName).
     */
    public boolean isExternal() {
        return fileName != null && !fileName.isEmpty();
    }

    /**
     * Check if the external cast data has been fetched (via preloadNetThing).
     */
    public boolean isFetched() {
        return sourceFile != null || !isExternal();
    }

    /**
     * Load the cast library members from the DirectorFile.
     * For external casts, this only works if the cast has been fetched first
     * via preloadNetThing() or downloadNetThing().
     */
    public void load() {
        if (state == State.LOADED) {
            return;
        }

        state = State.LOADING;

        // External casts must be fetched first via preloadNetThing
        // Don't auto-load them here
        if (sourceFile == null) {
            if (isExternal()) {
                // External cast not yet fetched - stay in LOADING state but don't block
                // It will be loaded when preloadNetThing completes
                state = State.NONE;
                return;
            }
            state = State.LOADED;
            return;
        }

        if (castChunk == null && sourceFile != null) {
            // For external casts, use the first cast from the loaded file
            if (!sourceFile.getCasts().isEmpty()) {
                loadFromExternalFile();
            }
            state = State.LOADED;
            return;
        }

        if (castChunk == null) {
            state = State.LOADED;
            return;
        }

        // Get minMember offset
        int minMember = getMinMember();

        // Track total slot count (including empty slots) for "the number of castMembers"
        totalSlotCount = castChunk.memberIds().size();

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
                    memberChunks.put(memberNumber, member);

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

    /**
     * Get raw member chunks map for diagnostic access.
     */
    public Map<Integer, CastMemberChunk> getMemberChunks() {
        if (!isLoaded()) {
            load();
        }
        return memberChunks;
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

    public boolean isFetching() {
        return state == State.FETCHING;
    }

    /**
     * Mark this external cast as being fetched.
     * Prevents duplicate fetch requests.
     */
    public void markFetching() {
        if (state == State.NONE) {
            state = State.FETCHING;
        }
    }

    /**
     * Get the total number of member slots in this cast library (including empty slots).
     * This matches Director's "the number of castMembers of castLib N" which returns
     * the total slot count, not just the non-empty member count.
     */
    public int getMemberCount() {
        if (!isLoaded()) {
            load();
        }
        // Return total slot count (including empties) for correct iteration in preIndexMembers.
        // If totalSlotCount wasn't set (e.g., empty/unloaded cast), fall back to memberChunks size.
        return totalSlotCount > 0 ? totalSlotCount : memberChunks.size();
    }

    /**
     * Find a member chunk by number (raw chunk, no lazy loading).
     */
    public CastMemberChunk findMemberByNumber(int memberNumber) {
        if (!isLoaded()) {
            load();
        }
        return memberChunks.get(memberNumber);
    }

    /**
     * Get or create a CastMember object with lazy loading of media data.
     */
    public CastMember getMember(int memberNumber) {
        if (!isLoaded()) {
            load();
        }

        // Check if already created
        CastMember member = members.get(memberNumber);
        if (member != null) {
            return member;
        }

        // Create from chunk if exists
        CastMemberChunk chunk = memberChunks.get(memberNumber);
        if (chunk == null) {
            return null;
        }

        // Create and cache the CastMember
        member = new CastMember(number, memberNumber, chunk, sourceFile);
        members.put(memberNumber, member);
        return member;
    }

    /**
     * Find a member chunk by name.
     */
    public CastMemberChunk findMemberByName(String name) {
        if (!isLoaded()) {
            load();
        }

        for (CastMemberChunk member : memberChunks.values()) {
            if (member.name() != null && member.name().equalsIgnoreCase(name)) {
                return member;
            }
        }
        return null;
    }

    /**
     * Get or create a CastMember by name with lazy loading.
     * Searches both file-loaded members (memberChunks) and dynamic members.
     */
    public CastMember getMemberByName(String name) {
        if (!isLoaded()) {
            load();
        }

        // Search file-loaded members first
        for (Map.Entry<Integer, CastMemberChunk> entry : memberChunks.entrySet()) {
            if (entry.getValue().name() != null && entry.getValue().name().equalsIgnoreCase(name)) {
                return getMember(entry.getKey());
            }
        }

        // Search dynamic members (created at runtime via new(#field, castLib))
        for (CastMember member : members.values()) {
            if (member.getName() != null && member.getName().equalsIgnoreCase(name)) {
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

        for (Map.Entry<Integer, CastMemberChunk> entry : memberChunks.entrySet()) {
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
     * Get the DirectorFile that contains this cast's data.
     */
    public DirectorFile getSourceFile() {
        return sourceFile;
    }

    /**
     * Get all scripts in this cast library.
     * Returns the scripts from the sourceFile if available.
     */
    public Collection<ScriptChunk> getAllScripts() {
        if (!isLoaded()) {
            load();
        }
        if (sourceFile != null) {
            return sourceFile.getScripts();
        }
        return scripts.values();
    }

    /**
     * Get the ScriptNamesChunk for this cast library.
     */
    public com.libreshockwave.chunks.ScriptNamesChunk getScriptNames() {
        if (sourceFile != null) {
            return sourceFile.getScriptNames();
        }
        return null;
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
            case "selection" -> selection;
            case "loaded" -> isLoaded() ? Datum.TRUE : Datum.FALSE;
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
            case "selection" -> {
                // Selection is a list of ranges like [[1, 10], [30, 40]]
                if (value instanceof Datum.List) {
                    this.selection = value;
                } else {
                    this.selection = Datum.list();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Get a member property.
     */
    public Datum getMemberProp(int memberNumber, String propName) {
        CastMember member = getMember(memberNumber);
        if (member == null) {
            // Return defaults for invalid members
            return getInvalidMemberProp(propName);
        }
        return member.getProp(propName);
    }

    /**
     * Set a member property.
     */
    public boolean setMemberProp(int memberNumber, String propName, Datum value) {
        CastMember member = getMember(memberNumber);
        if (member == null) {
            return false;
        }
        return member.setProp(propName, value);
    }

    /**
     * Get a property for an invalid/non-existent member.
     */
    private Datum getInvalidMemberProp(String propName) {
        String prop = propName.toLowerCase();
        return switch (prop) {
            case "name" -> Datum.EMPTY_STRING;
            case "number", "castlibnum", "membernum" -> Datum.of(-1);
            case "type" -> Datum.of("empty");
            default -> Datum.VOID;
        };
    }

    // ==================== External Cast Loading ====================

    private String basePath = "";

    /**
     * Set the base path for resolving relative file paths.
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath != null ? basePath : "";
    }

    /**
     * Load members from an external DirectorFile that was fetched.
     * Uses the first cast from the external file.
     */
    private void loadFromExternalFile() {
        if (sourceFile == null) {
            return;
        }

        // Get the first cast from the external file
        if (sourceFile.getCasts().isEmpty()) {
            return;
        }

        var externalCasts = sourceFile.getCasts();
        var externalCastList = sourceFile.getCastList();

        // Get minMember from the external file's config or cast list
        int minMember = 1;
        if (externalCastList != null && !externalCastList.entries().isEmpty()) {
            minMember = externalCastList.entries().get(0).minMember();
            if (minMember <= 0) minMember = 1;
        } else if (sourceFile.getConfig() != null) {
            minMember = sourceFile.getConfig().minMember();
            if (minMember <= 0) minMember = 1;
        }

        // Load members from the external cast
        var extCastChunk = externalCasts.get(0);
        totalSlotCount = extCastChunk.memberIds().size();
        for (int i = 0; i < extCastChunk.memberIds().size(); i++) {
            int chunkId = extCastChunk.memberIds().get(i);
            if (chunkId <= 0) {
                continue;
            }

            int memberNumber = i + minMember;

            for (CastMemberChunk member : sourceFile.getCastMembers()) {
                if (member.id() == chunkId) {
                    memberChunks.put(memberNumber, member);

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
    }

    /**
     * Set the external cast data from preloadNetThing.
     * @param data The raw file data
     * @return true if parsing was successful
     */
    public boolean setExternalData(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        try {
            DirectorFile file = DirectorFile.load(data);
            if (file != null) {
                this.sourceFile = file;
                // Reset state so load() will re-parse with the new data
                // (the cast may have been previously loaded with different data, e.g. empty.cst)
                this.state = State.NONE;
                this.memberChunks.clear();
                this.members.clear();
                this.scripts.clear();
                load();
                return true;
            }
        } catch (Exception e) {
            System.err.println("[CastLib] Failed to parse external cast " + name + ": " + e.getMessage());
        }
        return false;
    }

    // Track next dynamic member number for new members created at runtime
    private int nextDynamicMember = 10000;

    /**
     * Create a new dynamic cast member of the given type.
     * Used by Director's new(#type, castLib) syntax.
     * @param typeName The member type name (e.g. "field", "text", "bitmap")
     * @return The new CastMember, or null if creation failed
     */
    public CastMember createDynamicMember(String typeName) {
        if (!isLoaded()) {
            load();
        }

        // Find the next available member number
        int memberNum = nextDynamicMember++;

        // Map type name to MemberType
        MemberType type = switch (typeName.toLowerCase()) {
            case "field", "text" -> MemberType.TEXT;
            case "bitmap" -> MemberType.BITMAP;
            case "script" -> MemberType.SCRIPT;
            case "button" -> MemberType.BUTTON;
            case "shape" -> MemberType.SHAPE;
            case "sound" -> MemberType.SOUND;
            default -> MemberType.TEXT; // Default to text for unknown types
        };

        CastMember member = new CastMember(number, memberNum, type);
        members.put(memberNum, member);
        return member;
    }

    @Override
    public String toString() {
        return "CastLib{number=" + number + ", name='" + name + "', members=" + members.size() + "}";
    }
}
