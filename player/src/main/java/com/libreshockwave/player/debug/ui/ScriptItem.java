package com.libreshockwave.player.debug.ui;

import com.libreshockwave.chunks.ScriptChunk;

/**
 * Combo box item representing a script in the bytecode debugger.
 */
public class ScriptItem {
    private final ScriptChunk script;
    private final String sourceName;  // Cast library name or file name
    private final int loadOrder;

    public ScriptItem(ScriptChunk script, String sourceName, int loadOrder) {
        this.script = script;
        this.sourceName = sourceName;
        this.loadOrder = loadOrder;
    }

    public ScriptChunk getScript() {
        return script;
    }

    public String getSourceName() {
        return sourceName;
    }

    public int getLoadOrder() {
        return loadOrder;
    }

    @Override
    public String toString() {
        String name = script.getDisplayName();
        if (sourceName != null && !sourceName.isEmpty()) {
            return name + " (" + sourceName + ")";
        }
        return name;
    }

    /**
     * Check if this item matches the filter text (case-insensitive).
     */
    public boolean matchesFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        String lowerFilter = filter.toLowerCase();
        return script.getDisplayName().toLowerCase().contains(lowerFilter)
            || (sourceName != null && sourceName.toLowerCase().contains(lowerFilter));
    }
}
