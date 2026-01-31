package com.libreshockwave.tools.ui.renderer;

import javax.swing.*;
import java.awt.*;

/**
 * Row header renderer for channel names in the score table.
 */
public class RowHeaderRenderer extends JLabel implements ListCellRenderer<String> {

    public RowHeaderRenderer(JTable table) {
        setOpaque(true);
        setBorder(UIManager.getBorder("TableHeader.cellBorder"));
        setHorizontalAlignment(CENTER);
        setFont(table.getTableHeader().getFont());
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends String> list, String value,
            int index, boolean isSelected, boolean cellHasFocus) {
        setText(value);
        setBackground(UIManager.getColor("TableHeader.background"));
        return this;
    }
}
