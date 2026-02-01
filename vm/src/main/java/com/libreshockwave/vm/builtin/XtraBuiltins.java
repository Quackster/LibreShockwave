package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.xtra.XtraManager;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Xtra-related builtin functions for Lingo.
 * Similar to dirplayer-rs player/handlers/types.rs xtra function.
 *
 * Usage in Lingo:
 *   set myXtra = xtra("Multiuser")
 *   set myInstance = new(myXtra)
 *   myInstance.someHandler()
 */
public final class XtraBuiltins {

    private XtraBuiltins() {}

    // Thread-local XtraManager for VM access
    private static final ThreadLocal<XtraManager> currentManager = new ThreadLocal<>();

    /**
     * Set the XtraManager for the current thread.
     * Call this before executing scripts that use Xtra functions.
     */
    public static void setManager(XtraManager manager) {
        currentManager.set(manager);
    }

    /**
     * Clear the XtraManager for the current thread.
     */
    public static void clearManager() {
        currentManager.remove();
    }

    /**
     * Get the current XtraManager.
     */
    public static XtraManager getManager() {
        return currentManager.get();
    }

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("xtra", XtraBuiltins::xtra);
    }

    /**
     * xtra(name)
     * Returns a reference to the named Xtra, or VOID if not registered.
     *
     * Example: set myXtra = xtra("Multiuser")
     */
    private static Datum xtra(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        String xtraName = args.get(0).toStr();
        XtraManager manager = currentManager.get();

        if (manager == null) {
            System.err.println("[XtraBuiltins] No XtraManager registered");
            return Datum.VOID;
        }

        if (!manager.isXtraRegistered(xtraName)) {
            System.err.println("[XtraBuiltins] Xtra not registered: " + xtraName);
            return Datum.VOID;
        }

        return new Datum.XtraRef(xtraName);
    }

    /**
     * Create a new Xtra instance.
     * Called via ConstructorBuiltins new() when given an XtraRef.
     */
    public static Datum createInstance(Datum.XtraRef xtraRef, List<Datum> args) {
        XtraManager manager = currentManager.get();
        if (manager == null) {
            System.err.println("[XtraBuiltins] No XtraManager registered");
            return Datum.VOID;
        }

        return manager.createInstance(xtraRef.xtraName(), args);
    }

    /**
     * Call a handler on an Xtra instance.
     */
    public static Datum callHandler(Datum.XtraInstance instance, String handlerName, List<Datum> args) {
        XtraManager manager = currentManager.get();
        if (manager == null) {
            return Datum.VOID;
        }

        return manager.callHandler(instance, handlerName, args);
    }

    /**
     * Get a property from an Xtra instance.
     */
    public static Datum getProperty(Datum.XtraInstance instance, String propertyName) {
        XtraManager manager = currentManager.get();
        if (manager == null) {
            return Datum.VOID;
        }

        return manager.getProperty(instance, propertyName);
    }

    /**
     * Set a property on an Xtra instance.
     */
    public static void setProperty(Datum.XtraInstance instance, String propertyName, Datum value) {
        XtraManager manager = currentManager.get();
        if (manager != null) {
            manager.setProperty(instance, propertyName, value);
        }
    }
}
