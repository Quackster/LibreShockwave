package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.player.bitmap.Bitmap;

import java.util.*;
import java.util.Optional;

/**
 * Represents a cast library (internal or external).
 * Matches dirplayer-rs CastLib structure.
 */
public class CastLib {

    /**
     * State of external cast loading.
     */
    public enum State {
        NONE,      // Not loaded
        LOADING,   // Currently loading
        LOADED     // Members available
    }

    /**
     * Preload mode for external casts.
     */
    public enum PreloadMode {
        WHEN_NEEDED(0),      // Lazy load on first access
        AFTER_FRAME_ONE(1),  // Load after first frame
        BEFORE_FRAME_ONE(2); // Load before first frame

        private final int code;

        PreloadMode(int code) {
            this.code = code;
        }

        public static PreloadMode fromCode(int code) {
            for (PreloadMode mode : values()) {
                if (mode.code == code) return mode;
            }
            return WHEN_NEEDED;
        }

        public int code() {
            return code;
        }
    }

    private String name;
    private String fileName;         // Path to external .cct/.cst file
    private final int number;        // 1-based cast library number
    private boolean external;
    private State state;
    private PreloadMode preloadMode;

    // Script context for this cast
    private ScriptContextChunk scriptContext;
    private ScriptNamesChunk scriptNames;

    // DirectorFile for bitmap decoding (set for external casts)
    private DirectorFile directorFile;

    // Members mapped by slot number (1-based)
    private final Map<Integer, CastMemberChunk> members = new HashMap<>();
    private final Map<Integer, ScriptChunk> scripts = new HashMap<>();

    private int minMember;
    private int maxMember;
    private int dirVersion;
    private boolean capitalX;

    public CastLib(int number, String name, String fileName, boolean external) {
        this.number = number;
        this.name = name != null ? name : "";
        this.fileName = fileName != null ? fileName : "";
        this.external = external;
        this.state = external ? State.NONE : State.LOADED;
        this.preloadMode = PreloadMode.WHEN_NEEDED;
        this.minMember = 0;
        this.maxMember = 0;
        this.dirVersion = 0;
        this.capitalX = false;
    }

    // Getters and setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName != null ? fileName : "";
    }

    public int getNumber() {
        return number;
    }

    public boolean isExternal() {
        return external;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public PreloadMode getPreloadMode() {
        return preloadMode;
    }

    public void setPreloadMode(PreloadMode preloadMode) {
        this.preloadMode = preloadMode;
    }

    public void setPreloadMode(int code) {
        this.preloadMode = PreloadMode.fromCode(code);
    }

    public int getMinMember() {
        return minMember;
    }

    public void setMinMember(int minMember) {
        this.minMember = minMember;
    }

    public int getMaxMember() {
        return maxMember;
    }

    public void setMaxMember(int maxMember) {
        this.maxMember = maxMember;
    }

    public int getDirVersion() {
        return dirVersion;
    }

    public void setDirVersion(int dirVersion) {
        this.dirVersion = dirVersion;
    }

    public boolean isCapitalX() {
        return capitalX;
    }

    public void setCapitalX(boolean capitalX) {
        this.capitalX = capitalX;
    }

    public ScriptContextChunk getScriptContext() {
        return scriptContext;
    }

    public ScriptNamesChunk getScriptNames() {
        return scriptNames;
    }

    // Member access

    public int getMemberCount() {
        return members.size();
    }

    public CastMemberChunk getMember(int slot) {
        return members.get(slot);
    }

    public void addMember(int slot, CastMemberChunk member) {
        members.put(slot, member);
    }

    public Collection<CastMemberChunk> getAllMembers() {
        return Collections.unmodifiableCollection(members.values());
    }

    public ScriptChunk getScript(int id) {
        return scripts.get(id);
    }

    public void addScript(int id, ScriptChunk script) {
        scripts.put(id, script);
    }

    public Collection<ScriptChunk> getAllScripts() {
        return Collections.unmodifiableCollection(scripts.values());
    }

    public DirectorFile getDirectorFile() {
        return directorFile;
    }

    public void setDirectorFile(DirectorFile file) {
        this.directorFile = file;
    }

    /**
     * Decode a bitmap cast member from this cast library.
     * @param member The cast member chunk (must be a bitmap type)
     * @return Optional containing the decoded bitmap, or empty if decoding fails
     */
    public Optional<Bitmap> decodeBitmap(CastMemberChunk member) {
        if (directorFile != null) {
            return directorFile.decodeBitmap(member);
        }
        return Optional.empty();
    }

    /**
     * Decode a bitmap cast member by slot number.
     * @param slot The member slot number (1-based)
     * @return Optional containing the decoded bitmap, or empty if not found or not a bitmap
     */
    public Optional<Bitmap> decodeBitmap(int slot) {
        CastMemberChunk member = getMember(slot);
        if (member == null || !member.isBitmap()) {
            return Optional.empty();
        }
        return decodeBitmap(member);
    }

    /**
     * Clear all members and scripts (for reloading).
     */
    public void clear() {
        members.clear();
        scripts.clear();
        scriptContext = null;
        scriptNames = null;
    }

    /**
     * Load members from a DirectorFile (for external casts).
     * Matches dirplayer-rs CastLib::load_from_dir_file().
     */
    public void loadFromDirectorFile(DirectorFile file) {
        clear();
        this.directorFile = file;

        if (name.isEmpty() && file.getConfig() != null) {
            // Use file name as cast name if not set
            String baseName = getBaseNameNoExtension(fileName);
            if (!baseName.isEmpty()) {
                name = baseName;
            }
        }

        // Copy script context and names
        scriptContext = file.getScriptContext();
        scriptNames = file.getScriptNames();
        dirVersion = file.getConfig() != null ? file.getConfig().directorVersion() : 0;
        capitalX = file.isCapitalX();

        // Load all cast members
        for (CastMemberChunk member : file.getCastMembers()) {
            members.put(member.id(), member);
        }

        // Load all scripts
        for (ScriptChunk script : file.getScripts()) {
            scripts.put(script.id(), script);
        }

        state = State.LOADED;
    }

    /**
     * Get the base name of a file path without extension.
     */
    private static String getBaseNameNoExtension(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        // Normalize separators
        String normalized = path.replace("\\", "/");
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot > 0) ? fileName.substring(0, lastDot) : fileName;
    }

    /**
     * Normalize a cast library file path.
     * Converts .cst/.csx to .cct format and resolves relative to base path.
     * Matches dirplayer-rs normalize_cast_lib_path().
     */
    public static String normalizeCastPath(String basePath, String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }

        // Normalize path separators
        String normalized = filePath.replace("\\", "/");

        // Get just the file name part
        String[] parts = normalized.split("[/:]");
        String fileBaseName = parts[parts.length - 1];

        // Convert extension to .cct
        String castFileName;
        int dotIndex = fileBaseName.lastIndexOf('.');
        if (dotIndex > 0) {
            castFileName = fileBaseName.substring(0, dotIndex) + ".cct";
        } else {
            castFileName = fileBaseName + ".cct";
        }

        // Resolve against base path
        if (basePath != null && !basePath.isEmpty()) {
            String normalizedBase = basePath.replace("\\", "/");
            if (!normalizedBase.endsWith("/")) {
                normalizedBase += "/";
            }
            return normalizedBase + castFileName;
        }

        return castFileName;
    }

    @Override
    public String toString() {
        return "CastLib{" +
            "number=" + number +
            ", name='" + name + '\'' +
            ", external=" + external +
            ", state=" + state +
            ", members=" + members.size() +
            ", scripts=" + scripts.size() +
            '}';
    }
}
