package com.libreshockwave.editor.property;

import javax.swing.*;
import java.awt.*;

/**
 * Movie-level properties tab for the Property Inspector.
 */
public class MovieTab extends JPanel {

    public MovieTab() {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;

        addProperty(gbc, row++, "Movie Name:", "-");
        addProperty(gbc, row++, "Stage Width:", "-");
        addProperty(gbc, row++, "Stage Height:", "-");
        addProperty(gbc, row++, "Stage Color:", "-");
        addProperty(gbc, row++, "Palette:", "-");
        addProperty(gbc, row++, "Tempo:", "-");
        addProperty(gbc, row++, "Total Frames:", "-");
        addProperty(gbc, row++, "Total Casts:", "-");
        addProperty(gbc, row++, "Copyright:", "-");

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
