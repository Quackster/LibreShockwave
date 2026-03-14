package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Text editor window - rich text editing for text cast members.
 */
public class TextEditorWindow extends EditorPanel {

    public TextEditorWindow(EditorContext context) {
        super("Text", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Formatting toolbar
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JButton("B"));
        toolbar.add(new JButton("I"));
        toolbar.add(new JButton("U"));
        toolbar.addSeparator();
        toolbar.add(new JComboBox<>(new String[]{"Arial", "Times New Roman", "Courier New"}));
        toolbar.add(new JComboBox<>(new String[]{"12", "14", "16", "18", "24", "36"}));

        JTextPane textPane = new JTextPane();
        textPane.setText("Text Editor - Not yet implemented");

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JScrollPane(textPane), BorderLayout.CENTER);

        setContentPane(panel);
        setSize(400, 300);
    }
}
