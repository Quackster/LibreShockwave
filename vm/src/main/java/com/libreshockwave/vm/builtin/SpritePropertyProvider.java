package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;

/**
 * Interface for sprite property access.
 * Implemented by Player in player-core to provide access to sprite properties.
 */
public interface SpritePropertyProvider {

    /**
     * Get a sprite property value.
     * @param spriteNum The sprite channel number
     * @param propName The property name (e.g., "locH", "visible", "member")
     * @return The property value, or VOID if not found
     */
    Datum getSpriteProp(int spriteNum, String propName);

    /**
     * Set a sprite property value.
     * @param spriteNum The sprite channel number
     * @param propName The property name
     * @param value The value to set
     * @return true if the property was set, false if read-only or unknown
     */
    boolean setSpriteProp(int spriteNum, String propName, Datum value);

    // Thread-local provider for VM access
    ThreadLocal<SpritePropertyProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(SpritePropertyProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static SpritePropertyProvider getProvider() {
        return CURRENT.get();
    }
}
