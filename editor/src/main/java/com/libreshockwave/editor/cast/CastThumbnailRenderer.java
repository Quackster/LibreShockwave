package com.libreshockwave.editor.cast;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Renders thumbnails for cast members based on their type.
 * Renders actual bitmap previews for bitmap members
 * and type-specific icons for other member types.
 */
public class CastThumbnailRenderer {

    private static final int THUMB_SIZE = 48;

    /**
     * Create a placeholder thumbnail with a type abbreviation.
     */
    public static BufferedImage createPlaceholder(String typeAbbrev) {
        BufferedImage img = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();

        g2.setColor(new Color(240, 240, 240));
        g2.fillRect(0, 0, THUMB_SIZE, THUMB_SIZE);

        g2.setColor(Color.GRAY);
        g2.drawRect(0, 0, THUMB_SIZE - 1, THUMB_SIZE - 1);

        // Draw type abbreviation
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        String abbrev = typeAbbrev != null ? typeAbbrev : "?";
        FontMetrics fm = g2.getFontMetrics();
        int textX = (THUMB_SIZE - fm.stringWidth(abbrev)) / 2;
        int textY = (THUMB_SIZE + fm.getAscent()) / 2 - 2;
        g2.drawString(abbrev, textX, textY);

        g2.dispose();
        return img;
    }

    /**
     * Scale a full-size bitmap image to fit within the thumbnail size,
     * preserving aspect ratio.
     */
    public static BufferedImage createBitmapThumbnail(BufferedImage fullImage, int thumbSize) {
        int w = fullImage.getWidth();
        int h = fullImage.getHeight();

        double scale = Math.min((double) thumbSize / w, (double) thumbSize / h);
        int newW = Math.max(1, (int) (w * scale));
        int newH = Math.max(1, (int) (h * scale));

        BufferedImage thumb = new BufferedImage(thumbSize, thumbSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = thumb.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Center the scaled image within the thumbnail
        int x = (thumbSize - newW) / 2;
        int y = (thumbSize - newH) / 2;

        // Draw checkerboard background for transparency
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, thumbSize, thumbSize);
        g2.setColor(new Color(204, 204, 204));
        for (int cy = 0; cy < thumbSize; cy += 8) {
            for (int cx = 0; cx < thumbSize; cx += 8) {
                if ((cx / 8 + cy / 8) % 2 == 0) {
                    g2.fillRect(cx, cy, 8, 8);
                }
            }
        }

        g2.drawImage(fullImage, x, y, newW, newH, null);
        g2.dispose();
        return thumb;
    }
}
