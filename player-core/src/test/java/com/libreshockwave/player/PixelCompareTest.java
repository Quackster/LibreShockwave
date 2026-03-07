package com.libreshockwave.player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Pixel-level comparison between rendered output and reference screenshot.
 * Run with: ./gradlew :player-core:runPixelCompareTest
 */
public class PixelCompareTest {

    public static void main(String[] args) throws IOException {
        File rendered = new File("build/hotel-view-diag/hotel_view.png");
        File reference = new File("C:/SourceControl/HOTEL_VIEW_BR/basilisk_wVKgoIIY6K.png");

        if (!rendered.exists()) {
            System.err.println("Rendered image not found: " + rendered.getAbsolutePath());
            return;
        }
        if (!reference.exists()) {
            System.err.println("Reference image not found: " + reference.getAbsolutePath());
            return;
        }

        BufferedImage img1 = ImageIO.read(rendered);
        BufferedImage img2 = ImageIO.read(reference);

        System.out.println("Rendered:   " + img1.getWidth() + "x" + img1.getHeight());
        System.out.println("Reference:  " + img2.getWidth() + "x" + img2.getHeight());

        int w = Math.min(img1.getWidth(), img2.getWidth());
        int h = Math.min(img1.getHeight(), img2.getHeight());

        int totalPixels = w * h;
        int exactMatch = 0;
        int closeMatch = 0;   // within ±5 per channel
        int farOff = 0;       // >5 per channel difference
        long totalDelta = 0;

        // Region stats
        int[][] regions = {
            // name, x, y, w, h
        };

        // Track worst regions in a grid
        int gridW = 20, gridH = 15;
        int cellW = w / gridW, cellH = h / gridH;
        int[][] gridDiff = new int[gridH][gridW];
        int[][] gridTotal = new int[gridH][gridW];

        // Create diff image
        BufferedImage diff = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p1 = img1.getRGB(x, y);
                int p2 = img2.getRGB(x, y);

                int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
                int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;

                int dr = Math.abs(r1 - r2);
                int dg = Math.abs(g1 - g2);
                int db = Math.abs(b1 - b2);
                int maxDiff = Math.max(dr, Math.max(dg, db));
                totalDelta += dr + dg + db;

                int gx = Math.min(x / cellW, gridW - 1);
                int gy = Math.min(y / cellH, gridH - 1);
                gridTotal[gy][gx]++;

                if (maxDiff == 0) {
                    exactMatch++;
                    diff.setRGB(x, y, 0x000000); // black = perfect match
                } else if (maxDiff <= 5) {
                    closeMatch++;
                    // dim green for close match
                    diff.setRGB(x, y, 0x003300);
                } else {
                    farOff++;
                    gridDiff[gy][gx]++;
                    // Amplify difference for visibility: red channel = magnitude
                    int vis = Math.min(255, maxDiff * 3);
                    diff.setRGB(x, y, (vis << 16) | (vis / 3 << 8));
                }
            }
        }

        System.out.println("\n=== Pixel Comparison ===");
        System.out.printf("Total pixels:   %d%n", totalPixels);
        System.out.printf("Exact match:    %d (%.2f%%)%n", exactMatch, 100.0 * exactMatch / totalPixels);
        System.out.printf("Close (±5):     %d (%.2f%%)%n", closeMatch, 100.0 * closeMatch / totalPixels);
        System.out.printf("Different (>5): %d (%.2f%%)%n", farOff, 100.0 * farOff / totalPixels);
        System.out.printf("Avg delta/px:   %.2f%n", (double) totalDelta / totalPixels);

        // Print grid heatmap
        System.out.println("\n=== Difference Heatmap (grid) ===");
        System.out.println("Each cell shows %% of pixels with diff > 5:");
        for (int gy = 0; gy < gridH; gy++) {
            StringBuilder sb = new StringBuilder();
            for (int gx = 0; gx < gridW; gx++) {
                int total = gridTotal[gy][gx];
                int bad = gridDiff[gy][gx];
                double pct = total > 0 ? 100.0 * bad / total : 0;
                if (pct == 0) sb.append("  .  ");
                else if (pct < 1) sb.append("  o  ");
                else if (pct < 5) sb.append("  *  ");
                else if (pct < 20) sb.append(" **  ");
                else if (pct < 50) sb.append(" *** ");
                else sb.append("*****");
            }
            System.out.printf("Row %2d: %s%n", gy, sb);
        }

        // Find the biggest difference regions and sample some pixels
        System.out.println("\n=== Worst Grid Cells ===");
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int total = gridTotal[gy][gx];
                int bad = gridDiff[gy][gx];
                double pct = total > 0 ? 100.0 * bad / total : 0;
                if (pct >= 5) {
                    int rx = gx * cellW;
                    int ry = gy * cellH;
                    System.out.printf("  Cell[%d,%d] @ (%d,%d)-(%d,%d): %.1f%% diff (%d/%d pixels)%n",
                        gx, gy, rx, ry, rx + cellW, ry + cellH, pct, bad, total);
                }
            }
        }

        // Sample some specific differing pixels
        System.out.println("\n=== Sample Differing Pixels (first 30 with diff>20) ===");
        int sampled = 0;
        for (int y = 0; y < h && sampled < 30; y++) {
            for (int x = 0; x < w && sampled < 30; x++) {
                int p1 = img1.getRGB(x, y);
                int p2 = img2.getRGB(x, y);
                int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
                int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;
                int maxDiff = Math.max(Math.abs(r1 - r2), Math.max(Math.abs(g1 - g2), Math.abs(b1 - b2)));
                if (maxDiff > 20) {
                    System.out.printf("  (%3d,%3d): rendered=(%3d,%3d,%3d) ref=(%3d,%3d,%3d) delta=(%+d,%+d,%+d)%n",
                        x, y, r1, g1, b1, r2, g2, b2, r1 - r2, g1 - g2, b1 - b2);
                    sampled++;
                }
            }
        }

        // Sample key coordinates to diagnose sky/spotlight issues
        System.out.println("\n=== Key Position Samples ===");
        int[][] probes = {
            // Sky only (x < 331, no entry_shape overlay, no spotlight)
            {200, 70}, {300, 70}, {200, 150}, {300, 200},
            // Sky + entry_shape overlay (x >= 331, blend=16 black)
            {400, 70}, {500, 100}, {400, 200},
            // Spotlight area (x >= 678, ADD_PIN)
            {690, 70}, {700, 100}, {710, 200}, {700, 300},
            // Just outside spotlight (x < 678)
            {660, 100}, {670, 200},
        };
        for (int[] p : probes) {
            int px = p[0], py = p[1];
            if (px < w && py < h) {
                int p1 = img1.getRGB(px, py);
                int p2 = img2.getRGB(px, py);
                int r1 = (p1 >> 16) & 0xFF, g1 = (p1 >> 8) & 0xFF, b1 = p1 & 0xFF;
                int r2 = (p2 >> 16) & 0xFF, g2 = (p2 >> 8) & 0xFF, b2 = p2 & 0xFF;
                System.out.printf("  (%3d,%3d): rendered=(%3d,%3d,%3d) ref=(%3d,%3d,%3d) delta=(%+d,%+d,%+d)%n",
                    px, py, r1, g1, b1, r2, g2, b2, r1 - r2, g1 - g2, b1 - b2);
            }
        }

        // Save diff image
        File diffFile = new File("build/hotel-view-diag/pixel_diff.png");
        ImageIO.write(diff, "PNG", diffFile);
        System.out.println("\nDiff image saved to: " + diffFile.getAbsolutePath());
    }
}
