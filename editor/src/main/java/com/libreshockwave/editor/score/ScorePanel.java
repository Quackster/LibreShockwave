package com.libreshockwave.editor.score;

import javax.swing.*;
import java.awt.*;

/**
 * Custom JComponent that paints the Director MX 2004 score grid.
 * Renders cells colored by member type with a playback head overlay.
 */
public class ScorePanel extends JComponent {

    private static final int CELL_WIDTH = 12;
    private static final int CELL_HEIGHT = 14;
    private static final int HEADER_HEIGHT = 20;

    private ScoreModel model;
    private int currentFrame = 1;

    public void setModel(ScoreModel model) {
        this.model = model;
        repaint();
    }

    public void setCurrentFrame(int frame) {
        this.currentFrame = frame;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        if (model == null) {
            return new Dimension(600, 200);
        }
        int w = model.getFrameCount() * CELL_WIDTH;
        int h = HEADER_HEIGHT + model.getChannelCount() * CELL_HEIGHT;
        return new Dimension(w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (model == null) {
            g2.setColor(Color.GRAY);
            g2.drawString("No score data loaded", 20, 30);
            return;
        }

        // Draw grid
        g2.setColor(Color.LIGHT_GRAY);
        int totalFrames = model.getFrameCount();
        int totalChannels = model.getChannelCount();

        for (int f = 0; f <= totalFrames; f++) {
            int x = f * CELL_WIDTH;
            g2.drawLine(x, HEADER_HEIGHT, x, HEADER_HEIGHT + totalChannels * CELL_HEIGHT);
        }
        for (int c = 0; c <= totalChannels; c++) {
            int y = HEADER_HEIGHT + c * CELL_HEIGHT;
            g2.drawLine(0, y, totalFrames * CELL_WIDTH, y);
        }

        // Draw frame numbers in header
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(g2.getFont().deriveFont(9f));
        for (int f = 0; f < totalFrames; f += 5) {
            int x = f * CELL_WIDTH + 2;
            g2.drawString(String.valueOf(f + 1), x, HEADER_HEIGHT - 4);
        }

        // Draw cells (placeholder - will use ScoreModel data)
        for (int c = 0; c < totalChannels; c++) {
            for (int f = 0; f < totalFrames; f++) {
                Color cellColor = model.getCellColor(c, f);
                if (cellColor != null) {
                    int x = f * CELL_WIDTH + 1;
                    int y = HEADER_HEIGHT + c * CELL_HEIGHT + 1;
                    g2.setColor(cellColor);
                    g2.fillRect(x, y, CELL_WIDTH - 1, CELL_HEIGHT - 1);
                }
            }
        }

        // Draw playback head
        int headX = (currentFrame - 1) * CELL_WIDTH;
        g2.setColor(new Color(255, 0, 0, 128));
        g2.fillRect(headX, 0, CELL_WIDTH, HEADER_HEIGHT + totalChannels * CELL_HEIGHT);
        g2.setColor(Color.RED);
        g2.drawLine(headX, 0, headX, HEADER_HEIGHT + totalChannels * CELL_HEIGHT);
    }
}
