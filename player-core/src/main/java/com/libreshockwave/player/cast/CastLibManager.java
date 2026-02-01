package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
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
     */
    private void ensureInitialized() {
        if (initialized || file == null) {
            return;
        }

        initialized = true;

        List<CastChunk> casts = file.getCasts();
        CastListChunk castList = file.getCastList();

        for (int i = 0; i < casts.size(); i++) {
            int castLibNumber = i + 1; // 1-based

            CastListChunk.CastListEntry listEntry = null;
            if (castList != null && i < castList.entries().size()) {
                listEntry = castList.entries().get(i);
            }

            CastLib castLib = new CastLib(castLibNumber, file, casts.get(i), listEntry);
            castLibs.put(castLibNumber, castLib);
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
}
