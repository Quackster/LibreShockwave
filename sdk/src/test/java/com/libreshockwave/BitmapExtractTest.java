package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.player.bitmap.Bitmap;

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

        String testFile = "C:/SourceControl/habbo.dcr";
        String targetName = "Logo";

        if (args.length > 0) {
            testFile = args[0];
        }
        if (args.length > 1) {
            targetName = args[1];
        }

        extractBitmapByName(testFile, targetName);

        System.out.println("\n=== Bitmap Extract Test Complete ===");
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
