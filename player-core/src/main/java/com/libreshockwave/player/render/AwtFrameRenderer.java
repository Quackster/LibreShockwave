package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * AWT-based frame renderer using Java2D Graphics2D.
 * Used by the Swing player (StagePanel) and headless simulator.
 */
public final class AwtFrameRenderer {

    private AwtFrameRenderer() {}

    /**
     * Render a FrameSnapshot to a Bitmap using AWT Graphics2D.
     */
    public static Bitmap renderFrame(FrameSnapshot snapshot, int stageWidth, int stageHeight) {
        BufferedImage image = new BufferedImage(stageWidth, stageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        renderFrame(g, snapshot, stageWidth, stageHeight);
        g.dispose();

        int[] pixels = image.getRGB(0, 0, stageWidth, stageHeight, null, 0, stageWidth);
        return new Bitmap(stageWidth, stageHeight, 32, pixels);
    }

    /**
     * Render a FrameSnapshot onto an existing Graphics2D context.
     * The graphics context should already be translated to the stage origin.
     */
    public static void renderFrame(Graphics2D g, FrameSnapshot snapshot, int stageWidth, int stageHeight) {
        applyRenderingHints(g);

        // Draw stage background (or stage image if scripts have drawn on it)
        if (snapshot.stageImage() != null) {
            BufferedImage stageImg = snapshot.stageImage().toBufferedImage();
            g.drawImage(stageImg, 0, 0, null);
        } else {
            g.setColor(new Color(snapshot.backgroundColor()));
            g.fillRect(0, 0, stageWidth, stageHeight);
        }

        // Draw all sprites on top of the stage image
        for (RenderSprite sprite : snapshot.sprites()) {
            drawSprite(g, sprite);
        }
    }

    /**
     * Apply rendering hints based on RenderConfig.
     */
    public static void applyRenderingHints(Graphics2D g) {
        if (RenderConfig.isAntialias()) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
    }

    private static void drawSprite(Graphics2D g, RenderSprite sprite) {
        if (!sprite.isVisible()) {
            return;
        }

        Bitmap baked = sprite.getBakedBitmap();
        if (baked == null) {
            return;
        }

        // Apply blend (opacity) if not 100%
        Composite oldComposite = null;
        int blend = sprite.getBlend();
        if (blend >= 0 && blend < 100) {
            oldComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
        }

        BufferedImage img = baked.toBufferedImage();
        int w = sprite.getWidth() > 0 ? sprite.getWidth() : img.getWidth();
        int h = sprite.getHeight() > 0 ? sprite.getHeight() : img.getHeight();
        g.drawImage(img, sprite.getX(), sprite.getY(), w, h, null);

        if (oldComposite != null) {
            g.setComposite(oldComposite);
        }
    }
}
