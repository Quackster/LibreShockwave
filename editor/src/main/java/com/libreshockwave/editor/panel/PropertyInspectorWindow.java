package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;
import com.libreshockwave.editor.selection.SelectionEvent;

import javax.swing.*;
import java.awt.*;

/**
 * Property Inspector - tabbed inspector showing properties for the selected object.
 * Tabs: Sprite, Member, Behavior, Movie (matching Director MX 2004).
 */
public class PropertyInspectorWindow extends EditorPanel {

    private final JTabbedPane tabs;
    private final JPanel spriteTab;
    private final JPanel memberTab;
    private final JPanel behaviorTab;
    private final JPanel movieTab;

    public PropertyInspectorWindow(EditorContext context) {
        super("Property Inspector", context, true, true, true, true);

        tabs = new JTabbedPane(JTabbedPane.TOP);

        spriteTab = createPlaceholderTab("Select a sprite to view its properties");
        memberTab = createPlaceholderTab("Select a cast member to view its properties");
        behaviorTab = createPlaceholderTab("Select a sprite to view its behaviors");
        movieTab = createPlaceholderTab("Open a movie to view its properties");

        tabs.addTab("Sprite", spriteTab);
        tabs.addTab("Member", memberTab);
        tabs.addTab("Behavior", behaviorTab);
        tabs.addTab("Movie", movieTab);

        setContentPane(tabs);
        setSize(280, 400);
    }

    private JPanel createPlaceholderTab(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected void onSelectionChanged(SelectionEvent event) {
        switch (event.type()) {
            case SPRITE -> tabs.setSelectedComponent(spriteTab);
            case CAST_MEMBER -> tabs.setSelectedComponent(memberTab);
            default -> {}
        }
    }
}
