package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private Datum selection = Datum.list(); // Selected members as [[start, end], ...]

    // Raw member chunks indexed by member number
    private final Map<Integer, CastMemberChunk> memberChunks = new HashMap<>();

    // Loaded CastMember objects indexed by member number (lazy)
    private final Map<Integer, CastMember> members = new HashMap<>();

    // Scripts indexed by member number
    private final Map<Integer, ScriptChunk> scripts = new HashMap<>();

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
        return memberChunks.size();
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
     */
    public CastMember getMemberByName(String name) {
        if (!isLoaded()) {
            load();
        }

        for (Map.Entry<Integer, CastMemberChunk> entry : memberChunks.entrySet()) {
            if (entry.getValue().name() != null && entry.getValue().name().equalsIgnoreCase(name)) {
                return getMember(entry.getKey());
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
     * Fetch external cast data synchronously.
     * Called by preloadNetThing when the cast URL is requested.
     * Tries HTTPS first, then HTTP, then local file.
     * @return true if fetch was successful
     */
    public boolean fetchExternal() {
        if (!isExternal()) {
            return true; // Not external, nothing to fetch
        }

        if (sourceFile != null) {
            return true; // Already fetched
        }

        // Try fileName first
        if (fileName != null && !fileName.isEmpty()) {
            String path = fileName.replace("\\", "/");
            byte[] data = loadCastData(path);
            if (data != null) {
                parseDirectorFile(data);
                return sourceFile != null;
            }
        }

        // Try name as filename
        if (name != null && !name.isEmpty() && !name.equals("Internal")) {
            String path = name;
            if (!path.toLowerCase().endsWith(".cst") && !path.toLowerCase().endsWith(".cct")) {
                path = path + ".cst";
            }
            path = path.replace("\\", "/");
            byte[] data = loadCastData(path);
            if (data != null) {
                parseDirectorFile(data);
                return sourceFile != null;
            }
        }

        return false;
    }

    /**
     * Get the URL for this external cast (for preloadNetThing).
     */
    public String getExternalUrl() {
        if (!isExternal()) {
            return null;
        }

        String path = fileName;
        if (path == null || path.isEmpty()) {
            path = name;
            if (path != null && !path.isEmpty()) {
                if (!path.toLowerCase().endsWith(".cst") && !path.toLowerCase().endsWith(".cct")) {
                    path = path + ".cst";
                }
            }
        }

        if (path == null || path.isEmpty()) {
            return null;
        }

        path = path.replace("\\", "/");

        // If already a full URL, return it
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }

        // Build full URL from basePath
        if (!basePath.isEmpty()) {
            if (basePath.endsWith("/")) {
                return basePath + path;
            } else {
                return basePath + "/" + path;
            }
        }

        return path;
    }

    /**
     * Load cast data from a path. Tries HTTPS, then HTTP, then file.
     */
    private byte[] loadCastData(String path) {
        // If it's already a full URL, use it directly
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return loadFromUrl(path);
        }

        // Build the full path using basePath
        String fullPath = path;
        if (!basePath.isEmpty() && !path.startsWith("/")) {
            if (basePath.endsWith("/")) {
                fullPath = basePath + path;
            } else {
                fullPath = basePath + "/" + path;
            }
        }

        // Try HTTPS first
        if (basePath.startsWith("https://") || basePath.startsWith("http://")) {
            byte[] data = loadFromUrl(fullPath.replace("http://", "https://"));
            if (data != null) {
                return data;
            }

            // Try HTTP
            data = loadFromUrl(fullPath.replace("https://", "http://"));
            if (data != null) {
                return data;
            }
        } else {
            // Try as URL with HTTPS
            String httpsUrl = "https://" + fullPath;
            byte[] data = loadFromUrl(httpsUrl);
            if (data != null) {
                return data;
            }

            // Try HTTP
            String httpUrl = "http://" + fullPath;
            data = loadFromUrl(httpUrl);
            if (data != null) {
                return data;
            }
        }

        // Try as local file
        return loadFromFile(fullPath);
    }

    /**
     * Load data from a URL synchronously.
     */
    private byte[] loadFromUrl(String urlString) {
        try {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "LibreShockwave/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                conn.disconnect();
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return out.toByteArray();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            // Silently fail - will try next method
            return null;
        }
    }

    /**
     * Load data from a local file.
     */
    private byte[] loadFromFile(String filePath) {
        try {
            // Try as absolute path first
            Path path = Path.of(filePath);
            if (Files.exists(path)) {
                return Files.readAllBytes(path);
            }

            // Try relative to current directory
            File file = new File(filePath);
            if (file.exists()) {
                return Files.readAllBytes(file.toPath());
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the loaded data as a DirectorFile.
     */
    private void parseDirectorFile(byte[] data) {
        try {
            DirectorFile file = DirectorFile.load(data);
            if (file != null) {
                this.sourceFile = file;
                System.out.println("[CastLib] Loaded external cast: " + name + " (" + data.length + " bytes)");
            }
        } catch (Exception e) {
            System.err.println("[CastLib] Failed to parse external cast " + name + ": " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "CastLib{number=" + number + ", name='" + name + "', members=" + members.size() + "}";
    }
}
