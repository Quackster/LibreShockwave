package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Script Editor window - Lingo code editor with syntax highlighting.
 * Features handler navigation dropdown, line numbers, and auto-indent.
 */
public class ScriptEditorWindow extends EditorPanel {

    private final JTextPane editor;
    private final JComboBox<String> handlerDropdown;

    public ScriptEditorWindow(EditorContext context) {
        super("Script", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Toolbar with handler navigation
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        handlerDropdown = new JComboBox<>();
        handlerDropdown.addItem("(No script selected)");
        handlerDropdown.setPreferredSize(new Dimension(250, 25));
        toolbar.add(new JLabel(" Handler: "));
        toolbar.add(handlerDropdown);

        toolbar.addSeparator();
        JButton compileBtn = new JButton("Compile");
        compileBtn.setEnabled(false);
        toolbar.add(compileBtn);

        // Script editor area
        editor = new JTextPane();
        editor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        editor.setText("-- Select a script member to edit");
        editor.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(editor);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        setContentPane(panel);
        setSize(500, 400);
    }
}
