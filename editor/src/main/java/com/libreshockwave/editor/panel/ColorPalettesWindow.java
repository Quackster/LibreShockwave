package com.libreshockwave.editor.panel;

import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Color Palettes window - palette viewer/editor.
 */
public class ColorPalettesWindow extends EditorPanel {

    public ColorPalettesWindow(EditorContext context) {
        super("Color Palettes", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Palette selector
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Palette: "));
        JComboBox<String> paletteSelector = new JComboBox<>(new String[]{
            "System - Win", "System - Mac", "Rainbow", "Grayscale", "Pastels", "Vivid", "NTSC", "Metallic", "Web 216"
        });
        topPanel.add(paletteSelector);

        // Color grid placeholder
        JPanel colorGrid = new JPanel(new BorderLayout());
        colorGrid.setBackground(Color.WHITE);
        colorGrid.add(new JLabel("Color Palettes - Not yet implemented", SwingConstants.CENTER));

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(colorGrid, BorderLayout.CENTER);

        setContentPane(panel);
        setSize(350, 300);
    }
}
