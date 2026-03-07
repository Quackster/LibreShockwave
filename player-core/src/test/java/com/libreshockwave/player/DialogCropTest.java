package com.libreshockwave.player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Quick crop-and-diff test for the login dialog area.
 */
public class DialogCropTest {

    public static void main(String[] args) throws Exception {
        // Crop region for the dialog area
        int cx = 390, cy = 90, cw = 220, ch = 350;

        // Load images
        File refFile = new File("C:/SourceControl/HOTEL_VIEW_BR/source.png");
        File renderFile = new File("C:/SourceControl/LibreShockwave - Copy/player-core/build/hotel-view-diag/hotel_view.png");

        System.out.println("Loading reference: " + refFile);
        BufferedImage refImg = ImageIO.read(refFile);
        System.out.println("  Size: " + refImg.getWidth() + "x" + refImg.getHeight());

        System.out.println("Loading rendered: " + renderFile);
        BufferedImage renImg = ImageIO.read(renderFile);
        System.out.println("  Size: " + renImg.getWidth() + "x" + renImg.getHeight());

        // Clamp crop region to image bounds
        int rw = Math.min(cw, Math.min(refImg.getWidth() - cx, renImg.getWidth() - cx));
        int rh = Math.min(ch, Math.min(refImg.getHeight() - cy, renImg.getHeight() - cy));
        System.out.println("Crop region: x=" + cx + " y=" + cy + " w=" + rw + " h=" + rh);

        // Crop
        BufferedImage refCrop = refImg.getSubimage(cx, cy, rw, rh);
        BufferedImage renCrop = renImg.getSubimage(cx, cy, rw, rh);

        // Output directory
        File outDir = new File("C:/SourceControl/LibreShockwave - Copy/player-core/build/hotel-view-diag");
        outDir.mkdirs();

        // Save crops
        File refOut = new File(outDir, "ref_dialogs.png");
        File renOut = new File(outDir, "our_dialogs.png");
        ImageIO.write(refCrop, "png", refOut);
        System.out.println("Saved: " + refOut);
        ImageIO.write(renCrop, "png", renOut);
        System.out.println("Saved: " + renOut);

        // Save zoomed crops from the FULL images (not relative to crop)
        // login_a: back at (444,100), 212x101. Title area = top of dialog
        saveCrop(refImg, renImg, outDir, "title_a", 440, 95, 220, 50);
        // login_b: back at (444,230), 212x217. Title area
        saveCrop(refImg, renImg, outDir, "title_b", 440, 225, 220, 50);
        // Gap between login_a bottom and login_b top
        saveCrop(refImg, renImg, outDir, "gap", 440, 185, 220, 55);
        // OK button area
        saveCrop(refImg, renImg, outDir, "ok_btn", 500, 370, 120, 50);
        // login_a full dialog
        saveCrop(refImg, renImg, outDir, "login_a_full", 435, 92, 230, 120);
        // login_b full dialog
        saveCrop(refImg, renImg, outDir, "login_b_full", 435, 222, 230, 230);

        // Create difference image
        BufferedImage diff = new BufferedImage(rw, rh, BufferedImage.TYPE_BYTE_GRAY);
        long totalDiff = 0;
        int maxDiff = 0;
        int diffPixelCount = 0;

        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int rgb1 = refCrop.getRGB(x, y);
                int rgb2 = renCrop.getRGB(x, y);

                int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
                int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;

                int d = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2);
                // Scale from 0-765 to 0-255
                int gray = Math.min(255, d * 255 / 765);

                diff.getRaster().setSample(x, y, 0, gray);

                totalDiff += d;
                if (d > maxDiff) maxDiff = d;
                if (d > 0) diffPixelCount++;
            }
        }

        File diffOut = new File(outDir, "dialog_diff.png");
        ImageIO.write(diff, "png", diffOut);
        System.out.println("Saved: " + diffOut);

        // Amplified diff (10x)
        BufferedImage diffAmp = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int rgb1 = refCrop.getRGB(x, y);
                int rgb2 = renCrop.getRGB(x, y);
                int r1 = (rgb1 >> 16) & 0xFF, g1 = (rgb1 >> 8) & 0xFF, b1 = rgb1 & 0xFF;
                int r2 = (rgb2 >> 16) & 0xFF, g2 = (rgb2 >> 8) & 0xFF, b2 = rgb2 & 0xFF;
                int dr = Math.min(255, Math.abs(r1 - r2) * 10);
                int dg = Math.min(255, Math.abs(g1 - g2) * 10);
                int db = Math.min(255, Math.abs(b1 - b2) * 10);
                diffAmp.setRGB(x, y, (dr << 16) | (dg << 8) | db);
            }
        }
        ImageIO.write(diffAmp, "png", new File(outDir, "dialog_diff_amp.png"));
        System.out.println("Saved amplified diff");

        int totalPixels = rw * rh;
        double avgDiff = (double) totalDiff / totalPixels;
        System.out.println("\n--- Diff Stats ---");
        System.out.println("Total pixels: " + totalPixels);
        System.out.println("Different pixels: " + diffPixelCount + " (" + String.format("%.1f", 100.0 * diffPixelCount / totalPixels) + "%)");
        System.out.println("Max channel diff sum: " + maxDiff + " / 765");
        System.out.println("Avg channel diff sum: " + String.format("%.2f", avgDiff) + " / 765");
    }

    private static void saveCrop(BufferedImage ref, BufferedImage ren, File outDir,
                                  String name, int x, int y, int w, int h) throws Exception {
        int rw = Math.min(w, ref.getWidth() - x);
        int rh = Math.min(h, ref.getHeight() - y);
        if (x < 0 || y < 0 || rw <= 0 || rh <= 0) {
            System.out.println("Skip crop " + name + " (out of bounds)");
            return;
        }
        rw = Math.min(rw, ren.getWidth() - x);
        rh = Math.min(rh, ren.getHeight() - y);
        ImageIO.write(ref.getSubimage(x, y, rw, rh), "png", new File(outDir, "ref_" + name + ".png"));
        ImageIO.write(ren.getSubimage(x, y, rw, rh), "png", new File(outDir, "our_" + name + ".png"));
        System.out.println("Saved " + name + " crops (" + rw + "x" + rh + ")");
    }
}
