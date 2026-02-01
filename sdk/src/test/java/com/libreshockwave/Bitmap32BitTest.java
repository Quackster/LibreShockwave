package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.BitmapDecoder;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.io.BinaryReader;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test for debugging 32-bit bitmap decoding issues.
 */
public class Bitmap32BitTest {

    public static void main(String[] args) {
        System.out.println("=== 32-bit Bitmap Decode Test ===\n");

        String testFile = "C:/Users/alexm/Downloads/src/Media_Grievous.cct";

        if (args.length > 0) {
            testFile = args[0];
        }

        analyze32BitBitmaps(testFile);

        System.out.println("\n=== Test Complete ===");
    }

    private static void analyze32BitBitmaps(String filePath) {
        System.out.println("Analyzing file: " + filePath);

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found: " + filePath);
                return;
            }

            DirectorFile file = DirectorFile.load(path);
            System.out.println("File loaded successfully");
            System.out.println("Total cast members: " + file.getCastMembers().size());

            // Count bitmaps by bit depth
            int bitmap8Count = 0;
            int bitmap16Count = 0;
            int bitmap32Count = 0;

            for (CastMemberChunk member : file.getCastMembers()) {
                if (member.isBitmap()) {
                    BitmapInfo info = BitmapInfo.parse(member.specificData());
                    switch (info.bitDepth()) {
                        case 8 -> bitmap8Count++;
                        case 16 -> bitmap16Count++;
                        case 32 -> bitmap32Count++;
                    }
                }
            }

            System.out.printf("Bitmaps: 8-bit=%d, 16-bit=%d, 32-bit=%d%n",
                bitmap8Count, bitmap16Count, bitmap32Count);

            // Test decoding first 20 32-bit bitmaps
            System.out.println("\n--- Testing 32-bit bitmap decoding ---");
            int successCount = 0;
            int failCount = 0;
            int testCount = 0;

            for (CastMemberChunk member : file.getCastMembers()) {
                if (!member.isBitmap()) continue;

                BitmapInfo info = BitmapInfo.parse(member.specificData());
                if (info.bitDepth() != 32) continue;

                testCount++; // Test all bitmaps

                var result = file.decodeBitmap(member);
                if (result.isPresent()) {
                    successCount++;

                    // Save first successful decode
                    if (successCount == 1) {
                        Bitmap bitmap = result.get();
                        String safeName = member.name().replaceAll("[^a-zA-Z0-9]", "_");
                        String outputPath = "test_32bit_" + safeName + ".png";
                        ImageIO.write(bitmap.toBufferedImage(), "PNG", new File(outputPath));
                        System.out.printf("  First bitmap saved: %s (%dx%d)%n",
                            outputPath, bitmap.getWidth(), bitmap.getHeight());
                    }
                } else {
                    failCount++;
                    if (failCount <= 5) {
                        System.out.printf("  Failed: %s (id=%d, %dx%d)%n",
                            member.name(), member.id(), info.width(), info.height());
                    }
                }
            }

            System.out.printf("\nResults: Success=%d, Failed=%d (of %d tested)%n",
                successCount, failCount, testCount);

        } catch (IOException e) {
            System.out.println("FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
