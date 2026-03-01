package com.libreshockwave.player;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Swing panel that renders the Director stage using the player-core rendering API.
 * The stage canvas maintains a fixed size and is centered within the panel.
 * Resizing the window does not affect the stage dimensions.
 */
public class StagePanel extends JPanel {

    private static final Color VIEWPORT_COLOR = new Color(48, 48, 48);
    private static final Color CANVAS_BORDER_COLOR = new Color(80, 80, 80);

    private Player player;
    private final Map<Integer, BufferedImage> bitmapCache = new HashMap<>();
    private final LoadingScreen loadingScreen = new LoadingScreen();

    // Fixed stage dimensions (set from movie)
    private int stageWidth = 640;
    private int stageHeight = 480;

    public StagePanel() {
        setDoubleBuffered(true);
        setBackground(VIEWPORT_COLOR);
    }

    public void setPlayer(Player player) {
        this.player = player;
        bitmapCache.clear();
        loadingScreen.reset();
        repaint();
    }

    public LoadingScreen getLoadingScreen() {
        return loadingScreen;
    }

    /**
     * Set the fixed stage canvas size.
     * This determines the actual stage area, independent of panel size.
     */
    public void setStageSize(int width, int height) {
        this.stageWidth = width > 0 ? width : 640;
        this.stageHeight = height > 0 ? height : 480;
        repaint();
    }

    /**
     * Get the current stage width.
     */
    public int getStageWidth() {
        return stageWidth;
    }

    /**
     * Get the current stage height.
     */
    public int getStageHeight() {
        return stageHeight;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2d = (Graphics2D) g;

        // Fill viewport area with dark color
        g2d.setColor(VIEWPORT_COLOR);
        g2d.fillRect(0, 0, getWidth(), getHeight());

        if (player == null) {
            paintNoMovie(g2d);
            return;
        }

        if (loadingScreen.isActive()) {
            int canvasX = (getWidth() - stageWidth) / 2;
            int canvasY = (getHeight() - stageHeight) / 2;
            loadingScreen.paint(g2d, canvasX, canvasY, stageWidth, stageHeight);
            return;
        }

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Calculate centered position for the stage canvas
        int canvasX = (getWidth() - stageWidth) / 2;
        int canvasY = (getHeight() - stageHeight) / 2;

        // Get the frame snapshot from player-core
        FrameSnapshot snapshot = player.getFrameSnapshot();

        // Draw canvas border (subtle shadow effect)
        g2d.setColor(CANVAS_BORDER_COLOR);
        g2d.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

        // Clip to canvas area
        Shape oldClip = g2d.getClip();
        g2d.clipRect(canvasX, canvasY, stageWidth, stageHeight);

        // Translate to canvas origin
        g2d.translate(canvasX, canvasY);

        // Draw stage background
        g2d.setColor(new Color(snapshot.backgroundColor()));
        g2d.fillRect(0, 0, stageWidth, stageHeight);

        // Draw all sprites
        for (RenderSprite sprite : snapshot.sprites()) {
            drawSprite(g2d, sprite);
        }

        // Draw debug info
        drawDebugInfo(g2d, snapshot);

        // Restore transform and clip
        g2d.translate(-canvasX, -canvasY);
        g2d.setClip(oldClip);
    }

    private void paintNoMovie(Graphics2D g) {
        // Calculate centered position for the stage canvas
        int canvasX = (getWidth() - stageWidth) / 2;
        int canvasY = (getHeight() - stageHeight) / 2;

        // Draw canvas border
        g.setColor(CANVAS_BORDER_COLOR);
        g.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

        // Draw placeholder canvas
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(canvasX, canvasY, stageWidth, stageHeight);

        // Draw message centered in canvas
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        String msg = "No movie loaded";
        FontMetrics fm = g.getFontMetrics();
        int textX = canvasX + (stageWidth - fm.stringWidth(msg)) / 2;
        int textY = canvasY + (stageHeight + fm.getAscent()) / 2;
        g.drawString(msg, textX, textY);
    }

    private void drawSprite(Graphics2D g, RenderSprite sprite) {
        if (!sprite.isVisible()) {
            return;
        }

        int x = sprite.getX();
        int y = sprite.getY();
        int width = sprite.getWidth();
        int height = sprite.getHeight();

        switch (sprite.getType()) {
            case BITMAP -> drawBitmap(g, sprite, x, y, width, height);
            case SHAPE -> drawShape(g, sprite, x, y, width, height);
            case TEXT, BUTTON -> drawPlaceholder(g, x, y, width, height, sprite.getChannel(), "txt");
            default -> drawPlaceholder(g, x, y, width, height, sprite.getChannel(), "?");
        }
    }

    private void drawBitmap(Graphics2D g, RenderSprite sprite, int x, int y, int width, int height) {
        CastMemberChunk member = sprite.getCastMember();
        if (member == null) {
            drawPlaceholder(g, x, y, width, height, sprite.getChannel(), "bmp");
            return;
        }

        BufferedImage img = getCachedBitmap(member);
        if (img != null) {
            // Calculate actual position (regPoint offset)
            int drawX = x - (width > 0 ? 0 : img.getWidth() / 2);
            int drawY = y - (height > 0 ? 0 : img.getHeight() / 2);

            if (width > 0 && height > 0) {
                g.drawImage(img, x, y, width, height, null);
            } else {
                g.drawImage(img, drawX, drawY, null);
            }
        } else {
            drawPlaceholder(g, x, y, width, height, sprite.getChannel(), "bmp");
        }
    }

    private void drawShape(Graphics2D g, RenderSprite sprite, int x, int y, int width, int height) {
        int fc = sprite.getForeColor();
        g.setColor(new Color(fc, fc, fc));
        g.fillRect(x, y, width > 0 ? width : 50, height > 0 ? height : 50);
    }

    private BufferedImage getCachedBitmap(CastMemberChunk member) {
        int id = member.id();
        if (bitmapCache.containsKey(id)) {
            return bitmapCache.get(id);
        }

        if (player == null) {
            return null;
        }

        Optional<Bitmap> bitmap = player.decodeBitmap(member);
        if (bitmap.isPresent()) {
            BufferedImage img = bitmap.get().toBufferedImage();
            bitmapCache.put(id, img);
            return img;
        }

        bitmapCache.put(id, null);
        return null;
    }

    private void drawPlaceholder(Graphics2D g, int x, int y, int width, int height, int channel, String label) {
        int w = width > 0 ? width : 50;
        int h = height > 0 ? height : 50;

        // Draw a simple placeholder box
        g.setColor(new Color(200, 200, 200, 128));
        g.fillRect(x, y, w, h);
        g.setColor(Color.GRAY);
        g.drawRect(x, y, w, h);

        // Draw channel number and type
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString(label + channel, x + 2, y + 12);
    }

    private void drawDebugInfo(Graphics2D g, FrameSnapshot snapshot) {
        // Draw current frame info in corner of the stage canvas
        g.setColor(new Color(0, 0, 0, 128));
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.drawString(snapshot.debugInfo(), 5, stageHeight - 5);
    }
}
