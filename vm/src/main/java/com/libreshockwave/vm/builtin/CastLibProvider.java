package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;

/**
 * Interface for cast library access.
 * Implemented by Player in player-core to provide access to cast libraries.
 *
 * Cast libraries contain cast members (bitmaps, scripts, sounds, etc.)
 * and can be accessed via castLib(number) or castLib("name") in Lingo.
 */
public interface CastLibProvider {

    /**
     * Get a cast library by number.
     * @param castLibNumber 1-based cast library number
     * @return The cast library number if found, or -1 if not found
     */
    int getCastLibByNumber(int castLibNumber);

    /**
     * Get a cast library by name.
     * @param name The cast library name
     * @return The cast library number if found, or -1 if not found
     */
    int getCastLibByName(String name);

    /**
     * Get a cast library property.
     * @param castLibNumber The cast library number
     * @param propName The property name (name, fileName, number, preloadMode, etc.)
     * @return The property value
     */
    Datum getCastLibProp(int castLibNumber, String propName);

    /**
     * Set a cast library property.
     * @param castLibNumber The cast library number
     * @param propName The property name
     * @param value The value to set
     * @return true if set successfully
     */
    boolean setCastLibProp(int castLibNumber, String propName, Datum value);

    /**
     * Get a cast member reference.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @return A CastMemberRef datum, or VOID if not found
     */
    Datum getMember(int castLibNumber, int memberNumber);

    /**
     * Get a cast member by name.
     * @param castLibNumber The cast library number (0 to search all)
     * @param memberName The member name
     * @return A CastMemberRef datum, or VOID if not found
     */
    Datum getMemberByName(int castLibNumber, String memberName);

    /**
     * Get the number of cast libraries.
     */
    int getCastLibCount();

    /**
     * Get a cast member property.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @param propName The property name (name, type, width, height, etc.)
     * @return The property value
     */
    Datum getMemberProp(int castLibNumber, int memberNumber, String propName);

    /**
     * Set a cast member property.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @param propName The property name
     * @param value The value to set
     * @return true if set successfully
     */
    boolean setMemberProp(int castLibNumber, int memberNumber, String propName, Datum value);

    // Thread-local provider for VM access
    ThreadLocal<CastLibProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(CastLibProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static CastLibProvider getProvider() {
        return CURRENT.get();
    }
}
