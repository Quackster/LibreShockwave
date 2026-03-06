package com.libreshockwave;

import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.*;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scan .cct files for XMED chunks associated with cast members.
 * Detects PFR1 font data and FFFF styled text.
 */
public class XmedFontScan {

    public static void main(String[] args) throws Exception {
        String[] files = args.length > 0 ? args : new String[]{
            "C:/xampp/htdocs/dcr/14.1_b8/hh_interface.cct"
        };

        for (String filePath : files) {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("SKIP: " + path);
                continue;
            }

            byte[] data = Files.readAllBytes(path);
            DirectorFile file = DirectorFile.load(data);
            if (file == null) {
                System.out.println("FAIL: Could not parse " + path);
                continue;
            }

            System.out.println("=== " + path.getFileName() + " (" + file.getCastMembers().size() + " members) ===");

            KeyTableChunk keyTable = file.getKeyTable();
            if (keyTable == null) {
                System.out.println("  No key table!");
                continue;
            }

            int xmedFourcc = ChunkType.XMED.getFourCC();

            // Count XMED entries
            int xmedCount = 0;
            for (var entry : keyTable.entries()) {
                if (entry.fourcc() == xmedFourcc) {
                    xmedCount++;
                }
            }
            System.out.println("  Total XMED key table entries: " + xmedCount);

            // Check each member for XMED
            for (CastMemberChunk member : file.getCastMembers()) {
                var entry = keyTable.findEntry(member.id(), xmedFourcc);
                if (entry == null) continue;

                // Get the raw chunk data
                Chunk chunk = file.getChunk(entry.sectionId());
                byte[] rawData = null;
                if (chunk instanceof RawChunk raw) {
                    rawData = raw.data();
                }

                String magic = "?";
                String detail = "";
                if (rawData != null && rawData.length >= 4) {
                    magic = String.format("%c%c%c%c",
                        isPrint(rawData[0]) ? (char)rawData[0] : '.',
                        isPrint(rawData[1]) ? (char)rawData[1] : '.',
                        isPrint(rawData[2]) ? (char)rawData[2] : '.',
                        isPrint(rawData[3]) ? (char)rawData[3] : '.');

                    if (rawData[0] == 'P' && rawData[1] == 'F' && rawData[2] == 'R' && rawData[3] == '1') {
                        detail = " [PFR1 FONT]";
                        // Try to extract font name from PFR1 data
                        String fontName = extractPfr1FontName(rawData);
                        if (fontName != null) {
                            detail += " fontName='" + fontName + "'";
                        }
                    } else if (rawData[0] == 'F' && rawData[1] == 'F' && rawData[2] == 'F' && rawData[3] == 'F') {
                        detail = " [STYLED TEXT]";
                    } else if (rawData[0] == 'F' && rawData[1] == 'W' && rawData[2] == 'S') {
                        detail = " [SWF/Flash]";
                    } else if (rawData[0] == 'C' && rawData[1] == 'W' && rawData[2] == 'S') {
                        detail = " [SWF/Flash compressed]";
                    }
                }

                System.out.println("  XMED: member='" + member.name()
                    + "' type=" + member.memberType()
                    + " size=" + (rawData != null ? rawData.length : 0)
                    + " magic=" + magic + detail);
            }
        }
    }

    /**
     * Try to extract the font name from PFR1 data.
     * PFR1 structure: header + physical font section containing font ID string.
     */
    private static String extractPfr1FontName(byte[] data) {
        // Simple scan: look for readable ASCII string after the header
        // PFR1 font name is typically in the physical font section
        // The font_id field is a null-terminated string
        for (int i = 4; i < Math.min(data.length - 1, 200); i++) {
            // Look for a sequence of printable chars followed by null
            if (data[i] == 0 && i > 10) {
                // Walk back to find start of string
                int start = i - 1;
                while (start > 4 && isPrint(data[start])) start--;
                start++;
                if (i - start >= 3) {
                    return new String(data, start, i - start);
                }
            }
        }
        return null;
    }

    private static boolean isPrint(byte b) {
        return b >= 32 && b < 127;
    }
}
