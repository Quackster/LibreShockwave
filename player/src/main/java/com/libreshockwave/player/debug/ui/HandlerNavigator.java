package com.libreshockwave.player.debug.ui;

import com.libreshockwave.chunks.ScriptChunk;

import java.util.List;

/**
 * Utility class for finding and navigating to handler definitions.
 */
public class HandlerNavigator {

    /**
     * Result of searching for a handler by name.
     */
    public record HandlerLocation(ScriptChunk script, ScriptChunk.Handler handler) {
        public boolean found() {
            return script != null && handler != null;
        }

        public String handlerName() {
            if (!found()) return null;
            return script.getHandlerName(handler);
        }

        public int scriptId() {
            if (!found()) return -1;
            return script.id();
        }
    }

    private final List<ScriptChunk> allScripts;

    public HandlerNavigator(List<ScriptChunk> allScripts) {
        this.allScripts = allScripts;
    }

    /**
     * Find a handler by name across all scripts.
     */
    public HandlerLocation findHandler(String handlerName) {
        if (handlerName == null || handlerName.isEmpty()) {
            return new HandlerLocation(null, null);
        }

        for (ScriptChunk script : allScripts) {
            ScriptChunk.Handler handler = script.findHandler(handlerName);
            if (handler != null) {
                return new HandlerLocation(script, handler);
            }
        }

        return new HandlerLocation(null, null);
    }

    /**
     * Find a script by its ID.
     */
    public ScriptChunk findScriptById(int scriptId) {
        for (ScriptChunk script : allScripts) {
            if (script.id() == scriptId) {
                return script;
            }
        }
        return null;
    }

    /**
     * Find a handler in a specific script by name.
     */
    public HandlerLocation findHandlerInScript(int scriptId, String handlerName) {
        ScriptChunk script = findScriptById(scriptId);
        if (script != null) {
            ScriptChunk.Handler handler = script.findHandler(handlerName);
            if (handler != null) {
                return new HandlerLocation(script, handler);
            }
        }
        return new HandlerLocation(null, null);
    }

    /**
     * Get all scripts.
     */
    public List<ScriptChunk> getAllScripts() {
        return allScripts;
    }
}
