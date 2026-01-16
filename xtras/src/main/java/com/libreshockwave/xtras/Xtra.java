package com.libreshockwave.xtras;

import com.libreshockwave.vm.LingoVM;

/**
 * Interface for Director Xtras.
 * Xtras extend Director functionality by providing additional Lingo commands.
 */
public interface Xtra {

    /**
     * Get the name of this Xtra.
     * @return Xtra name (e.g., "NetLingo")
     */
    String getName();

    /**
     * Register this Xtra's functions with a LingoVM.
     * @param vm The LingoVM to register functions with
     */
    void register(LingoVM vm);

    /**
     * Called when the Xtra should release resources.
     */
    default void dispose() {
    }
}
