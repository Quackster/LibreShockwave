package com.libreshockwave.player.event;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.PlayerEvent;
import com.libreshockwave.player.behavior.BehaviorInstance;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatches events to scripts in the correct order.
 * Follows Director's event propagation: sprite behaviors → frame behaviors → movie scripts.
 */
public class EventDispatcher {

    private final DirectorFile file;
    private final LingoVM vm;
    private final BehaviorManager behaviorManager;

    // Debug logging
    private boolean debugEnabled = false;

    // Event propagation control
    private boolean eventPassed = false;

    public EventDispatcher(DirectorFile file, LingoVM vm, BehaviorManager behaviorManager) {
        this.file = file;
        this.vm = vm;
        this.behaviorManager = behaviorManager;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    /**
     * Dispatch a global event to all scripts.
     * Order: sprite behaviors → frame behavior → movie scripts
     */
    public void dispatchGlobalEvent(PlayerEvent event, List<Datum> args) {
        dispatchGlobalEvent(event.getHandlerName(), args);
    }

    /**
     * Dispatch a global event by handler name.
     */
    public void dispatchGlobalEvent(String handlerName, List<Datum> args) {
        eventPassed = false;

        if (debugEnabled) {
            System.out.println("[EventDispatcher] Dispatching global event: " + handlerName);
        }

        // 1. Sprite behaviors (in channel order)
        List<BehaviorInstance> spriteInstances = behaviorManager.getSpriteInstances();
        for (BehaviorInstance instance : spriteInstances) {
            if (eventPassed) break;
            invokeHandler(instance, handlerName, args);
        }

        // 2. Frame behavior
        if (!eventPassed) {
            BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
            if (frameInstance != null) {
                invokeHandler(frameInstance, handlerName, args);
            }
        }

        // 3. Movie scripts
        if (!eventPassed) {
            dispatchToMovieScripts(handlerName, args);
        }
    }

    /**
     * Dispatch an event only to frame and movie scripts.
     * Used for frame-level events like enterFrame, exitFrame.
     */
    public void dispatchFrameAndMovieEvent(PlayerEvent event, List<Datum> args) {
        dispatchFrameAndMovieEvent(event.getHandlerName(), args);
    }

    /**
     * Dispatch an event only to frame and movie scripts.
     */
    public void dispatchFrameAndMovieEvent(String handlerName, List<Datum> args) {
        eventPassed = false;

        if (debugEnabled) {
            System.out.println("[EventDispatcher] Dispatching frame/movie event: " + handlerName);
        }

        // Frame behavior first
        BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
        if (frameInstance != null) {
            invokeHandler(frameInstance, handlerName, args);
        }

        // Then movie scripts
        if (!eventPassed) {
            dispatchToMovieScripts(handlerName, args);
        }
    }

    /**
     * Dispatch an event to a specific sprite's behaviors.
     */
    public void dispatchSpriteEvent(int channel, PlayerEvent event, List<Datum> args) {
        dispatchSpriteEvent(channel, event.getHandlerName(), args);
    }

    /**
     * Dispatch an event to a specific sprite's behaviors.
     */
    public void dispatchSpriteEvent(int channel, String handlerName, List<Datum> args) {
        if (debugEnabled) {
            System.out.println("[EventDispatcher] Dispatching sprite event: " + handlerName + " to channel " + channel);
        }

        List<BehaviorInstance> instances = behaviorManager.getInstancesForChannel(channel);
        for (BehaviorInstance instance : instances) {
            invokeHandler(instance, handlerName, args);
        }
    }

    /**
     * Dispatch an event to movie scripts only.
     */
    public void dispatchToMovieScripts(String handlerName, List<Datum> args) {
        if (file == null) return;

        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) return;

        for (ScriptChunk script : file.getScripts()) {
            if (script.scriptType() == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                ScriptChunk.Handler handler = script.findHandler(handlerName, names);
                if (handler != null) {
                    if (debugEnabled) {
                        System.out.println("[EventDispatcher] Invoking movie script handler: " + handlerName);
                    }
                    try {
                        vm.executeHandler(script, handler, args, null);
                    } catch (Exception e) {
                        System.err.println("[EventDispatcher] Error in movie script " + handlerName + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Invoke a handler on a behavior instance.
     */
    private void invokeHandler(BehaviorInstance instance, String handlerName, List<Datum> args) {
        if (instance == null || instance.getScript() == null) return;
        if (file == null) return;

        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) return;

        ScriptChunk script = instance.getScript();
        ScriptChunk.Handler handler = script.findHandler(handlerName, names);

        if (handler == null) {
            // Handler not found in this script - not an error
            return;
        }

        if (debugEnabled) {
            System.out.println("[EventDispatcher] Invoking handler: " + handlerName +
                               " on " + instance);
        }

        try {
            // Pass the instance as the receiver ('me')
            Datum receiver = instance.toDatum();
            Datum result = vm.executeHandler(script, handler, args, receiver);

            // Check if pass() was called (would need VM integration)
            // For now, we continue propagation unless explicitly stopped

        } catch (Exception e) {
            System.err.println("[EventDispatcher] Error in handler " + handlerName +
                               " on " + instance + ": " + e.getMessage());
            if (debugEnabled) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Called by scripts to pass the event to the next handler.
     * Not yet implemented - would need VM integration.
     */
    public void pass() {
        eventPassed = true;
    }

    /**
     * Check if the current event was passed.
     */
    public boolean wasEventPassed() {
        return eventPassed;
    }
}
