package com.libreshockwave.vm.builtin;

import java.util.Map;

/**
 * Interface for external parameter access.
 * Provides Shockwave-style externalParamValue/Name/Count builtins.
 * These correspond to PARAM tags in the HTML page that embeds the Shockwave plugin.
 */
public interface ExternalParamProvider {

    /**
     * Get the value of an external parameter by name (case-insensitive).
     * @param name The parameter name (e.g. "sw1")
     * @return The value, or null if not found
     */
    String getParamValue(String name);

    /**
     * Get the name of an external parameter by 1-based index.
     * @param index 1-based index
     * @return The name, or null if out of range
     */
    String getParamName(int index);

    /**
     * Get the total number of external parameters.
     */
    int getParamCount();

    /**
     * Get all external parameters as a map.
     */
    Map<String, String> getAllParams();

    // Thread-local provider for VM access
    ThreadLocal<ExternalParamProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(ExternalParamProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static ExternalParamProvider getProvider() {
        return CURRENT.get();
    }
}
