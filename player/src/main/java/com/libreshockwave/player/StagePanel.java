package com.libreshockwave.player;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Swing panel that renders the Director stage using the player-core rendering API.
 * The stage canvas maintains a fixed size and is centered within the panel.
 * Resizing the window does not affect the stage dimensions.
 */
public class StagePanel extends JPanel {

    private static final Color VIEWPORT_COLOR = new Color(48, 48, 48);
    private static final Color CANVAS_BORDER_COLOR = new Color(80, 80, 80);

    private Player player;
    private final Map<Integer, BufferedImage> bitmapCache = new ConcurrentHashMap<>();

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
        CastMemberChunk member = sprite.getCastMember();
        if (member == null) {
            return;
        }

        BufferedImage img = getCachedBitmap(member);
        if (img == null) {
            return;
        }

        int ink = sprite.getInk();

        // Apply Background Transparent ink (36): make the backColor transparent
        if (ink == 36) {
            img = applyBackgroundTransparent(img, sprite.getBackColor());
        }

        // Calculate actual position (regPoint offset)
        int drawX = x - (width > 0 ? 0 : img.getWidth() / 2);
        int drawY = y - (height > 0 ? 0 : img.getHeight() / 2);

        if (width > 0 && height > 0) {
            g.drawImage(img, x, y, width, height, null);
        } else {
            g.drawImage(img, drawX, drawY, null);
        }
    }

    /**
     * Apply Background Transparent ink: pixels matching the background color become transparent.
     * This is Director's ink type 36, commonly used for sprite compositing.
     */
    private BufferedImage applyBackgroundTransparent(BufferedImage src, int backColor) {
        // Check if we've already processed this image (cached version is ARGB)
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }

        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        // Determine the background color to make transparent.
        // In Director, backColor 255 = white (0xFFFFFF), 0 = black.
        int bgRgb;
        if (backColor > 255) {
            bgRgb = backColor & 0xFFFFFF;
        } else {
            int gray = 255 - backColor;
            bgRgb = (gray << 16) | (gray << 8) | gray;
        }

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = src.getRGB(x, y);
                int rgb = pixel & 0xFFFFFF;
                if (rgb == bgRgb) {
                    result.setRGB(x, y, 0x00000000); // Fully transparent
                } else {
                    result.setRGB(x, y, pixel | 0xFF000000); // Fully opaque
                }
            }
        }

        // Replace in cache so we don't reprocess every frame
        CastMemberChunk member = null; // We can't easily get member here, so skip cache update
        return result;
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
     * Call this when external casts are loaded so newly available bitmaps are picked up.
     */
    public void clearBitmapCache() {
        bitmapCache.clear();
    }

    private BufferedImage getCachedBitmap(CastMemberChunk member) {
        int id = member.id();
        BufferedImage cached = bitmapCache.get(id);
        if (cached != null) {
            return cached;
        }

        if (player == null) {
            return null;
        }

        // Don't re-attempt if already in the cache as null — but we no longer cache null.
        // This means we retry every repaint until the bitmap is available (e.g., cast loads).
        Optional<Bitmap> bitmap = player.decodeBitmap(member);
        if (bitmap.isPresent()) {
            BufferedImage img = bitmap.get().toBufferedImage();
            bitmapCache.put(id, img);
            return img;
        }

        // Don't cache null — retry on next repaint when cast may have loaded
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
