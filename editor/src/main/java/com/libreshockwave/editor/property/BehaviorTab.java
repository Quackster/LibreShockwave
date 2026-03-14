package com.libreshockwave.editor.property;

import javax.swing.*;
import java.awt.*;

/**
 * Behavior properties tab for the Property Inspector.
 * Shows behaviors attached to the selected sprite.
 */
public class BehaviorTab extends JPanel {

    private final DefaultListModel<String> listModel;

    public BehaviorTab() {
        setLayout(new BorderLayout());

        listModel = new DefaultListModel<>();
        listModel.addElement("(Select a sprite to see its behaviors)");
        JList<String> behaviorList = new JList<>(listModel);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("Add");
        addBtn.setEnabled(false);
        JButton removeBtn = new JButton("Remove");
        removeBtn.setEnabled(false);
        buttonPanel.add(addBtn);
        buttonPanel.add(removeBtn);

        add(buttonPanel, BorderLayout.NORTH);
        add(new JScrollPane(behaviorList), BorderLayout.CENTER);
    }
}
