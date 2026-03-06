package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.font.Pfr1Font;
import com.libreshockwave.format.ChunkType;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dump glyph data bytes for PFR1 font debugging.
 */
public class Pfr1GlyphDump {
    public static void main(String[] args) throws Exception {
        String filePath = "C:/xampp/htdocs/dcr/14.1_b8/hh_interface.cct";
        byte[] data = Files.readAllBytes(Path.of(filePath));
        DirectorFile file = DirectorFile.load(data);
        KeyTableChunk keyTable = file.getKeyTable();
        int xmedFourcc = ChunkType.XMED.getFourCC();

        for (CastMemberChunk member : file.getCastMembers()) {
            if (!"v".equals(member.name())) continue;

            var entry = keyTable.findEntry(member.id(), xmedFourcc);
            Chunk chunk = file.getChunk(entry.sectionId());
            byte[] rawData = ((RawChunk) chunk).data();

            Pfr1Font font = Pfr1Font.parse(rawData);
            System.out.println("Font: " + font.fontName);
            System.out.println("GPS offset: 0x" + Integer.toHexString(font.gpsOffset));
            System.out.println("GPS size: " + font.gpsSize);
            System.out.println("hasBitmap: " + font.metrics.hasBitmapSection);

            // Dump glyph data for printable ASCII chars
            for (Pfr1Font.CharacterRecord cr : font.charRecords) {
                if (cr.charCode < 32 || cr.charCode > 127) continue;
                if (cr.gpsSize <= 1) continue;

                char ch = (char) cr.charCode;
                int start = font.gpsOffset + cr.gpsOffset;
                int size = cr.gpsSize;
                if (start + size > rawData.length) continue;

                int b0 = rawData[start] & 0xFF;
                int zerosField = (b0 >> 4) & 0x07;
                int outlineFormat = (b0 >> 6) & 3;
                int countEnc = b0 & 3;

                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(size, 20); i++) {
                    hex.append(String.format("%02X ", rawData[start + i] & 0xFF));
                }

                System.out.printf("  '%c' (code=%d) size=%d b0=0x%02X outFmt=%d zeros=%d countEnc=%d data=[%s]%n",
                        ch, cr.charCode, size, b0, outlineFormat, zerosField, countEnc, hex.toString().trim());

                // Check if outline or bitmap
                if (zerosField != 0) {
                    System.out.printf("    -> potential BITMAP glyph (zeros=0x%X)%n", zerosField);
                } else {
                    System.out.printf("    -> OUTLINE glyph (outFmt=%d, countEnc=%d)%n", outlineFormat, countEnc);
                }

                // Only print first 20 chars
                if (cr.charCode > 'E') break;
            }
        }
    }
}
