package com.libreshockwave.editor.selection;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks the current selection state in the editor and notifies listeners.
 */
public class SelectionManager {

    private final List<SelectionListener> listeners = new CopyOnWriteArrayList<>();
    private SelectionEvent currentSelection = SelectionEvent.none();

    public void addListener(SelectionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SelectionListener listener) {
        listeners.remove(listener);
    }

    public SelectionEvent getCurrentSelection() {
        return currentSelection;
    }

    public void select(SelectionEvent event) {
        this.currentSelection = event;
        for (SelectionListener listener : listeners) {
            listener.selectionChanged(event);
        }
    }

    public void clearSelection() {
        select(SelectionEvent.none());
    }
}
