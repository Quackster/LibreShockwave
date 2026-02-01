package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;

/**
 * Interface for movie property access.
 * Implemented by Player in player-core to provide access to movie-level properties.
 *
 * Movie properties are Lingo's "the" expressions like:
 * - the frame
 * - the moviePath
 * - the stageRight
 * - the exitLock
 * etc.
 */
public interface MoviePropertyProvider {

    /**
     * Get a movie property value.
     * @param propName The property name (e.g., "frame", "moviePath", "stageRight")
     * @return The property value, or VOID if not found
     */
    Datum getMovieProp(String propName);

    /**
     * Set a movie property value.
     * @param propName The property name
     * @param value The value to set
     * @return true if the property was set, false if read-only or unknown
     */
    boolean setMovieProp(String propName, Datum value);

    // Thread-local provider for VM access
    ThreadLocal<MoviePropertyProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(MoviePropertyProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static MoviePropertyProvider getProvider() {
        return CURRENT.get();
    }
}
