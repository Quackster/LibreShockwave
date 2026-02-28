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

    /**
     * Get the current item delimiter character.
     * Used for string chunk operations (item...of).
     * @return The item delimiter, defaults to ','
     */
    default char getItemDelimiter() {
        Datum d = getMovieProp("itemDelimiter");
        if (d != null && !d.isVoid()) {
            String s = d.toStr();
            return s.isEmpty() ? ',' : s.charAt(0);
        }
        return ',';
    }

    /**
     * Set the item delimiter character.
     * Used for string chunk operations (item...of).
     * @param delimiter The delimiter character to use
     */
    default void setItemDelimiter(char delimiter) {
        setMovieProp("itemDelimiter", Datum.of(String.valueOf(delimiter)));
    }

    /**
     * Go to a specific frame number.
     * @param frame The 1-based frame number to go to
     */
    default void goToFrame(int frame) {
        // Default: no-op
    }

    /**
     * Go to a labeled frame.
     * @param label The frame label to go to
     */
    default void goToLabel(String label) {
        // Default: no-op
    }

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
