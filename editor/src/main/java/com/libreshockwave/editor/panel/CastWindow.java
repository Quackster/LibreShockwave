package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Cast window - displays cast members in a grid or list view.
 * Supports multiple cast library tabs.
 */
public class CastWindow extends EditorPanel {

    private final JTabbedPane castTabs;
    private final JLabel placeholder;

    public CastWindow(EditorContext context) {
        super("Cast", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Toolbar for view switching
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton gridViewBtn = new JButton("Grid");
        JButton listViewBtn = new JButton("List");
        toolbar.add(gridViewBtn);
        toolbar.add(listViewBtn);
        toolbar.addSeparator();
        JButton newMemberBtn = new JButton("New");
        newMemberBtn.setEnabled(false);
        toolbar.add(newMemberBtn);

        // Cast library tabs
        castTabs = new JTabbedPane(JTabbedPane.BOTTOM);
        placeholder = new JLabel("Internal Cast", SwingConstants.CENTER);
        castTabs.addTab("Internal", placeholder);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(castTabs, BorderLayout.CENTER);

        setContentPane(panel);
        setSize(400, 350);
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        // Will populate cast tabs from CastLibManager
        placeholder.setText("Internal Cast");
    }

    @Override
    protected void onFileClosed() {
        castTabs.removeAll();
        castTabs.addTab("Internal", new JLabel("Internal Cast", SwingConstants.CENTER));
    }
}
