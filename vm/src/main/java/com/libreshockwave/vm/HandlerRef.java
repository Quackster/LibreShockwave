package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;

/**
 * Reference to a handler within a script.
 * For external cast scripts, includes the ScriptNamesChunk for name resolution.
 */
public record HandlerRef(ScriptChunk script, ScriptChunk.Handler handler, ScriptNamesChunk scriptNames) {

    /**
     * Constructor for handlers in the main file (uses file's ScriptNamesChunk).
     */
    public HandlerRef(ScriptChunk script, ScriptChunk.Handler handler) {
        this(script, handler, null);
    }

    /**
     * Check if this handler is from an external cast.
     */
    public boolean isExternal() {
        return scriptNames != null;
    }
}
