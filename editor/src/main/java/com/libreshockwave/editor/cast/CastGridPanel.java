package com.libreshockwave.editor.cast;

import javax.swing.*;
import java.awt.*;

/**
 * Grid view of cast members with thumbnails.
 * Displays members as a grid of cells with type icons and names.
 */
public class CastGridPanel extends JPanel {

    private static final int CELL_SIZE = 64;
    private static final int CELL_PADDING = 4;

    public CastGridPanel() {
        setLayout(new FlowLayout(FlowLayout.LEFT, CELL_PADDING, CELL_PADDING));
        setBackground(Color.WHITE);
    }

    /**
     * Add a placeholder cell for a cast member.
     */
    public void addMemberCell(int memberNum, String name, String type) {
        JPanel cell = new JPanel(new BorderLayout());
        cell.setPreferredSize(new Dimension(CELL_SIZE, CELL_SIZE + 16));
        cell.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        cell.setBackground(Color.WHITE);

        // Thumbnail area
        JLabel thumbnail = new JLabel(String.valueOf(memberNum), SwingConstants.CENTER);
        thumbnail.setPreferredSize(new Dimension(CELL_SIZE, CELL_SIZE));
        thumbnail.setOpaque(true);
        thumbnail.setBackground(new Color(240, 240, 240));

        // Name label
        JLabel nameLabel = new JLabel(name != null ? name : "", SwingConstants.CENTER);
        nameLabel.setFont(nameLabel.getFont().deriveFont(9f));

        cell.add(thumbnail, BorderLayout.CENTER);
        cell.add(nameLabel, BorderLayout.SOUTH);

        add(cell);
    }

    public void clearMembers() {
        removeAll();
        revalidate();
        repaint();
    }
}
