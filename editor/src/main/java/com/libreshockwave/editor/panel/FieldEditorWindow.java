package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Field editor window - simple text field editing.
 */
public class FieldEditorWindow extends EditorPanel {

    public FieldEditorWindow(EditorContext context) {
        super("Field", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());
        JTextArea textArea = new JTextArea();
        textArea.setText("Field Editor - Not yet implemented");

        panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        setContentPane(panel);
        setSize(350, 250);
    }
}
