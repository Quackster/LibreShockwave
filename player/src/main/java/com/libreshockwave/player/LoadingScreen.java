package com.libreshockwave.player;

import java.awt.*;

/**
 * Shockwave-style loading screen renderer.
 * Draws a black canvas with "LibreShockwave" title, progress bar, and counter text.
 * Used by StagePanel while external casts are being fetched.
 */
public class LoadingScreen {

    private static final Color BG = new Color(0, 0, 0);
    private static final Color BORDER = new Color(80, 80, 80);
    private static final Color BAR_BG = new Color(60, 60, 60);
    private static final Color BAR_FG = new Color(0, 153, 255);  // Shockwave blue
    private static final Color BAR_BORDER = new Color(100, 100, 100);
    private static final Color TEXT_COLOR = new Color(200, 200, 200);
    private static final Color TITLE_COLOR = new Color(255, 255, 255);

    private boolean active = false;
    private boolean complete = false;
    private int total = 0;
    private int progress = 0;
    private Runnable onComplete;

    /**
     * Enter loading mode with the given total number of items to load.
     */
    public void start(int total) {
        this.active = true;
        this.complete = false;
        this.total = total;
        this.progress = 0;
    }

    /**
     * Increment progress by one.
     * When progress reaches total, marks as complete and fires the onComplete callback.
     * The screen stays visible (showing a full bar) until dismiss() is called.
     */
    public void incrementProgress() {
        progress++;
        if (progress >= total && !complete) {
            complete = true;
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /**
     * Dismiss the loading screen. Call this after the completion delay.
     */
    public void dismiss() {
        active = false;
    }

    /**
     * Reset the loading screen state.
     */
    public void reset() {
        active = false;
        complete = false;
        total = 0;
        progress = 0;
        onComplete = null;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Set a callback for when loading completes (progress reaches total).
     * The loading screen remains visible after this fires — call dismiss() to hide it.
     */
    public void setOnComplete(Runnable onComplete) {
        this.onComplete = onComplete;
    }

    /**
     * Paint the loading screen centered within the given canvas area.
     */
    public void paint(Graphics2D g, int canvasX, int canvasY, int stageWidth, int stageHeight) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Black background
        g.setColor(BG);
        g.fillRect(canvasX, canvasY, stageWidth, stageHeight);

        // Canvas border
        g.setColor(BORDER);
        g.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

        int cx = canvasX + stageWidth / 2;
        int cy = canvasY + stageHeight / 2;

        // Title
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        FontMetrics fmTitle = g.getFontMetrics();
        String title = "LibreShockwave";
        g.setColor(TITLE_COLOR);
        g.drawString(title, cx - fmTitle.stringWidth(title) / 2, cy - 30);

        // Progress bar
        int barWidth = Math.min(300, stageWidth - 80);
        int barHeight = 12;
        int barX = cx - barWidth / 2;
        int barY = cy + 10;

        g.setColor(BAR_BG);
        g.fillRoundRect(barX, barY, barWidth, barHeight, 6, 6);

        if (total > 0) {
            int fillWidth = (int) (barWidth * ((float) progress / total));
            if (fillWidth > 0) {
                g.setColor(BAR_FG);
                g.fillRoundRect(barX, barY, fillWidth, barHeight, 6, 6);
            }
        }

        g.setColor(BAR_BORDER);
        g.drawRoundRect(barX, barY, barWidth, barHeight, 6, 6);

        // Progress text
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        FontMetrics fmText = g.getFontMetrics();
        String progressText = complete
                ? "Complete (" + total + "/" + total + ")"
                : "Loading... " + progress + "/" + total;
        g.setColor(TEXT_COLOR);
        g.drawString(progressText, cx - fmText.stringWidth(progressText) / 2, barY + barHeight + 24);
    }
}
