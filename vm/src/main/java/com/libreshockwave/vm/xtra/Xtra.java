package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.Datum;

import java.util.List;

/**
 * Interface for Xtra implementations.
 * Xtras are plugin extensions in Director/Shockwave.
 * Similar to dirplayer-rs player/xtra/mod.rs.
 */
public interface Xtra {

    /**
     * Get the name of this Xtra.
     */
    String getName();

    /**
     * Create a new instance of this Xtra.
     * @param args Constructor arguments
     * @return Instance ID for tracking
     */
    int createInstance(List<Datum> args);

    /**
     * Destroy an instance of this Xtra.
     */
    void destroyInstance(int instanceId);

    /**
     * Call a handler on an Xtra instance.
     * @param instanceId The instance ID
     * @param handlerName The handler name
     * @param args Handler arguments
     * @return The result
     */
    Datum callHandler(int instanceId, String handlerName, List<Datum> args);

    /**
     * Get a property from an Xtra instance.
     */
    Datum getProperty(int instanceId, String propertyName);

    /**
     * Set a property on an Xtra instance.
     */
    void setProperty(int instanceId, String propertyName, Datum value);
}
