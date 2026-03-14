package com.libreshockwave.editor.score;

import javax.swing.*;
import java.awt.*;

/**
 * Renders channel names and numbers to the left of the score grid.
 */
public class ChannelHeader extends JComponent {

    private static final int HEADER_WIDTH = 100;
    private static final int CELL_HEIGHT = 14;
    private static final int TOP_OFFSET = 20;

    // Special channel names matching Director MX 2004
    private static final String[] SPECIAL_CHANNELS = {
        "Tempo", "Palette", "Transition", "Sound 1", "Sound 2", "Script"
    };

    private int spriteChannelCount = 48;

    public void setSpriteChannelCount(int count) {
        this.spriteChannelCount = count;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int totalChannels = SPECIAL_CHANNELS.length + spriteChannelCount;
        return new Dimension(HEADER_WIDTH, TOP_OFFSET + totalChannels * CELL_HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setColor(new Color(220, 220, 220));
        g2.fillRect(0, 0, getWidth(), getHeight());

        g2.setFont(g2.getFont().deriveFont(10f));

        // Special channels
        g2.setColor(Color.DARK_GRAY);
        for (int i = 0; i < SPECIAL_CHANNELS.length; i++) {
            int y = TOP_OFFSET + i * CELL_HEIGHT + CELL_HEIGHT - 2;
            g2.drawString(SPECIAL_CHANNELS[i], 4, y);
        }

        // Separator line
        int sepY = TOP_OFFSET + SPECIAL_CHANNELS.length * CELL_HEIGHT;
        g2.setColor(Color.GRAY);
        g2.drawLine(0, sepY, HEADER_WIDTH, sepY);

        // Sprite channels
        g2.setColor(Color.BLACK);
        for (int i = 0; i < spriteChannelCount; i++) {
            int y = TOP_OFFSET + (SPECIAL_CHANNELS.length + i) * CELL_HEIGHT + CELL_HEIGHT - 2;
            g2.drawString(String.valueOf(i + 1), 4, y);
        }
    }
}
