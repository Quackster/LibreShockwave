package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Message window - Lingo REPL/console.
 * Allows typing Lingo commands and viewing output.
 */
public class MessageWindow extends EditorPanel {

    private final JTextArea outputArea;
    private final JTextField inputField;

    public MessageWindow(EditorContext context) {
        super("Message", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Output area (read-only)
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        outputArea.setText("Welcome to LibreShockwave Editor\n-- Type Lingo commands below\n\n");

        JScrollPane scrollPane = new JScrollPane(outputArea);

        // Input field
        inputField = new JTextField();
        inputField.setFont(new Font("Monospaced", Font.PLAIN, 12));
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    executeCommand();
                }
            }
        });

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(inputField, BorderLayout.SOUTH);

        setContentPane(panel);
        setSize(450, 200);
    }

    private void executeCommand() {
        String command = inputField.getText().trim();
        if (!command.isEmpty()) {
            outputArea.append(">> " + command + "\n");
            // TODO: Execute via LingoVM
            outputArea.append("-- (command execution not yet implemented)\n");
            inputField.setText("");
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
        }
    }

    public void appendOutput(String text) {
        outputArea.append(text + "\n");
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
}
