package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.bitmap.Bitmap;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test for extracting bitmap cast members from Director files.
 */
public class BitmapExtractTest {

    public static void main(String[] args) {
        System.out.println("=== Bitmap Extract Test ===\n");

        String testFile = "C:/xampp/htdocs/dcr/14.1_b8/hh_entry_au.cct";
        if (args.length > 0 && !args[0].isEmpty() && !args[0].equals("sdk")) {
            testFile = args[0];
        }

        extractAllBitmaps(testFile);

        System.out.println("\n=== Bitmap Extract Test Complete ===");
    }

    private static void extractAllBitmaps(String filePath) {
        System.out.println("--- Extracting ALL bitmaps from: " + filePath + " ---");
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found: " + filePath);
                return;
            }

            DirectorFile file = DirectorFile.load(path);
            System.out.println("  File loaded successfully");

            Path outDir = Path.of("build/extracted-bitmaps");
            Files.createDirectories(outDir);

            int total = 0, success = 0, fail = 0;
            for (CastMemberChunk member : file.getCastMembers()) {
                if (!member.isBitmap()) continue;
                total++;
                try {
                    var bitmapOpt = file.decodeBitmap(member);
                    if (bitmapOpt.isPresent()) {
                        Bitmap bitmap = bitmapOpt.get();
                        String safeName = member.name().replaceAll("[^a-zA-Z0-9_.-]", "_");
                        if (safeName.isEmpty()) safeName = "unnamed_" + member.id();
                        File outputFile = outDir.resolve(safeName + ".png").toFile();
                        ImageIO.write(bitmap.toBufferedImage(), "PNG", outputFile);
                        System.out.printf("  OK: %s (%dx%d %dbit) → %s%n",
                            member.name(), bitmap.getWidth(), bitmap.getHeight(), bitmap.getBitDepth(),
                            outputFile.getName());
                        success++;
                    } else {
                        System.out.println("  FAIL: " + member.name() + " (decode returned empty)");
                        fail++;
                    }
                } catch (Exception e) {
                    System.out.println("  FAIL: " + member.name() + " (" + e.getMessage() + ")");
                    fail++;
                }
            }
            System.out.printf("  Total: %d, Success: %d, Fail: %d%n", total, success, fail);
        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
        }
    }

    private static void extractBitmapByName(String filePath, String targetName) {
        System.out.println("--- Extracting bitmap '" + targetName + "' from: " + filePath + " ---");

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found: " + filePath);
                return;
            }

            DirectorFile file = DirectorFile.load(path);
            System.out.println("  File loaded successfully");
            System.out.println("  Total cast members: " + file.getCastMembers().size());

            // Find the bitmap cast member by name
            CastMemberChunk targetMember = null;
            for (CastMemberChunk member : file.getCastMembers()) {
                if (member.isBitmap() && targetName.equalsIgnoreCase(member.name())) {
                    targetMember = member;
                    System.out.println("  Found bitmap '" + member.name() + "' (id=" + member.id() + ")");
                    break;
                }
            }

            if (targetMember == null) {
                System.out.println("  ERROR: Bitmap '" + targetName + "' not found");
                System.out.println("  Available bitmaps:");
                for (CastMemberChunk member : file.getCastMembers()) {
                    if (member.isBitmap()) {
                        System.out.println("    - '" + member.name() + "' (id=" + member.id() + ")");
                    }
                }
                return;
            }

            // Decode bitmap using DirectorFile helper
            var bitmapOpt = file.decodeBitmap(targetMember);
            if (bitmapOpt.isEmpty()) {
                System.out.println("  ERROR: Failed to decode bitmap");
                return;
            }

            Bitmap bitmap = bitmapOpt.get();
            System.out.println("  Decoded bitmap: " + bitmap);

            // Save as PNG
            String outputPath = targetName + "_extracted.png";
            File outputFile = new File(outputPath);
            ImageIO.write(bitmap.toBufferedImage(), "PNG", outputFile);

            System.out.println("  Saved to: " + outputFile.getAbsolutePath());
            System.out.println("  Bitmap Extract: PASS");

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
