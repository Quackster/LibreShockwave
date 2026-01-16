package com.libreshockwave.xtras;

import com.libreshockwave.vm.LingoVM;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages loading and registration of Director Xtras.
 */
public class XtraManager {

    private final List<Xtra> xtras = new ArrayList<>();

    /**
     * Add an Xtra to be managed.
     * @param xtra The Xtra to add
     */
    public void addXtra(Xtra xtra) {
        xtras.add(xtra);
    }

    /**
     * Register all loaded Xtras with a LingoVM.
     * @param vm The LingoVM to register Xtras with
     */
    public void registerAll(LingoVM vm) {
        for (Xtra xtra : xtras) {
            xtra.register(vm);
        }
    }

    /**
     * Get an Xtra by name.
     * @param name The Xtra name
     * @return The Xtra, or null if not found
     */
    public Xtra getXtra(String name) {
        for (Xtra xtra : xtras) {
            if (xtra.getName().equalsIgnoreCase(name)) {
                return xtra;
            }
        }
        return null;
    }

    /**
     * Get all loaded Xtras.
     * @return Unmodifiable list of Xtras
     */
    public List<Xtra> getXtras() {
        return Collections.unmodifiableList(xtras);
    }

    /**
     * Dispose all Xtras and clear the list.
     */
    public void dispose() {
        for (Xtra xtra : xtras) {
            xtra.dispose();
        }
        xtras.clear();
    }

    /**
     * Create an XtraManager with all standard Xtras pre-loaded.
     * @return An XtraManager with standard Xtras
     */
    public static XtraManager createWithStandardXtras() {
        XtraManager manager = new XtraManager();
        manager.addXtra(new NetLingoXtra());
        return manager;
    }
}
