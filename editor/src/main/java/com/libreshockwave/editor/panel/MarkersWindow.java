package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Markers window - frame label/marker management.
 */
public class MarkersWindow extends EditorPanel {

    private final DefaultListModel<String> markerListModel;

    public MarkersWindow(EditorContext context) {
        super("Markers", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Marker list
        markerListModel = new DefaultListModel<>();
        markerListModel.addElement("(No markers)");
        JList<String> markerList = new JList<>(markerListModel);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("Add");
        addBtn.setEnabled(false);
        JButton removeBtn = new JButton("Remove");
        removeBtn.setEnabled(false);
        JButton goToBtn = new JButton("Go To");
        goToBtn.setEnabled(false);
        buttonPanel.add(addBtn);
        buttonPanel.add(removeBtn);
        buttonPanel.add(goToBtn);

        panel.add(buttonPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(markerList), BorderLayout.CENTER);

        setContentPane(panel);
        setSize(250, 300);
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        markerListModel.clear();
        // Will populate from FrameLabelsChunk
        markerListModel.addElement("(Markers will be loaded from file)");
    }

    @Override
    protected void onFileClosed() {
        markerListModel.clear();
        markerListModel.addElement("(No markers)");
    }
}
