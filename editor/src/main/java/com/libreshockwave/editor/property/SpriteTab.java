package com.libreshockwave.editor.property;

import javax.swing.*;
import java.awt.*;

/**
 * Sprite properties tab for the Property Inspector.
 * Displays locH, locV, width, height, ink, blend, etc.
 */
public class SpriteTab extends JPanel {

    public SpriteTab() {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        addProperty(gbc, row++, "Sprite:", "-");
        addProperty(gbc, row++, "Member:", "-");
        addProperty(gbc, row++, "X (locH):", "-");
        addProperty(gbc, row++, "Y (locV):", "-");
        addProperty(gbc, row++, "Width:", "-");
        addProperty(gbc, row++, "Height:", "-");
        addProperty(gbc, row++, "Ink:", "-");
        addProperty(gbc, row++, "Blend:", "-");
        addProperty(gbc, row++, "locZ:", "-");
        addProperty(gbc, row++, "Visible:", "-");
        addProperty(gbc, row++, "Moveable:", "-");
        addProperty(gbc, row++, "Editable:", "-");

        // Spacer at bottom
        gbc.gridy = row;
        gbc.weighty = 1.0;
        add(new JPanel(), gbc);
    }

    private void addProperty(GridBagConstraints gbc, int row, String label, String value) {
        gbc.gridy = row;
        gbc.gridx = 0;
        gbc.weightx = 0;
        add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        JTextField field = new JTextField(value);
        field.setEditable(false);
        add(field, gbc);
    }
}
