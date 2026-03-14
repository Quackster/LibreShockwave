package com.libreshockwave.editor.cast;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Renders thumbnails for cast members based on their type.
 * Will render actual bitmap previews for bitmap members
 * and type-specific icons for other member types.
 */
public class CastThumbnailRenderer {

    private static final int THUMB_SIZE = 48;

    /**
     * Create a placeholder thumbnail for a member type.
     */
    public static BufferedImage createPlaceholder(String memberType) {
        BufferedImage img = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();

        g2.setColor(new Color(240, 240, 240));
        g2.fillRect(0, 0, THUMB_SIZE, THUMB_SIZE);

        g2.setColor(Color.GRAY);
        g2.drawRect(0, 0, THUMB_SIZE - 1, THUMB_SIZE - 1);

        // Draw type abbreviation
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        String abbrev = memberType != null ? memberType.substring(0, Math.min(3, memberType.length())) : "?";
        FontMetrics fm = g2.getFontMetrics();
        int textX = (THUMB_SIZE - fm.stringWidth(abbrev)) / 2;
        int textY = (THUMB_SIZE + fm.getAscent()) / 2 - 2;
        g2.drawString(abbrev, textX, textY);

        g2.dispose();
        return img;
    }
}
