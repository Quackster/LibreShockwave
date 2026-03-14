package com.libreshockwave.editor.script;

import javax.swing.*;
import java.util.List;

/**
 * Handler navigation dropdown for the script editor.
 * Lists all handlers in the current script for quick navigation.
 */
public class HandlerDropdown extends JComboBox<String> {

    public HandlerDropdown() {
        addItem("(No handlers)");
        setMaximumSize(new java.awt.Dimension(250, 25));
    }

    /**
     * Populate the dropdown with handler names from a script.
     */
    public void setHandlers(List<String> handlerNames) {
        removeAllItems();
        if (handlerNames == null || handlerNames.isEmpty()) {
            addItem("(No handlers)");
        } else {
            for (String name : handlerNames) {
                addItem(name);
            }
        }
    }

    /**
     * Clear the handler list.
     */
    public void clearHandlers() {
        removeAllItems();
        addItem("(No handlers)");
    }
}
