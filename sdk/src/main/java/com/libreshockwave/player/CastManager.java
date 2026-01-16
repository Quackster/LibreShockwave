package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Manages all cast libraries for a Director movie.
 * Handles both internal and external casts.
 * Matches dirplayer-rs CastManager structure.
 */
public class CastManager {

    /**
     * Reason for preloading external casts.
     */
    public enum PreloadReason {
        MOVIE_LOADED,    // Before frame one (preload mode 2)
        AFTER_FRAME_ONE  // After frame one (preload mode 1)
    }

    private final List<CastLib> casts = new ArrayList<>();
    private final Map<String, DirectorFile> castFileCache = new HashMap<>();
    private String basePath;
    private BiConsumer<String, byte[]> externalLoader;
    private NetManager netManager;

    public CastManager() {
        this.basePath = "";
    }

    /**
     * Set the network manager for HTTP loading.
     */
    public void setNetManager(NetManager netManager) {
        this.netManager = netManager;
    }

    public NetManager getNetManager() {
        return netManager;
    }

    /**
     * Set the base path for resolving external cast files.
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath != null ? basePath : "";
    }

    public String getBasePath() {
        return basePath;
    }

    /**
     * Set a custom loader function for external cast files.
     * The loader takes a file path and returns the file bytes.
     */
    public void setExternalLoader(BiConsumer<String, byte[]> externalLoader) {
        this.externalLoader = externalLoader;
    }

    /**
     * Get all cast libraries.
     */
    public List<CastLib> getCasts() {
        return Collections.unmodifiableList(casts);
    }

    /**
     * Get cast library by number (1-based).
     */
    public CastLib getCast(int number) {
        if (number >= 1 && number <= casts.size()) {
            return casts.get(number - 1);
        }
        return null;
    }

    /**
     * Get cast library by name.
     */
    public CastLib getCastByName(String name) {
        for (CastLib cast : casts) {
            if (cast.getName().equalsIgnoreCase(name)) {
                return cast;
            }
        }
        return null;
    }

    /**
     * Get the total number of cast libraries.
     */
    public int getCastCount() {
        return casts.size();
    }

    /**
     * Load cast information from a DirectorFile.
     * This creates CastLib entries for both internal and external casts.
     * Matches dirplayer-rs CastManager::load_from_dir().
     */
    public void loadFromDirectorFile(DirectorFile file) {
        casts.clear();

        CastListChunk castList = file.getCastList();
        List<CastChunk> fileCasts = file.getCasts();
        KeyTableChunk keyTable = file.getKeyTable();

        if (castList != null && !castList.entries().isEmpty()) {
            // Modern Director (v5.0+): Use MCsL entries
            for (int i = 0; i < castList.entries().size(); i++) {
                CastListChunk.CastListEntry entry = castList.entries().get(i);

                // Find matching CAS* chunk using KEY* table
                // The KEY* table maps MCsL entry ID (castId) to CAS* chunk resource ID (sectionId)
                CastChunk castDef = null;
                if (keyTable != null) {
                    int casFourCC = com.libreshockwave.io.BinaryReader.fourCC("CAS*");
                    KeyTableChunk.KeyTableEntry keyEntry = keyTable.findEntry(entry.id(), casFourCC);
                    if (keyEntry != null) {
                        // Find the CAS* chunk by its section ID
                        for (CastChunk c : fileCasts) {
                            if (c.id() == keyEntry.sectionId()) {
                                castDef = c;
                                break;
                            }
                        }
                    }
                }

                // If no KEY* entry found, fall back to direct ID matching
                if (castDef == null) {
                    for (CastChunk c : fileCasts) {
                        if (c.id() == entry.id()) {
                            castDef = c;
                            break;
                        }
                    }
                }

                // External if MCsL entry has a file path
                // Internal casts have empty paths - they store members in the main movie file
                String normalizedPath = CastLib.normalizeCastPath(basePath, entry.path());
                boolean isExternal = entry.path() != null && !entry.path().isEmpty();

                CastLib castLib = new CastLib(
                    i + 1,  // 1-based number
                    entry.name(),
                    normalizedPath,
                    isExternal
                );

                castLib.setMinMember(entry.minMember());
                castLib.setMaxMember(entry.maxMember());

                // Set preload mode from entry (if available)
                // The preload mode is stored in the MCsL entry path section

                if (!isExternal) {
                    // Internal cast - load members from main file
                    castLib.setDirectorFile(file);
                    // If we found a matching CAS* chunk, use it; otherwise load all members
                    if (castDef != null) {
                        loadCastMembers(castLib, file, castDef);
                    } else {
                        // No CAS* chunk found, load all available members and scripts
                        loadAllMembers(castLib, file);
                    }
                }

                casts.add(castLib);
            }
        } else if (!fileCasts.isEmpty()) {
            // Older Director: Single CAS* chunk
            CastChunk castDef = fileCasts.get(0);
            CastLib castLib = new CastLib(1, "", "", false);
            castLib.setDirectorFile(file);
            loadCastMembers(castLib, file, castDef);
            casts.add(castLib);
        }
    }

    /**
     * Load all members and scripts from the main DirectorFile into a CastLib.
     * Used when no CAS* chunk is found but the cast is internal (no external path).
     */
    private void loadAllMembers(CastLib castLib, DirectorFile file) {
        if (file.getConfig() != null) {
            castLib.setDirVersion(file.getConfig().directorVersion());
        }

        castLib.setState(CastLib.State.LOADED);

        // Load all scripts
        for (ScriptChunk script : file.getScripts()) {
            castLib.addScript(script.id(), script);
        }

        // Load all members
        for (CastMemberChunk member : file.getCastMembers()) {
            castLib.addMember(member.id(), member);
        }
    }

    /**
     * Load cast members from the main DirectorFile into a CastLib.
     */
    private void loadCastMembers(CastLib castLib, DirectorFile file, CastChunk castDef) {
        if (file.getConfig() != null) {
            castLib.setDirVersion(file.getConfig().directorVersion());
        }

        castLib.setState(CastLib.State.LOADED);

        // Load scripts
        for (ScriptChunk script : file.getScripts()) {
            castLib.addScript(script.id(), script);
        }

        // Load members
        // Use KEY* table to map member IDs to chunks if available
        KeyTableChunk keyTable = file.getKeyTable();
        if (keyTable != null && castDef != null) {
            for (int i = 0; i < castDef.memberIds().size(); i++) {
                int memberId = castDef.memberIds().get(i);
                if (memberId > 0) {
                    // Find CASt chunk for this member
                    for (CastMemberChunk member : file.getCastMembers()) {
                        if (member.id() == memberId) {
                            castLib.addMember(i + 1, member);  // 1-based slot
                            break;
                        }
                    }
                }
            }
        } else {
            // Fallback: add all members with their IDs
            for (CastMemberChunk member : file.getCastMembers()) {
                castLib.addMember(member.id(), member);
            }
        }
    }

    /**
     * Preload external casts based on reason.
     * Matches dirplayer-rs CastManager::preload_casts().
     */
    public void preloadCasts(PreloadReason reason) {
        for (CastLib cast : casts) {
            if (!cast.isExternal() || cast.getState() != CastLib.State.NONE) {
                continue;
            }
            if (cast.getFileName().isEmpty()) {
                continue;
            }

            CastLib.PreloadMode mode = cast.getPreloadMode();
            boolean shouldPreload = switch (mode) {
                case WHEN_NEEDED -> false;  // Load on demand only
                case AFTER_FRAME_ONE -> reason == PreloadReason.AFTER_FRAME_ONE;
                case BEFORE_FRAME_ONE -> reason == PreloadReason.MOVIE_LOADED;
            };

            if (shouldPreload) {
                loadExternalCast(cast);
            }
        }
    }

    /**
     * Load an external cast file.
     * Returns true if loaded successfully.
     */
    public boolean loadExternalCast(CastLib cast) {
        if (!cast.isExternal()) {
            return true;  // Already internal
        }

        String fileName = cast.getFileName();
        if (fileName.isEmpty()) {
            return false;
        }

        // Check cache first
        DirectorFile cachedFile = castFileCache.get(fileName);
        if (cachedFile != null) {
            cast.loadFromDirectorFile(cachedFile);
            return true;
        }

        // Try to load the external file
        cast.setState(CastLib.State.LOADING);

        try {
            DirectorFile castFile = loadCastFile(fileName);
            if (castFile != null) {
                castFileCache.put(fileName, castFile);
                cast.loadFromDirectorFile(castFile);
                return true;
            }
        } catch (Exception e) {
            System.err.println("Failed to load external cast '" + fileName + "': " + e.getMessage());
        }

        cast.setState(CastLib.State.NONE);
        return false;
    }

    /**
     * Load an external cast file asynchronously.
     * Supports both file and HTTP URLs via NetManager.
     */
    public CompletableFuture<Boolean> loadExternalCastAsync(CastLib cast) {
        if (!cast.isExternal()) {
            return CompletableFuture.completedFuture(true);
        }

        String fileName = cast.getFileName();
        if (fileName.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        // Check cache first
        DirectorFile cachedFile = castFileCache.get(fileName);
        if (cachedFile != null) {
            cast.loadFromDirectorFile(cachedFile);
            return CompletableFuture.completedFuture(true);
        }

        cast.setState(CastLib.State.LOADING);

        // Use NetManager if available and we have HTTP base path
        if (netManager != null && netManager.getBasePath().isPresent()) {
            return loadCastFromNetwork(cast, fileName);
        }

        // Fall back to file loading
        return CompletableFuture.supplyAsync(() -> {
            try {
                DirectorFile castFile = loadCastFile(fileName);
                if (castFile != null) {
                    castFileCache.put(fileName, castFile);
                    cast.loadFromDirectorFile(castFile);
                    return true;
                }
            } catch (Exception e) {
                System.err.println("Failed to load cast: " + e.getMessage());
            }
            cast.setState(CastLib.State.NONE);
            return false;
        });
    }

    /**
     * Load a cast file over HTTP using NetManager.
     */
    private CompletableFuture<Boolean> loadCastFromNetwork(CastLib cast, String fileName) {
        System.out.println("[CastManager] Loading cast from network: " + fileName);
        int taskId = netManager.preloadNetThing(fileName);

        return netManager.awaitTask(taskId).thenApply(result -> {
            if (result.isSuccess()) {
                try {
                    byte[] data = result.getData();
                    System.out.println("[CastManager] Downloaded " + fileName + " (" + data.length + " bytes)");
                    DirectorFile castFile = DirectorFile.load(data);
                    if (castFile != null) {
                        castFileCache.put(fileName, castFile);
                        cast.loadFromDirectorFile(castFile);
                        System.out.println("[CastManager] Loaded cast: " + cast.getName() +
                            " with " + cast.getMemberCount() + " members");
                        return true;
                    }
                } catch (Exception e) {
                    System.err.println("[CastManager] Failed to parse cast from network: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.err.println("[CastManager] Failed to download cast: " + fileName +
                    " (error " + result.getErrorCode() + ")");
            }
            cast.setState(CastLib.State.NONE);
            return false;
        });
    }

    /**
     * Preload external casts asynchronously.
     */
    public CompletableFuture<Void> preloadCastsAsync(PreloadReason reason) {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (CastLib cast : casts) {
            if (!cast.isExternal() || cast.getState() != CastLib.State.NONE) {
                continue;
            }
            if (cast.getFileName().isEmpty()) {
                continue;
            }

            CastLib.PreloadMode mode = cast.getPreloadMode();
            boolean shouldPreload = switch (mode) {
                case WHEN_NEEDED -> false;
                case AFTER_FRAME_ONE -> reason == PreloadReason.AFTER_FRAME_ONE;
                case BEFORE_FRAME_ONE -> reason == PreloadReason.MOVIE_LOADED;
            };

            if (shouldPreload) {
                futures.add(loadExternalCastAsync(cast));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Load a cast file from the file system.
     * Override this method to provide custom loading (e.g., from URL, archive, etc.).
     */
    protected DirectorFile loadCastFile(String fileName) throws IOException {
        // Try multiple extensions
        String[] extensions = {".cct", ".cst", ".cxt"};
        String baseName = fileName;

        // Remove existing extension if present
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
        }

        for (String ext : extensions) {
            Path path = Path.of(baseName + ext);
            if (Files.exists(path)) {
                return DirectorFile.load(path);
            }
        }

        // Try original path
        Path originalPath = Path.of(fileName);
        if (Files.exists(originalPath)) {
            return DirectorFile.load(originalPath);
        }

        return null;
    }

    /**
     * Load a cast file from byte data.
     */
    public DirectorFile loadCastFileFromBytes(String fileName, byte[] data) throws IOException {
        DirectorFile file = DirectorFile.load(data);
        if (file != null) {
            castFileCache.put(fileName, file);
        }
        return file;
    }

    /**
     * Find a member by name across all casts.
     */
    public CastMemberChunk findMemberByName(String name) {
        for (CastLib cast : casts) {
            for (CastMemberChunk member : cast.getAllMembers()) {
                if (name.equals(member.name())) {
                    return member;
                }
            }
        }
        return null;
    }

    /**
     * Find a member by number within a specific cast.
     */
    public CastMemberChunk getMember(int castNum, int memberNum) {
        CastLib cast = getCast(castNum);
        if (cast != null) {
            return cast.getMember(memberNum);
        }
        return null;
    }

    /**
     * Get a script member by ID.
     */
    public ScriptChunk getScript(int scriptId) {
        for (CastLib cast : casts) {
            ScriptChunk script = cast.getScript(scriptId);
            if (script != null) {
                return script;
            }
        }
        return null;
    }

    /**
     * Clear the external cast file cache.
     */
    public void clearCache() {
        castFileCache.clear();
    }

    /**
     * Print a summary of all casts.
     */
    public void printSummary() {
        System.out.println("=== Cast Manager Summary ===");
        System.out.println("Total casts: " + casts.size());
        System.out.println("Base path: " + basePath);

        for (CastLib cast : casts) {
            System.out.println("\nCast #" + cast.getNumber() + ": " + cast.getName());
            System.out.println("  External: " + cast.isExternal());
            System.out.println("  State: " + cast.getState());
            System.out.println("  File: " + cast.getFileName());
            System.out.println("  Members: " + cast.getMemberCount());
            System.out.println("  Scripts: " + cast.getAllScripts().size());
            System.out.println("  Range: " + cast.getMinMember() + "-" + cast.getMaxMember());
        }
    }
}
