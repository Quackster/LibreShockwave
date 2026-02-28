package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.CastLibProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages cast libraries for the player.
 * Provides access to cast libraries and their members for Lingo scripts.
 * Cast libraries are lazily loaded when first accessed via castLib().
 */
public class CastLibManager implements CastLibProvider {

    private final DirectorFile file;
    private final Map<Integer, CastLib> castLibs = new HashMap<>();
    private boolean initialized = false;

    public CastLibManager(DirectorFile file) {
        this.file = file;
    }

    /**
     * Initialize cast library references from the DirectorFile.
     * This creates CastLib objects but doesn't load their members yet.
     * Uses CastListChunk as the primary source for cast library info.
     */
    private synchronized void ensureInitialized() {
        if (initialized || file == null) {
            return;
        }

        initialized = true;

        CastListChunk castList = file.getCastList();
        List<CastChunk> casts = file.getCasts();

        String basePath = file.getBasePath();

        if (castList != null && !castList.entries().isEmpty()) {
            // Use CastListChunk entries as the source
            for (int i = 0; i < castList.entries().size(); i++) {
                CastListChunk.CastListEntry listEntry = castList.entries().get(i);

                // Cast lib number is 1-based index
                int castLibNumber = i + 1;

                // Get the corresponding CastChunk if available
                CastChunk castChunk = (i < casts.size()) ? casts.get(i) : null;

                CastLib castLib = new CastLib(castLibNumber, castChunk, listEntry);

                // Set preload mode from cast list entry
                castLib.setPreloadMode(listEntry.preloadSettings());

                // Set base path for external cast loading
                castLib.setBasePath(basePath);

                // For internal casts (no external fileName), set the source file directly
                boolean isExternal = listEntry.path() != null && !listEntry.path().isEmpty();
                if (!isExternal) {
                    castLib.setSourceFile(file);
                }

                castLibs.put(castLibNumber, castLib);
            }
        } else if (!casts.isEmpty()) {
            // Fallback: use CastChunks directly if no cast list
            for (int i = 0; i < casts.size(); i++) {
                int castLibNumber = i + 1;
                CastLib castLib = new CastLib(castLibNumber, casts.get(i), null);
                castLib.setBasePath(basePath);
                castLib.setSourceFile(file);
                castLibs.put(castLibNumber, castLib);
            }
        }
    }

    /**
     * Get a cast library by number, loading it if necessary.
     */
    public CastLib getCastLib(int castLibNumber) {
        ensureInitialized();

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib != null && !castLib.isLoaded()) {
            castLib.load();
        }
        return castLib;
    }

    /**
     * Get a cast library by name, loading it if necessary.
     */
    public CastLib getCastLibByNameInternal(String name) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (castLib.getName().equalsIgnoreCase(name)) {
                if (!castLib.isLoaded()) {
                    castLib.load();
                }
                return castLib;
            }
        }

        // Check for "Internal" as default name for cast 1
        if ("internal".equalsIgnoreCase(name)) {
            return getCastLib(1);
        }

        return null;
    }

    @Override
    public int getCastLibByNumber(int castLibNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        return castLib != null ? castLib.getNumber() : -1;
    }

    @Override
    public int getCastLibByName(String name) {
        CastLib castLib = getCastLibByNameInternal(name);
        return castLib != null ? castLib.getNumber() : -1;
    }

    @Override
    public Datum getCastLibProp(int castLibNumber, String propName) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return Datum.VOID;
        }
        return castLib.getProp(propName);
    }

    @Override
    public boolean setCastLibProp(int castLibNumber, String propName, Datum value) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.setProp(propName, value);
    }

    @Override
    public Datum getMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            // Return reference anyway - will be invalid
            return new Datum.CastMemberRef(castLibNumber, memberNumber);
        }

        // Validate member exists
        CastMemberChunk member = castLib.findMemberByNumber(memberNumber);
        if (member == null) {
            // Return reference anyway - member may not exist but reference is valid syntax
            return new Datum.CastMemberRef(castLibNumber, memberNumber);
        }

        return new Datum.CastMemberRef(castLibNumber, memberNumber);
    }

    @Override
    public Datum getMemberByName(int castLibNumber, String memberName) {
        ensureInitialized();

        if (castLibNumber > 0) {
            // Search in specific cast
            CastLib castLib = getCastLib(castLibNumber);
            if (castLib != null) {
                CastMemberChunk member = castLib.findMemberByName(memberName);
                if (member != null) {
                    int memberNumber = castLib.getMemberNumber(member);
                    return new Datum.CastMemberRef(castLibNumber, memberNumber);
                }
            }
        } else {
            // Search in all casts
            for (CastLib castLib : castLibs.values()) {
                if (!castLib.isLoaded()) {
                    castLib.load();
                }
                CastMemberChunk member = castLib.findMemberByName(memberName);
                if (member != null) {
                    int memberNumber = castLib.getMemberNumber(member);
                    return new Datum.CastMemberRef(castLib.getNumber(), memberNumber);
                }
            }
        }

        return Datum.VOID;
    }

    @Override
    public int getCastLibCount() {
        ensureInitialized();
        return castLibs.size();
    }

    @Override
    public Datum getMemberProp(int castLibNumber, int memberNumber, String propName) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            // Return defaults for invalid cast lib
            return getInvalidMemberProp(propName);
        }
        return castLib.getMemberProp(memberNumber, propName);
    }

    @Override
    public boolean setMemberProp(int castLibNumber, int memberNumber, String propName, Datum value) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.setMemberProp(memberNumber, propName, value);
    }

    /**
     * Get a property for an invalid member reference.
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

    /**
     * Get a cast member chunk directly.
     */
    public CastMemberChunk getCastMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.findMemberByNumber(memberNumber);
    }

    /**
     * Get a cast member chunk by name.
     */
    public CastMemberChunk getCastMemberByName(String name) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                castLib.load();
            }
            CastMemberChunk member = castLib.findMemberByName(name);
            if (member != null) {
                return member;
            }
        }
        return null;
    }

    /**
     * Get all loaded cast libraries.
     */
    public Map<Integer, CastLib> getCastLibs() {
        ensureInitialized();
        return castLibs;
    }

    @Override
    public String getCastLibName(int castLibNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.getName();
    }

    @Override
    public String getCastLibFileName(int castLibNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.getFileName();
    }

    @Override
    public boolean fetchCastLib(int castLibNumber) {
        // External cast fetching is now handled by preloadNetThing
        // This method returns true if the cast is already fetched
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.isFetched();
    }

    @Override
    public boolean isCastLibExternal(int castLibNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.isExternal();
    }

    @Override
    public void preloadCasts(int mode) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            int preloadMode = castLib.getPreloadMode();

            // mode 1 = AfterFrameOne, mode 2 = BeforeFrameOne (MovieLoaded)
            if (preloadMode == mode) {
                // External casts must be fetched via preloadNetThing first
                if (castLib.isFetched() && !castLib.isLoaded()) {
                    castLib.load();
                }
            }
        }
    }

    /**
     * Find the cast lib number for a URL.
     * Used by preloadNetThing to identify which cast to load.
     * @param url The URL to find
     * @return The cast lib number if found, or -1
     */
    public int getCastLibNumberByUrl(String url) {
        ensureInitialized();

        String extractedFileName = FileUtil.getFileName(url);
        String fileName = FileUtil.getFileNameWithoutExtension(extractedFileName);

        for (CastLib castLib : castLibs.values()) {
            String castUrl = castLib.getFileName();
            if (castUrl != null && !castUrl.isEmpty() && castLib.getName().equalsIgnoreCase(fileName)) {
                return castLib.getNumber();
            }
        }
        return -1;
    }

    /**
     * Set external cast data from preloadNetThing.
     * @param castLibNumber The cast library number
     * @param data The raw file data
     * @return true if parsing was successful
     */
    public boolean setExternalCastData(int castLibNumber, byte[] data) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.setExternalData(data);
    }

    /**
     * Set external cast data by URL from preloadNetThing.
     * @param fileName The URL that was fetched
     * @param data The raw file data
     * @return true if parsing was successful
     */
    public boolean setExternalCastDataByUrl(String fileName, byte[] data) {
        int castLibNumber = getCastLibNumberByUrl(fileName);
        if (castLibNumber > 0) {
            return setExternalCastData(castLibNumber, data);
        }
        return false;
    }

    @Override
    public String getFieldValue(Object memberNameOrNum, int castId) {
        ensureInitialized();

        CastMember member = null;

        if (memberNameOrNum instanceof String name) {
            // Find by name
            if (castId > 0) {
                CastLib castLib = getCastLib(castId);
                if (castLib != null) {
                    member = castLib.getMemberByName(name);
                }
            } else {
                // Search all casts
                for (CastLib castLib : castLibs.values()) {
                    if (!castLib.isLoaded()) {
                        castLib.load();
                    }
                    member = castLib.getMemberByName(name);
                    if (member != null) {
                        break;
                    }
                }
            }
        } else if (memberNameOrNum instanceof Integer num) {
            // Find by number
            int effectiveCastId = castId > 0 ? castId : 1;
            CastLib castLib = getCastLib(effectiveCastId);
            if (castLib != null) {
                member = castLib.getMember(num);
            }
        }

        if (member != null) {
            return member.getTextContent();
        }

        return "";
    }

    /**
     * Find a handler by name across all cast libraries.
     * Searches loaded external casts for the handler.
     */
    @Override
    public HandlerLocation findHandler(String handlerName) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                // Only search loaded casts - don't trigger lazy load for handler search
                continue;
            }

            var scriptNames = castLib.getScriptNames();
            if (scriptNames == null) {
                continue;
            }

            for (var script : castLib.getAllScripts()) {
                var handler = script.findHandler(handlerName, scriptNames);
                if (handler != null) {
                    return new HandlerLocation(castLib.getNumber(), script, handler, scriptNames);
                }
            }
        }

        return null;
    }

    /**
     * Find a handler in a specific script by its cast member number.
     * Used for method calls on script instances - only searches the instance's parent script.
     *
     * @param memberNumber The cast member number (not the script chunk ID)
     * @param handlerName The handler name to find
     * @return The handler location if found, null otherwise
     */
    @Override
    public HandlerLocation findHandlerInScript(int memberNumber, String handlerName) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                continue;
            }

            var scriptNames = castLib.getScriptNames();
            if (scriptNames == null) {
                continue;
            }

            // Look up script by member number
            var script = castLib.getScript(memberNumber);
            if (script != null) {
                var handler = script.findHandler(handlerName, scriptNames);
                if (handler != null) {
                    return new HandlerLocation(castLib.getNumber(), script, handler, scriptNames);
                }
                // Found the script but no handler - don't continue searching
                return null;
            }
        }

        return null;
    }

    /**
     * Find a handler in a specific script in a specific cast library.
     * Used when we know both the cast lib number and member number.
     */
    public HandlerLocation findHandlerInScript(int castLibNumber, int memberNumber, String handlerName) {
        ensureInitialized();

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null || !castLib.isLoaded()) {
            return null;
        }

        var scriptNames = castLib.getScriptNames();
        if (scriptNames == null) {
            return null;
        }

        var script = castLib.getScript(memberNumber);
        if (script != null) {
            var handler = script.findHandler(handlerName, scriptNames);
            if (handler != null) {
                return new HandlerLocation(castLib.getNumber(), script, handler, scriptNames);
            }
        }

        return null;
    }
}
