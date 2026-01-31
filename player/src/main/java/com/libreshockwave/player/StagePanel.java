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
 */
public class StagePanel extends JPanel {

    private Player player;
    private final Map<Integer, BufferedImage> bitmapCache = new HashMap<>();

    public StagePanel() {
        setDoubleBuffered(true);
    }

    public void setPlayer(Player player) {
        this.player = player;
        bitmapCache.clear();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (player == null) {
            paintNoMovie(g);
            return;
        }

        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Get the frame snapshot from player-core
        FrameSnapshot snapshot = player.getFrameSnapshot();

        // Draw background
        g2d.setColor(new Color(snapshot.backgroundColor()));
        g2d.fillRect(0, 0, getWidth(), getHeight());

        // Draw all sprites
        for (RenderSprite sprite : snapshot.sprites()) {
            drawSprite(g2d, sprite);
        }

        // Draw debug info
        drawDebugInfo(g2d, snapshot);
    }

    private void paintNoMovie(Graphics g) {
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, getWidth(), getHeight());

        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        String msg = "No movie loaded";
        FontMetrics fm = g.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(msg)) / 2;
        int y = (getHeight() + fm.getAscent()) / 2;
        g.drawString(msg, x, y);
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

        if (player == null || player.getFile() == null) {
            return null;
        }

        Optional<Bitmap> bitmap = player.getFile().decodeBitmap(member);
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
        // Draw current frame info in corner
        g.setColor(new Color(0, 0, 0, 128));
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.drawString(snapshot.debugInfo(), 5, getHeight() - 5);
    }
}
