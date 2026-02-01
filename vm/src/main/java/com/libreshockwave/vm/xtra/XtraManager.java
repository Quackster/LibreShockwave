package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.Datum;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Xtra registration and instance lifecycle.
 * Similar to dirplayer-rs player/xtra/manager.rs.
 */
public class XtraManager {

    private final Map<String, Xtra> registeredXtras = new HashMap<>();

    /**
     * Register an Xtra by name.
     */
    public void registerXtra(Xtra xtra) {
        registeredXtras.put(xtra.getName().toLowerCase(), xtra);
    }

    /**
     * Check if an Xtra is registered.
     */
    public boolean isXtraRegistered(String name) {
        return registeredXtras.containsKey(name.toLowerCase());
    }

    /**
     * Get an Xtra by name.
     */
    public Xtra getXtra(String name) {
        return registeredXtras.get(name.toLowerCase());
    }

    /**
     * Create an instance of an Xtra.
     * @param xtraName The Xtra name
     * @param args Constructor arguments
     * @return The Datum representing the instance, or VOID if Xtra not found
     */
    public Datum createInstance(String xtraName, List<Datum> args) {
        Xtra xtra = getXtra(xtraName);
        if (xtra == null) {
            System.err.println("[XtraManager] Xtra not found: " + xtraName);
            return Datum.VOID;
        }

        int instanceId = xtra.createInstance(args);
        return new Datum.XtraInstance(xtraName, instanceId);
    }

    /**
     * Call a handler on an Xtra instance.
     */
    public Datum callHandler(Datum.XtraInstance instance, String handlerName, List<Datum> args) {
        Xtra xtra = getXtra(instance.xtraName());
        if (xtra == null) {
            System.err.println("[XtraManager] Xtra not found: " + instance.xtraName());
            return Datum.VOID;
        }

        return xtra.callHandler(instance.instanceId(), handlerName, args);
    }

    /**
     * Get a property from an Xtra instance.
     */
    public Datum getProperty(Datum.XtraInstance instance, String propertyName) {
        Xtra xtra = getXtra(instance.xtraName());
        if (xtra == null) {
            return Datum.VOID;
        }

        return xtra.getProperty(instance.instanceId(), propertyName);
    }

    /**
     * Set a property on an Xtra instance.
     */
    public void setProperty(Datum.XtraInstance instance, String propertyName, Datum value) {
        Xtra xtra = getXtra(instance.xtraName());
        if (xtra != null) {
            xtra.setProperty(instance.instanceId(), propertyName, value);
        }
    }

    /**
     * Destroy an Xtra instance.
     */
    public void destroyInstance(Datum.XtraInstance instance) {
        Xtra xtra = getXtra(instance.xtraName());
        if (xtra != null) {
            xtra.destroyInstance(instance.instanceId());
        }
    }
}
