package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Behavior Inspector - view and edit behaviors attached to sprites.
 */
public class BehaviorInspectorWindow extends EditorPanel {

    public BehaviorInspectorWindow(EditorContext context) {
        super("Behavior Inspector", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Behavior list
        DefaultListModel<String> listModel = new DefaultListModel<>();
        listModel.addElement("(No sprite selected)");
        JList<String> behaviorList = new JList<>(listModel);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("+");
        addBtn.setEnabled(false);
        JButton removeBtn = new JButton("-");
        removeBtn.setEnabled(false);
        buttonPanel.add(addBtn);
        buttonPanel.add(removeBtn);

        // Parameters area
        JPanel paramsPanel = new JPanel(new BorderLayout());
        paramsPanel.setBorder(BorderFactory.createTitledBorder("Parameters"));
        paramsPanel.add(new JLabel("Select a behavior to view parameters", SwingConstants.CENTER));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(behaviorList), paramsPanel);
        splitPane.setDividerLocation(120);

        panel.add(buttonPanel, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);

        setContentPane(panel);
        setSize(300, 350);
    }
}
