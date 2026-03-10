package com.libreshockwave.vm.builtin.timeout;

import com.libreshockwave.vm.datum.Datum;

/**
 * Interface for timeout management.
 * Implemented by TimeoutManager in player-core to provide Director's timeout() system.
 *
 * Director timeouts are named timers that periodically call a handler on a target object.
 * Created via: timeout("name").new(periodMs, #handler, target)
 */
public interface TimeoutProvider {

    /**
     * Create a new timeout.
     * @param name Unique timeout name
     * @param periodMs Period in milliseconds between firings
     * @param handler Handler name to call on target
     * @param target Target script instance to call handler on
     * @return TimeoutRef datum
     */
    Datum createTimeout(String name, int periodMs, String handler, Datum target);

    /**
     * Remove and cancel a timeout.
     * @param name The timeout name
     */
    void forgetTimeout(String name);

    /**
     * Check if a timeout with the given name exists.
     * @param name The timeout name
     * @return true if the timeout exists
     */
    boolean timeoutExists(String name);

    /**
     * Get a property of a timeout.
     * @param name The timeout name
     * @param prop Property name (name, target, period, handler, persistent)
     * @return The property value, or VOID if not found
     */
    Datum getTimeoutProp(String name, String prop);

    /**
     * Set a property of a timeout.
     * @param name The timeout name
     * @param prop Property name (target, period, handler, persistent)
     * @param value The value to set
     * @return true if set successfully
     */
    boolean setTimeoutProp(String name, String prop, Datum value);

    // Thread-local provider for VM access
    ThreadLocal<TimeoutProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(TimeoutProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static TimeoutProvider getProvider() {
        return CURRENT.get();
    }
}
