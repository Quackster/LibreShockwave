package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Paint window - bitmap editor for cast member images.
 */
public class PaintWindow extends EditorPanel {

    public PaintWindow(EditorContext context) {
        super("Paint", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Toolbar placeholder
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JButton("Pencil"));
        toolbar.add(new JButton("Brush"));
        toolbar.add(new JButton("Eraser"));
        toolbar.add(new JButton("Fill"));
        toolbar.add(new JButton("Line"));
        toolbar.add(new JButton("Rect"));
        toolbar.add(new JButton("Oval"));
        toolbar.addSeparator();
        toolbar.add(new JButton("Select"));
        toolbar.add(new JButton("Lasso"));

        // Canvas area
        JPanel canvas = new JPanel();
        canvas.setBackground(Color.WHITE);
        canvas.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        JLabel label = new JLabel("Paint Editor - Not yet implemented", SwingConstants.CENTER);
        canvas.setLayout(new BorderLayout());
        canvas.add(label, BorderLayout.CENTER);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(canvas, BorderLayout.CENTER);

        setContentPane(panel);
        setSize(500, 400);
    }
}
