package com.libreshockwave.editor.score;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders frame label markers above the score columns.
 */
public class MarkersBar extends JComponent {

    private static final int BAR_HEIGHT = 20;
    private static final int CELL_WIDTH = 12;

    private final Map<Integer, String> markers = new LinkedHashMap<>();

    public void setMarkers(Map<Integer, String> markers) {
        this.markers.clear();
        this.markers.putAll(markers);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(getParent() != null ? getParent().getWidth() : 600, BAR_HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setColor(new Color(230, 230, 230));
        g2.fillRect(0, 0, getWidth(), BAR_HEIGHT);

        g2.setColor(Color.DARK_GRAY);
        g2.setFont(g2.getFont().deriveFont(9f));

        for (Map.Entry<Integer, String> entry : markers.entrySet()) {
            int x = (entry.getKey() - 1) * CELL_WIDTH;
            g2.setColor(Color.RED);
            g2.fillPolygon(
                new int[]{x, x + 6, x},
                new int[]{0, 0, 6},
                3
            );
            g2.setColor(Color.BLACK);
            g2.drawString(entry.getValue(), x + 8, BAR_HEIGHT - 4);
        }
    }
}
