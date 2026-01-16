package com.libreshockwave;

import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.*;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.player.Palette;
import com.libreshockwave.player.bitmap.Bitmap;
import com.libreshockwave.player.bitmap.BitmapDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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

            // Parse bitmap info from specificData
            BitmapInfo bitmapInfo = BitmapInfo.parse(targetMember.specificData());
            System.out.println("  Bitmap info: " + bitmapInfo.width() + "x" + bitmapInfo.height() +
                ", " + bitmapInfo.bitDepth() + "-bit");

            // Use the KeyTable to find the BITD chunk for this cast member
            KeyTableChunk keyTable = file.getKeyTable();
            if (keyTable == null) {
                System.out.println("  ERROR: No KEY* table found");
                return;
            }

            // Find BITD entry for this cast member
            // Note: fourCC may be stored in different byte orders, so search by string match
            KeyTableChunk.KeyTableEntry bitdEntry = null;
            for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(targetMember.id())) {
                String fourccStr = entry.fourccString();
                // Check for both "BITD" and reversed "DTIB" (endian difference)
                if (fourccStr.equals("BITD") || fourccStr.equals("DTIB")) {
                    bitdEntry = entry;
                    break;
                }
            }

            if (bitdEntry == null) {
                System.out.println("  ERROR: No BITD chunk found for cast member " + targetMember.id());
                System.out.println("  Entries for this cast member:");
                for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(targetMember.id())) {
                    System.out.println("    - " + entry.fourccString() + " (sectionId=" + entry.sectionId() + ")");
                }
                return;
            }

            System.out.println("  Found BITD chunk at sectionId=" + bitdEntry.sectionId());

            // Get the BITD chunk
            Chunk bitdChunk = file.getChunk(bitdEntry.sectionId());
            if (!(bitdChunk instanceof BitmapChunk bitmapChunk)) {
                System.out.println("  ERROR: BITD chunk not found or wrong type");
                return;
            }

            System.out.println("  BITD data size: " + bitmapChunk.data().length + " bytes");

            // Look up the palette
            Palette palette = null;
            int paletteId = bitmapInfo.paletteId();
            System.out.println("  Palette ID: " + paletteId);

            if (paletteId < 0) {
                // Built-in palette (negative IDs)
                palette = Palette.getBuiltIn(paletteId);
                System.out.println("  Using built-in palette: " + palette.getName());
            } else if (paletteId > 0) {
                // Custom palette - look up CLUT chunk by cast member ID
                // First find the cast member for the palette
                CastMemberChunk paletteMember = null;
                for (CastMemberChunk member : file.getCastMembers()) {
                    if (member.id() == paletteId && member.memberType() == CastMemberChunk.MemberType.PALETTE) {
                        paletteMember = member;
                        break;
                    }
                }

                if (paletteMember != null) {
                    // Find CLUT chunk for this cast member
                    for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(paletteMember.id())) {
                        String fourccStr = entry.fourccString();
                        if (fourccStr.equals("CLUT") || fourccStr.equals("TULC")) {
                            Chunk clutChunk = file.getChunk(entry.sectionId());
                            if (clutChunk instanceof PaletteChunk paletteChunk) {
                                // Convert PaletteChunk to Palette
                                palette = new Palette(paletteChunk.colors(), "Custom Palette " + paletteId);
                                System.out.println("  Using custom palette from cast member " + paletteId +
                                    " (" + paletteChunk.colorCount() + " colors)");
                                break;
                            }
                        }
                    }
                }

                if (palette == null) {
                    System.out.println("  Custom palette " + paletteId + " not found, using default");
                    palette = Palette.getBuiltIn(Palette.SYSTEM_MAC);
                }
            } else {
                // paletteId == 0: use default
                palette = Palette.getBuiltIn(Palette.SYSTEM_MAC);
                System.out.println("  Using default System Mac palette");
            }

            // Decode the bitmap
            boolean bigEndian = file.getEndian() == java.nio.ByteOrder.BIG_ENDIAN;
            int directorVersion = file.getConfig() != null ? file.getConfig().directorVersion() : 500;

            Bitmap bitmap = BitmapDecoder.decode(
                bitmapChunk.data(),
                bitmapInfo.width(),
                bitmapInfo.height(),
                bitmapInfo.bitDepth(),
                palette,
                true,  // RLE compressed
                bigEndian,
                directorVersion
            );

            System.out.println("  Decoded bitmap: " + bitmap);

            // Convert to BufferedImage and save as PNG
            BufferedImage image = bitmap.toBufferedImage();
            String outputPath = targetName + "_extracted.png";
            File outputFile = new File(outputPath);
            ImageIO.write(image, "PNG", outputFile);

            System.out.println("  Saved to: " + outputFile.getAbsolutePath());
            System.out.println("  Bitmap Extract: PASS");

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
