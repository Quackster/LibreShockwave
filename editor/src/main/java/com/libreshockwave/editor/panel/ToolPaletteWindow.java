package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Tool Palette - drawing and selection tools for the Stage.
 */
public class ToolPaletteWindow extends EditorPanel {

    public ToolPaletteWindow(EditorContext context) {
        super("Tool Palette", context, true, true, false, true);

        JPanel panel = new JPanel(new GridLayout(0, 2, 2, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        String[] tools = {
            "Arrow", "Rotate",
            "Hand", "Zoom",
            "Line", "Rect",
            "Round Rect", "Ellipse",
            "Text", "Field",
            "Button", "Check Box",
            "Radio", "Color"
        };

        for (String tool : tools) {
            JButton btn = new JButton(tool);
            btn.setFont(btn.getFont().deriveFont(9f));
            btn.setMargin(new Insets(2, 2, 2, 2));
            panel.add(btn);
        }

        JScrollPane scrollPane = new JScrollPane(panel);
        setContentPane(scrollPane);
        setSize(160, 350);
    }
}
