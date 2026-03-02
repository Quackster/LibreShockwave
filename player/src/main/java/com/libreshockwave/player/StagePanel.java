package com.libreshockwave.player;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.vm.builtin.WindowProvider;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Swing panel that renders the Director stage using the player-core rendering API.
 * The stage canvas maintains a fixed size and is centered within the panel.
 * Resizing the window does not affect the stage dimensions.
 *
 * Ink processing (matte, background transparent, etc.) is handled by the Player's
 * BitmapCache and baked into each RenderSprite before it reaches this panel.
 */
public class StagePanel extends JPanel {

    private static final Color VIEWPORT_COLOR = new Color(48, 48, 48);
    private static final Color CANVAS_BORDER_COLOR = new Color(80, 80, 80);

    private Player player;

    // Fixed stage dimensions (set from movie)
    private int stageWidth = 640;
    private int stageHeight = 480;

    public StagePanel() {
        setDoubleBuffered(true);
        setBackground(VIEWPORT_COLOR);
    }

    public void setPlayer(Player player) {
        this.player = player;
        repaint();
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

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Calculate centered position for the stage canvas
        int canvasX = (getWidth() - stageWidth) / 2;
        int canvasY = (getHeight() - stageHeight) / 2;

        // Get the frame snapshot from player-core (bitmaps are already ink-processed)
        FrameSnapshot snapshot = player.getFrameSnapshot();

        // Draw canvas border (subtle shadow effect)
        g2d.setColor(CANVAS_BORDER_COLOR);
        g2d.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

        // Clip to canvas area
        Shape oldClip = g2d.getClip();
        g2d.clipRect(canvasX, canvasY, stageWidth, stageHeight);

        // Translate to canvas origin
        g2d.translate(canvasX, canvasY);

        // Draw stage background (or stage image if scripts have drawn on it)
        if (snapshot.stageImage() != null) {
            // Stage image contains the background + any script drawing (loading bar, etc.)
            BufferedImage stageImg = snapshot.stageImage().toBufferedImage();
            g2d.drawImage(stageImg, 0, 0, null);
        } else {
            g2d.setColor(new Color(snapshot.backgroundColor()));
            g2d.fillRect(0, 0, stageWidth, stageHeight);
        }

        // Draw all sprites on top of the stage image
        for (RenderSprite sprite : snapshot.sprites()) {
            drawSprite(g2d, sprite);
        }

        // Draw window buffers on top of sprites (e.g., loading bar window)
        drawWindowBuffers(g2d, snapshot);

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

        // Apply blend (opacity) if not 100%
        Composite oldComposite = null;
        int blend = sprite.getBlend();
        if (blend >= 0 && blend < 100) {
            oldComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
        }

        switch (sprite.getType()) {
            case BITMAP -> drawBitmap(g, sprite, x, y, width, height);
            case SHAPE -> drawShape(g, sprite, x, y, width, height);
            case TEXT, BUTTON -> drawText(g, sprite, x, y, width, height);
            default -> {} // Skip unknown types silently
        }

        if (oldComposite != null) {
            g.setComposite(oldComposite);
        }
    }

    private void drawBitmap(Graphics2D g, RenderSprite sprite, int x, int y, int width, int height) {
        Bitmap baked = sprite.getBakedBitmap();
        if (baked == null) {
            return;
        }

        BufferedImage img = baked.toBufferedImage();
        int w = width > 0 ? width : img.getWidth();
        int h = height > 0 ? height : img.getHeight();
        g.drawImage(img, x, y, w, h, null);
    }

    private void drawText(Graphics2D g, RenderSprite sprite, int x, int y, int width, int height) {
        String text = null;

        if (player != null) {
            CastLibManager clm = player.getCastLibManager();
            if (clm != null) {
                // Try dynamic member first (runtime-created text/field members)
                CastMember dynMember = sprite.getDynamicMember();
                if (dynMember != null) {
                    text = dynMember.getTextContent();
                }
                // Fall back to file-loaded member lookup by name
                if ((text == null || text.isEmpty()) && sprite.getCastMember() != null) {
                    String memberName = sprite.getCastMember().name();
                    if (memberName != null && !memberName.isEmpty()) {
                        text = clm.getFieldValue(memberName, 0);
                    }
                }
            }
        }

        if (text == null || text.isEmpty()) {
            return; // Nothing to render
        }

        int w = width > 0 ? width : 200;
        int h = height > 0 ? height : 20;

        // Draw text with foreColor
        int fc = sprite.getForeColor();
        g.setColor(foreColorToAwtColor(fc));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();

        // Simple multi-line text rendering
        String[] lines = text.split("[\r\n]+");
        int textY = y + fm.getAscent();
        for (String line : lines) {
            if (textY > y + h) break;
            g.drawString(line, x, textY);
            textY += fm.getHeight();
        }
    }

    private void drawShape(Graphics2D g, RenderSprite sprite, int x, int y, int width, int height) {
        int fc = sprite.getForeColor();
        g.setColor(foreColorToAwtColor(fc));
        int w = width > 0 ? width : 50;
        int h = height > 0 ? height : 50;
        g.fillRect(x, y, w, h);
    }

    /**
     * Convert a Director foreColor value to an AWT Color.
     * If the value is > 255, it's a packed RGB int (set via sprite.color = rgb(r,g,b)).
     * If <= 255, treat as a Director palette index (approximate: 0=white, 255=black).
     */
    private static Color foreColorToAwtColor(int fc) {
        if (fc > 255) {
            // Packed RGB from rgb(r,g,b)
            return new Color(fc);
        }
        // Director palette: 0 = white, 255 = black (inverted grayscale approximation)
        int gray = 255 - fc;
        return new Color(gray, gray, gray);
    }

    /**
     * Clear the bitmap cache, forcing bitmaps to be re-decoded on next repaint.
     * Delegates to the Player's BitmapCache.
     */
    public void clearBitmapCache() {
        if (player != null) {
            player.getBitmapCache().clear();
        }
    }

    private void drawWindowBuffers(Graphics2D g, FrameSnapshot snapshot) {
        if (snapshot.windowBuffers() == null || snapshot.windowBuffers().isEmpty()) {
            return;
        }
        for (Map.Entry<String, WindowProvider.WindowBuffer> entry : snapshot.windowBuffers().entrySet()) {
            WindowProvider.WindowBuffer wb = entry.getValue();
            BufferedImage img = wb.bitmap().toBufferedImage();
            g.drawImage(img, wb.x(), wb.y(), null);
        }
    }

    private void drawDebugInfo(Graphics2D g, FrameSnapshot snapshot) {
        // Draw current frame info in corner of the stage canvas
        g.setColor(new Color(0, 0, 0, 128));
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.drawString(snapshot.debugInfo(), 5, stageHeight - 5);
    }
}
