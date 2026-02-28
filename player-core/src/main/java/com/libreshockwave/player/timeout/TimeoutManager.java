package com.libreshockwave.player.timeout;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.TimeoutProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages Director timeouts.
 * Timeouts are named timers that periodically call a handler on a target script instance.
 *
 * Usage in Lingo:
 *   timeout("myTimer").new(1000, #onTimer, me)  -- fires onTimer(me) every 1000ms
 *   timeout("myTimer").forget()                  -- cancel
 */
public class TimeoutManager implements TimeoutProvider {

    private final Map<String, TimeoutEntry> timeouts = new LinkedHashMap<>();

    /**
     * A registered timeout entry.
     */
    private static class TimeoutEntry {
        final String name;
        int periodMs;
        String handler;
        Datum target;
        boolean persistent;
        long lastFiredMs;

        TimeoutEntry(String name, int periodMs, String handler, Datum target, long currentTimeMs) {
            this.name = name;
            this.periodMs = periodMs;
            this.handler = handler;
            this.target = target;
            this.persistent = false;
            this.lastFiredMs = currentTimeMs;
        }
    }

    // -- TimeoutProvider implementation --

    @Override
    public Datum createTimeout(String name, int periodMs, String handler, Datum target) {
        long now = System.currentTimeMillis();
        timeouts.put(name, new TimeoutEntry(name, periodMs, handler, target, now));
        return new Datum.TimeoutRef(name);
    }

    @Override
    public void forgetTimeout(String name) {
        timeouts.remove(name);
    }

    @Override
    public Datum getTimeoutProp(String name, String prop) {
        TimeoutEntry entry = timeouts.get(name);
        if (entry == null) return Datum.VOID;

        return switch (prop.toLowerCase()) {
            case "name" -> Datum.of(entry.name);
            case "target" -> entry.target;
            case "period" -> Datum.of(entry.periodMs);
            case "handler" -> Datum.symbol(entry.handler);
            case "persistent" -> entry.persistent ? Datum.TRUE : Datum.FALSE;
            case "time" -> Datum.of((int) (System.currentTimeMillis() - entry.lastFiredMs));
            default -> Datum.VOID;
        };
    }

    @Override
    public boolean setTimeoutProp(String name, String prop, Datum value) {
        TimeoutEntry entry = timeouts.get(name);
        if (entry == null) return false;

        switch (prop.toLowerCase()) {
            case "target" -> entry.target = value;
            case "period" -> entry.periodMs = value.toInt();
            case "handler" -> {
                if (value instanceof Datum.Symbol sym) {
                    entry.handler = sym.name();
                } else {
                    entry.handler = value.toStr();
                }
            }
            case "persistent" -> entry.persistent = value.isTruthy();
            default -> { return false; }
        }
        return true;
    }

    // -- Timeout processing --

    /**
     * Process all active timeouts. Call this each tick from Player.
     * Fires any timeouts whose period has elapsed, calling the handler on the target.
     *
     * @param vm The Lingo VM instance
     * @param currentTimeMs Current time in milliseconds
     */
    public void processTimeouts(LingoVM vm, long currentTimeMs) {
        if (timeouts.isEmpty()) return;

        // Copy keys to avoid ConcurrentModificationException (handlers may create/remove timeouts)
        List<String> keys = new ArrayList<>(timeouts.keySet());

        for (String key : keys) {
            TimeoutEntry entry = timeouts.get(key);
            if (entry == null) continue;  // May have been removed by a previous handler

            long elapsed = currentTimeMs - entry.lastFiredMs;
            if (elapsed >= entry.periodMs) {
                entry.lastFiredMs = currentTimeMs;
                fireTimeout(vm, entry);
            }
        }
    }

    /**
     * Fire a single timeout: call target.handler(timeoutRef).
     */
    private void fireTimeout(LingoVM vm, TimeoutEntry entry) {
        Datum.TimeoutRef timeoutRef = new Datum.TimeoutRef(entry.name);
        List<Datum> args = List.of(timeoutRef);

        if (entry.target instanceof Datum.ScriptInstance target) {
            // Find the handler on the target's script (walking ancestor chain)
            invokeOnScriptInstance(vm, target, entry.handler, args);
        } else {
            // Non-instance target: try as a global handler call
            try {
                vm.callHandler(entry.handler, args);
            } catch (Exception e) {
                System.err.println("[TimeoutManager] Error in timeout '" + entry.name
                        + "' handler " + entry.handler + ": " + e.getMessage());
            }
        }
    }

    /**
     * Invoke a handler on a script instance, walking the ancestor chain.
     * Replicates the logic from ScriptInstanceMethodDispatcher for finding handlers.
     */
    private void invokeOnScriptInstance(LingoVM vm, Datum.ScriptInstance target,
                                        String handlerName, List<Datum> args) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            // No provider - fall back to global handler
            try {
                vm.callHandler(handlerName, args);
            } catch (Exception e) {
                System.err.println("[TimeoutManager] Error in timeout handler "
                        + handlerName + ": " + e.getMessage());
            }
            return;
        }

        // Walk ancestor chain to find the handler
        Datum.ScriptInstance current = target;
        for (int i = 0; i < 20; i++) {  // Safety limit
            Datum scriptRefDatum = current.properties().get(Datum.PROP_SCRIPT_REF);
            CastLibProvider.HandlerLocation location;

            if (scriptRefDatum instanceof Datum.ScriptRef sr) {
                location = provider.findHandlerInScript(sr.castLib(), sr.member(), handlerName);
            } else {
                location = provider.findHandlerInScript(current.scriptId(), handlerName);
            }

            if (location != null && location.script() instanceof ScriptChunk script
                    && location.handler() instanceof ScriptChunk.Handler handler) {
                try {
                    vm.executeHandler(script, handler, args, target);
                } catch (Exception e) {
                    System.err.println("[TimeoutManager] Error in timeout handler "
                            + handlerName + ": " + e.getMessage());
                }
                return;
            }

            // Walk to ancestor
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }

        // Handler not found on instance - try as global handler
        try {
            vm.callHandler(handlerName, args);
        } catch (Exception e) {
            System.err.println("[TimeoutManager] Error in timeout handler "
                    + handlerName + " (global fallback): " + e.getMessage());
        }
    }

    /**
     * Dispatch a system event to all active timeout targets.
     * Called for frame lifecycle events (prepareFrame, exitFrame, etc.) so that
     * timeout targets (like the Fuse Object Manager) receive these events.
     *
     * Key differences from periodic firing (fireTimeout):
     * - Calls the event handler name (e.g. "prepareFrame"), NOT the timeout's own handler
     * - Passes no args (empty list), not the TimeoutRef
     * - Silently skips if the handler isn't found on the target
     */
    public void dispatchSystemEvent(LingoVM vm, String handlerName) {
        if (timeouts.isEmpty()) return;

        // Snapshot targets to avoid ConcurrentModificationException
        List<TimeoutEntry> targets = new ArrayList<>(timeouts.values());
        for (TimeoutEntry entry : targets) {
            if (entry.target instanceof Datum.ScriptInstance target) {
                invokeOnScriptInstanceQuiet(vm, target, handlerName, List.of());
            }
        }
    }

    /**
     * Invoke a handler on a script instance quietly (no global fallback, no error on missing handler).
     * Used for system event forwarding where targets may not define every handler.
     */
    private void invokeOnScriptInstanceQuiet(LingoVM vm, Datum.ScriptInstance target,
                                              String handlerName, List<Datum> args) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) return;

        Datum.ScriptInstance current = target;
        for (int i = 0; i < 20; i++) {  // Safety limit
            Datum scriptRefDatum = current.properties().get(Datum.PROP_SCRIPT_REF);
            CastLibProvider.HandlerLocation location;

            if (scriptRefDatum instanceof Datum.ScriptRef sr) {
                location = provider.findHandlerInScript(sr.castLib(), sr.member(), handlerName);
            } else {
                location = provider.findHandlerInScript(current.scriptId(), handlerName);
            }

            if (location != null && location.script() instanceof ScriptChunk script
                    && location.handler() instanceof ScriptChunk.Handler handler) {
                try {
                    vm.executeHandler(script, handler, args, target);
                } catch (Exception e) {
                    System.err.println("[TimeoutManager] Error in system event '"
                            + handlerName + "': " + e.getMessage());
                }
                return;
            }

            // Walk to ancestor
            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }

        // Handler not found — silently return (no global fallback)
    }

    /**
     * Get the number of active timeouts.
     */
    public int getTimeoutCount() {
        return timeouts.size();
    }

    /**
     * Clear all timeouts. Called on movie stop.
     */
    public void clear() {
        timeouts.clear();
    }
}
