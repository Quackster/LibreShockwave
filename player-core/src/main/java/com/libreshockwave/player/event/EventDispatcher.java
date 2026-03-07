package com.libreshockwave.player.event;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.PlayerEvent;
import com.libreshockwave.player.behavior.BehaviorInstance;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.ControlFlowBuiltins;

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
    private CastLibManager castLibManager;
    private SpriteRegistry spriteRegistry;

    // Debug logging
    private boolean debugEnabled = false;

    // Event propagation control
    // In Director: if a handler exists and doesn't call pass(), propagation STOPS
    // If pass() is called, propagation CONTINUES to next handler
    private boolean stopPropagation = false;

    public EventDispatcher(DirectorFile file, LingoVM vm, BehaviorManager behaviorManager) {
        this.file = file;
        this.vm = vm;
        this.behaviorManager = behaviorManager;

        // Register pass callback with VM
        vm.setPassCallback(this::pass);
    }

    public void setCastLibManager(CastLibManager castLibManager) {
        this.castLibManager = castLibManager;
    }

    public void setSpriteRegistry(SpriteRegistry spriteRegistry) {
        this.spriteRegistry = spriteRegistry;
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
        stopPropagation = false;
        // Reset error state at start of each event dispatch
        // This allows execution to continue after errors
        vm.resetErrorState();

        // 1. Sprite behaviors (in channel order)
        List<BehaviorInstance> spriteInstances = behaviorManager.getSpriteInstances();
        BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
        for (BehaviorInstance instance : spriteInstances) {
            if (stopPropagation) {
                break;
            }
            invokeHandler(instance, handlerName, args);
        }

        // 2. Frame behavior
        if (!stopPropagation) {
            if (frameInstance != null) {
                invokeHandler(frameInstance, handlerName, args);
            }
        }

        // 3. Movie scripts
        if (!stopPropagation) {
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
        stopPropagation = false;

        // Frame behavior first
        BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
        if (frameInstance != null) {
            invokeHandler(frameInstance, handlerName, args);
        }

        // Then movie scripts
        if (!stopPropagation) {
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
     * Dispatches to both Score-based behaviors (BehaviorManager) and
     * dynamically attached behaviors (sprite.scriptInstanceList).
     */
    public void dispatchSpriteEvent(int channel, String handlerName, List<Datum> args) {
        // 1. Score-based behaviors
        List<BehaviorInstance> instances = behaviorManager.getInstancesForChannel(channel);
        for (BehaviorInstance instance : instances) {
            invokeHandler(instance, handlerName, args);
        }

        // 2. Dynamically attached behaviors via scriptInstanceList
        if (spriteRegistry != null) {
            SpriteState sprite = spriteRegistry.get(channel);
            if (sprite != null) {
                List<Datum> scriptInstances = sprite.getScriptInstanceList();
                if (scriptInstances != null && !scriptInstances.isEmpty()) {
                    // Snapshot to avoid ConcurrentModificationException
                    List<Datum> snapshot = new ArrayList<>(scriptInstances);
                    for (Datum target : snapshot) {
                        if (target instanceof Datum.ScriptInstance si) {
                            try {
                                ControlFlowBuiltins.callHandlerOnInstance(vm, si, handlerName, args);
                            } catch (Exception e) {
                                System.err.println("[EventDispatcher] Error in scriptInstanceList handler "
                                        + handlerName + " on sprite " + channel + ": " + e.getMessage());
                                if (debugEnabled) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Dispatch an event to movie scripts only using a PlayerEvent constant.
     */
    public void dispatchToMovieScripts(PlayerEvent event, List<Datum> args) {
        dispatchToMovieScripts(event.getHandlerName(), args);
    }

    /**
     * Dispatch an event to movie scripts only.
     * Movie scripts handle movie-level events: prepareMovie, startMovie, stopMovie.
     * Searches the main DCR and all loaded external casts.
     */
    public void dispatchToMovieScripts(String handlerName, List<Datum> args) {
        if (file == null) return;

        // 1. Main DCR movie scripts
        ScriptNamesChunk names = file.getScriptNames();
        if (names != null) {
            for (ScriptChunk script : file.getScripts()) {
                if (script.getScriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                    continue;
                }
                ScriptChunk.Handler handler = script.findHandler(handlerName, names);
                if (handler != null) {
                    try {
                        vm.executeHandler(script, handler, args, null);
                    } catch (Exception e) {
                        System.err.println("[EventDispatcher] Error in " + handlerName + ": " + e.getMessage());
                        if (debugEnabled) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        // 2. External cast movie scripts
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                if (!castLib.isExternal() || !castLib.isLoaded()) continue;
                ScriptNamesChunk castNames = castLib.getScriptNames();
                if (castNames == null) continue;
                for (ScriptChunk script : castLib.getAllScripts()) {
                    if (script.getScriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                        continue;
                    }
                    ScriptChunk.Handler handler = script.findHandler(handlerName, castNames);
                    if (handler != null) {
                        try {
                            vm.executeHandler(script, handler, args, null);
                        } catch (Exception e) {
                            System.err.println("[EventDispatcher] Error in " + handlerName
                                    + " (external cast " + castLib.getName() + "): " + e.getMessage());
                            if (debugEnabled) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Invoke a handler on a behavior instance.
     * In Director, if a handler exists and doesn't call pass(), propagation stops.
     */
    private void invokeHandler(BehaviorInstance instance, String handlerName, List<Datum> args) {
        if (instance == null || instance.getScript() == null) return;

        ScriptChunk script = instance.getScript();
        // Use the script's own file's names (may be an external cast, not the main DCR)
        ScriptNamesChunk names = script.file() != null ? script.file().getScriptNames() : null;
        if (names == null) {
            return;
        }

        ScriptChunk.Handler handler = script.findHandler(handlerName, names);

        if (handler == null) {
            return;
        }

        // Handler exists - by default, stop propagation unless pass() is called
        stopPropagation = true;

        try {
            // Pass the instance as the receiver ('me')
            Datum receiver = instance.toDatum();
            vm.executeHandler(script, handler, args, receiver);
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
     * This allows propagation to continue to subsequent handlers.
     */
    public void pass() {
        stopPropagation = false;
    }

    /**
     * Check if propagation was stopped.
     */
    public boolean isPropagationStopped() {
        return stopPropagation;
    }

}
