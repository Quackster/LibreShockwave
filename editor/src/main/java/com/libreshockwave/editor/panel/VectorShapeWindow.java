package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Vector Shape editor window.
 */
public class VectorShapeWindow extends EditorPanel {

    public VectorShapeWindow(EditorContext context) {
        super("Vector Shape", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JButton("Pen"));
        toolbar.add(new JButton("Line"));
        toolbar.add(new JButton("Rect"));
        toolbar.add(new JButton("Ellipse"));
        toolbar.addSeparator();
        toolbar.add(new JButton("Select"));

        JPanel canvas = new JPanel(new BorderLayout());
        canvas.setBackground(Color.WHITE);
        canvas.add(new JLabel("Vector Shape Editor - Not yet implemented", SwingConstants.CENTER));

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(canvas, BorderLayout.CENTER);

        setContentPane(panel);
        setSize(450, 350);
    }
}
