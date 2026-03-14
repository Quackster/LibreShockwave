package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Library Palette - built-in behavior and transition library.
 */
public class LibraryPaletteWindow extends EditorPanel {

    public LibraryPaletteWindow(EditorContext context) {
        super("Library Palette", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Category selector
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Library: "));
        JComboBox<String> categorySelector = new JComboBox<>(new String[]{
            "Animation", "Controls", "Internet", "Media", "Navigation",
            "Paintbox", "Text", "3D"
        });
        topPanel.add(categorySelector);

        // Library items list
        DefaultListModel<String> listModel = new DefaultListModel<>();
        listModel.addElement("(Library items not yet loaded)");
        JList<String> itemList = new JList<>(listModel);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(itemList), BorderLayout.CENTER);

        setContentPane(panel);
        setSize(250, 350);
    }
}
