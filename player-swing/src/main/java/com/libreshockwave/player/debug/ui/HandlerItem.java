package com.libreshockwave.player.debug.ui;

import com.libreshockwave.chunks.ScriptChunk;

/**
 * Combo box item representing a handler in the bytecode debugger.
 */
public class HandlerItem {
    private final ScriptChunk script;
    private final ScriptChunk.Handler handler;

    public HandlerItem(ScriptChunk script, ScriptChunk.Handler handler) {
        this.script = script;
        this.handler = handler;
    }

    public ScriptChunk getScript() {
        return script;
    }

    public ScriptChunk.Handler getHandler() {
        return handler;
    }

    @Override
    public String toString() {
        return script.getHandlerName(handler);
    }

    /**
     * Check if this item matches the filter text (case-insensitive).
     */
    public boolean matchesFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        return script.getHandlerName(handler).toLowerCase().contains(filter.toLowerCase());
    }
}
