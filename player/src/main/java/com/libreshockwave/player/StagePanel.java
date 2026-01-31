package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScoreChunk;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Panel that renders the Director stage and sprites.
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

        // Clear background
        DirectorFile file = player.getFile();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, getWidth(), getHeight());

        // Draw sprites for current frame
        drawSprites(g2d);

        // Draw frame info overlay (debug)
        drawDebugInfo(g2d);
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

    private void drawSprites(Graphics2D g) {
        DirectorFile file = player.getFile();
        ScoreChunk score = file.getScoreChunk();
        if (score == null) return;

        int frameIndex = player.getCurrentFrame() - 1;  // Convert to 0-indexed

        // Find all channel data for this frame
        for (ScoreChunk.FrameChannelEntry entry : score.frameData().frameChannelData()) {
            if (entry.frameIndex() == frameIndex) {
                drawSprite(g, entry.channelIndex(), entry.data());
            }
        }
    }

    private void drawSprite(Graphics2D g, int channel, ScoreChunk.ChannelData data) {
        DirectorFile file = player.getFile();

        // Skip empty sprites
        if (data.isEmpty() || data.spriteType() == 0) {
            return;
        }

        int x = data.posX();
        int y = data.posY();
        int width = data.width();
        int height = data.height();

        // Try to get the cast member
        CastMemberChunk member = file.getCastMemberByIndex(data.castLib(), data.castMember());

        if (member != null && member.isBitmap()) {
            // Draw bitmap
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
                // Draw placeholder
                drawPlaceholder(g, x, y, width, height, channel);
            }
        } else if (member != null && member.memberType() == MemberType.SHAPE) {
            // Draw shape (simplified)
            g.setColor(new Color(data.foreColor(), data.foreColor(), data.foreColor()));
            g.fillRect(x, y, width > 0 ? width : 50, height > 0 ? height : 50);
        } else {
            // Draw placeholder for unknown sprite types
            drawPlaceholder(g, x, y, width, height, channel);
        }
    }

    private BufferedImage getCachedBitmap(CastMemberChunk member) {
        int id = member.id();
        if (bitmapCache.containsKey(id)) {
            return bitmapCache.get(id);
        }

        DirectorFile file = player.getFile();
        Optional<Bitmap> bitmap = file.decodeBitmap(member);
        if (bitmap.isPresent()) {
            BufferedImage img = bitmap.get().toBufferedImage();
            bitmapCache.put(id, img);
            return img;
        }

        bitmapCache.put(id, null);
        return null;
    }

    private void drawPlaceholder(Graphics2D g, int x, int y, int width, int height, int channel) {
        int w = width > 0 ? width : 50;
        int h = height > 0 ? height : 50;

        // Draw a simple placeholder box
        g.setColor(new Color(200, 200, 200, 128));
        g.fillRect(x, y, w, h);
        g.setColor(Color.GRAY);
        g.drawRect(x, y, w, h);

        // Draw channel number
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString("ch" + channel, x + 2, y + 12);
    }

    private void drawDebugInfo(Graphics2D g) {
        // Draw current frame number in corner
        g.setColor(new Color(0, 0, 0, 128));
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));

        int frame = player.getCurrentFrame();
        int total = player.getFrameCount();
        String info = String.format("Frame %d/%d | %s", frame, total, player.getState());

        g.drawString(info, 5, getHeight() - 5);
    }
}
