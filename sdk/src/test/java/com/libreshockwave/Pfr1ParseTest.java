package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.font.BitmapFont;
import com.libreshockwave.font.Pfr1Font;
import com.libreshockwave.format.ChunkType;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Diagnostic test: parse PFR1 fonts from hh_interface.cct and verify rendering.
 */
public class Pfr1ParseTest {

    public static void main(String[] args) throws Exception {
        String filePath = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_interface.cct";
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.out.println("SKIP: " + path);
            return;
        }

        byte[] data = Files.readAllBytes(path);
        DirectorFile file = DirectorFile.load(data);
        if (file == null) {
            System.out.println("FAIL: Could not parse " + path);
            return;
        }

        KeyTableChunk keyTable = file.getKeyTable();
        if (keyTable == null) {
            System.out.println("FAIL: No key table");
            return;
        }

        int xmedFourcc = ChunkType.XMED.getFourCC();
        int fontsFound = 0;

        for (CastMemberChunk member : file.getCastMembers()) {
            var entry = keyTable.findEntry(member.id(), xmedFourcc);
            if (entry == null) continue;

            Chunk chunk = file.getChunk(entry.sectionId());
            if (!(chunk instanceof RawChunk raw)) continue;

            byte[] rawData = raw.data();
            if (rawData == null || rawData.length < 4) continue;
            if (rawData[0] != 'P' || rawData[1] != 'F' || rawData[2] != 'R' || rawData[3] != '1') continue;

            fontsFound++;
            System.out.println("=== PFR1 Font: '" + member.name() + "' (" + rawData.length + " bytes) ===");

            Pfr1Font font = Pfr1Font.parse(rawData);
            if (font == null) {
                System.out.println("  FAIL: Could not parse PFR1 data");
                continue;
            }

            System.out.println("  fontName: " + font.fontName);
            System.out.println("  outlineRes: " + font.metrics.outlineResolution);
            System.out.println("  bbox: (" + font.metrics.xMin + "," + font.metrics.yMin
                    + ")..(" + font.metrics.xMax + "," + font.metrics.yMax + ")");
            System.out.println("  ascender: " + font.metrics.ascender
                    + " descender: " + font.metrics.descender);
            System.out.println("  hasBitmapSection: " + font.metrics.hasBitmapSection);
            System.out.println("  charRecords: " + font.charRecords.size());
            System.out.println("  outlineGlyphs: " + font.glyphs.size());
            System.out.println("  bitmapGlyphs: " + font.bitmapGlyphs.size());
            System.out.println("  fontMatrix: [" + font.fontMatrix[0] + "," + font.fontMatrix[1]
                    + "," + font.fontMatrix[2] + "," + font.fontMatrix[3] + "]");

            // Print first few char records
            for (int i = 0; i < Math.min(5, font.charRecords.size()); i++) {
                Pfr1Font.CharacterRecord cr = font.charRecords.get(i);
                char ch = (cr.charCode >= 32 && cr.charCode < 127) ? (char) cr.charCode : '?';
                System.out.printf("  Char[%d]: code=%d ('%c') width=%d gpsSize=%d gpsOff=0x%X%n",
                        i, cr.charCode, ch, cr.setWidth, cr.gpsSize, cr.gpsOffset);
            }

            // Count glyphs with contours
            int withContours = 0;
            for (var g : font.glyphs.values()) {
                if (!g.contours.isEmpty()) withContours++;
            }
            System.out.println("  Outline glyphs with contours: " + withContours);

            // Test rasterization at size 9 (Habbo uses fontSize:9)
            BitmapFont bitmapFont = BitmapFont.fromPfr1(font, 9);
            if (bitmapFont != null) {
                System.out.println("  Rasterized at size 9:");
                System.out.println("    lineHeight: " + bitmapFont.getLineHeight());
                System.out.println("    'H' width: " + bitmapFont.getCharWidth('H'));
                System.out.println("    'i' width: " + bitmapFont.getCharWidth('i'));
                System.out.println("    ' ' width: " + bitmapFont.getCharWidth(' '));
                System.out.println("    \"Hello\" width: " + bitmapFont.getStringWidth("Hello"));

                // Test rendering
                int[] testBuf = new int[100 * 12];
                bitmapFont.drawChar('H', testBuf, 100, 12, 0, 0, 0xFF000000);
                int inkPixels = 0;
                for (int p : testBuf) {
                    if (((p >> 24) & 0xFF) > 0) inkPixels++;
                }
                System.out.println("    'H' rendered ink pixels: " + inkPixels);
                System.out.println("    RESULT: " + (inkPixels > 0 ? "OK" : "FAIL - no ink!"));
            } else {
                System.out.println("  FAIL: Rasterization returned null");
            }
        }

        System.out.println("\n=== Summary: " + fontsFound + " PFR1 fonts found ===");
    }
}
