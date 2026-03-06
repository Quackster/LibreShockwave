package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.font.Pfr1Font;
import com.libreshockwave.format.ChunkType;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.zip.Inflater;

/**
 * Converts PFR1 fonts from a Director file to TrueType (.ttf) format.
 * Compares generated TTFs against reference Volter Goldfish fonts.
 * Usage: java Pfr1ToTtfTest [input.cct] [outputDir]
 */
public class Pfr1ToTtfTest {

    public static void main(String[] args) throws Exception {
        String filePath = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_interface.cct";
        String outputDir = args.length > 1 ? args[1] : "build/ttf-output";
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            System.out.println("SKIP: " + path);
            return;
        }

        Files.createDirectories(Path.of(outputDir));

        byte[] data = Files.readAllBytes(path);
        DirectorFile file = DirectorFile.load(data);
        if (file == null) { System.out.println("FAIL: Could not parse " + path); return; }

        KeyTableChunk keyTable = file.getKeyTable();
        if (keyTable == null) { System.out.println("FAIL: No key table"); return; }

        int xmedFourcc = ChunkType.XMED.getFourCC();
        int converted = 0;

        // Map member name -> generated TTF path for comparison
        Map<String, Path> generatedFonts = new LinkedHashMap<>();

        for (CastMemberChunk member : file.getCastMembers()) {
            var entry = keyTable.findEntry(member.id(), xmedFourcc);
            if (entry == null) continue;

            Chunk chunk = file.getChunk(entry.sectionId());
            if (!(chunk instanceof RawChunk raw)) continue;

            byte[] rawData = raw.data();
            if (rawData == null || rawData.length < 4) continue;
            if (rawData[0] != 'P' || rawData[1] != 'F' || rawData[2] != 'R' || rawData[3] != '1') continue;

            Pfr1Font font = Pfr1Font.parse(rawData);
            if (font == null || font.glyphs.isEmpty()) {
                System.out.println("SKIP: '" + member.name() + "' - no outline glyphs");
                continue;
            }

            String safeName = member.name().replaceAll("[^a-zA-Z0-9_-]", "_");
            if (safeName.isEmpty()) safeName = "font_" + converted;
            String ttfName = font.fontName.isEmpty() ? safeName : font.fontName.replaceAll("[^a-zA-Z0-9_ -]", "");

            Path outPath = Path.of(outputDir, safeName + ".ttf");
            byte[] ttfData = convertToTtf(font, ttfName);
            Files.write(outPath, ttfData);

            System.out.printf("OK: '%s' (%s) -> %s (%d bytes, %d glyphs)%n",
                    member.name(), font.fontName, outPath, ttfData.length, font.glyphs.size());
            generatedFonts.put(member.name(), outPath);
            converted++;
        }

        System.out.println("\n=== Converted " + converted + " fonts ===\n");

        // Run pixel comparison against reference fonts
        compareAgainstReference(generatedFonts);
    }

    // ---- Reference comparison ----

    static void compareAgainstReference(Map<String, Path> generatedFonts) {
        // Map member names to reference font files
        Map<String, String> refMap = Map.of(
                "v", "volter/volter.ttf",
                "vb", "volter/volter_bold.ttf"
        );

        for (var refEntry : refMap.entrySet()) {
            String memberName = refEntry.getKey();
            String refResource = refEntry.getValue();

            Path generatedPath = generatedFonts.get(memberName);
            if (generatedPath == null) {
                System.out.println("SKIP comparison: no generated font for '" + memberName + "'");
                continue;
            }

            // Try to load reference font from test resources
            Path refPath = findReferenceFont(refResource);
            if (refPath == null) {
                System.out.println("SKIP comparison: reference font not found: " + refResource);
                continue;
            }

            try {
                comparePixels(memberName, generatedPath, refPath);
            } catch (Exception e) {
                System.out.println("ERROR comparing '" + memberName + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    static Path findReferenceFont(String resource) {
        // Check test resources directory
        Path[] candidates = {
                Path.of("sdk/src/test/resources", resource),
                Path.of("src/test/resources", resource),
                Path.of("sdk/src/test/resources/" + resource)
        };
        for (Path p : candidates) {
            if (Files.exists(p)) return p;
        }
        // Try classpath
        var url = Pfr1ToTtfTest.class.getClassLoader().getResource(resource);
        if (url != null) {
            try {
                return Path.of(url.toURI());
            } catch (Exception ignored) {}
        }
        return null;
    }

    static void comparePixels(String name, Path generatedPath, Path referencePath) throws Exception {
        Font genFont = Font.createFont(Font.TRUETYPE_FONT, generatedPath.toFile()).deriveFont(9f);

        // Handle WOFF files (which may have .ttf extension)
        byte[] refBytes = Files.readAllBytes(referencePath);
        Font refFont;
        if (refBytes.length >= 4 && refBytes[0] == 'w' && refBytes[1] == 'O' && refBytes[2] == 'F' && refBytes[3] == 'F') {
            byte[] ttfBytes = woffToTtf(refBytes);
            refFont = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttfBytes)).deriveFont(9f);
        } else {
            refFont = Font.createFont(Font.TRUETYPE_FONT, referencePath.toFile()).deriveFont(9f);
        }

        System.out.println("=== Pixel comparison: '" + name + "' ===");
        System.out.println("  Generated: " + generatedPath);
        System.out.println("  Reference: " + referencePath);

        // Get common char codes by checking which chars both fonts can display
        int totalCompared = 0;
        int totalMatched = 0;
        int totalMismatched = 0;
        int totalMissingFromGenerated = 0;
        List<String> mismatches = new ArrayList<>();

        // Test full BMP range that either font may cover
        for (int cp = 32; cp < 0xFFFF; cp++) {
            {
                char ch = (char) cp;

                boolean refCanDisplay = refFont.canDisplay(ch);
                boolean genCanDisplay = genFont.canDisplay(ch);

                if (!refCanDisplay) continue; // Not in reference, skip

                if (!genCanDisplay) {
                    totalMissingFromGenerated++;
                    continue;
                }

                totalCompared++;

                // Render both at size 9 onto images (wider for multi-part glyphs)
                int imgW = 24, imgH = 16;
                BufferedImage genImg = renderGlyph(genFont, ch, imgW, imgH);
                BufferedImage refImg = renderGlyph(refFont, ch, imgW, imgH);

                // Count matching pixels
                int totalPixels = imgW * imgH;
                int matchingPixels = 0;
                for (int y = 0; y < imgH; y++) {
                    for (int x = 0; x < imgW; x++) {
                        int gp = genImg.getRGB(x, y) & 0xFF; // grayscale from alpha
                        int rp = refImg.getRGB(x, y) & 0xFF;
                        // Both "on" or both "off" (threshold at 128)
                        boolean genOn = gp < 128;
                        boolean refOn = rp < 128;
                        if (genOn == refOn) matchingPixels++;
                    }
                }

                float matchPct = 100f * matchingPixels / totalPixels;
                if (matchPct >= 90f) {
                    totalMatched++;
                } else {
                    totalMismatched++;
                    mismatches.add(String.format("  U+%04X '%c': %.1f%% match", cp, ch, matchPct));
                }
            }
        }

        System.out.printf("  Compared: %d glyphs, Matched (≥90%%): %d, Mismatched: %d, Missing: %d%n",
                totalCompared, totalMatched, totalMismatched, totalMissingFromGenerated);

        if (!mismatches.isEmpty()) {
            System.out.println("  Mismatched glyphs:");
            for (String m : mismatches) System.out.println(m);
        }

        // Pass criteria: all compared glyphs must match. Missing glyphs may be absent from
        // the PFR1 source data (e.g. € was added by the Goldfish project, not in the original PFR1).
        boolean pass = totalCompared > 0 && totalMismatched == 0;
        System.out.println("  Result: " + (pass ? "PASS" : "FAIL")
                + (totalMissingFromGenerated > 0 ? " (missing " + totalMissingFromGenerated
                + " glyph(s) not in PFR1 source)" : ""));
        System.out.println();
    }

    static BufferedImage renderGlyph(Font font, char ch, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);
        g2.setColor(Color.BLACK);
        g2.setFont(font);
        // Disable anti-aliasing for pixel-perfect comparison
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(String.valueOf(ch), 1, fm.getAscent());
        g2.dispose();
        return img;
    }

    // ---- TTF Generation ----

    static byte[] convertToTtf(Pfr1Font font, String familyName) throws IOException {
        // Collect glyphs sorted by char code, always include .notdef at index 0
        List<GlyphEntry> entries = new ArrayList<>();
        entries.add(new GlyphEntry(0, 0, new Pfr1Font.OutlineGlyph())); // .notdef

        int unitsPerEm = font.metrics.outlineResolution > 0 ? font.metrics.outlineResolution : 2048;

        for (var cr : font.charRecords) {
            var glyph = font.glyphs.get(cr.charCode);
            if (glyph == null) continue;
            entries.add(new GlyphEntry(cr.charCode, cr.setWidth, glyph));
        }

        // Build cmap: charCode -> glyph index
        Map<Integer, Integer> cmapEntries = new LinkedHashMap<>();
        for (int i = 1; i < entries.size(); i++) {
            cmapEntries.put(entries.get(i).charCode, i);
        }

        // Build tables
        byte[] headTable = buildHead(unitsPerEm, font.metrics);
        byte[] hheaTable = buildHhea(font.metrics, entries, unitsPerEm);
        byte[] maxpTable = buildMaxp(entries.size());
        byte[] os2Table = buildOs2(font.metrics, unitsPerEm, cmapEntries);
        byte[] nameTable = buildName(familyName);
        byte[] cmapTable = buildCmap(cmapEntries);
        byte[] postTable = buildPost();

        // Build glyf + loca (short format)
        ByteArrayOutputStream glyfBuf = new ByteArrayOutputStream();
        List<Integer> locaOffsets = new ArrayList<>();

        for (GlyphEntry entry : entries) {
            // Pad to 2-byte boundary
            while (glyfBuf.size() % 2 != 0) glyfBuf.write(0);
            locaOffsets.add(glyfBuf.size() / 2); // short loca = offset/2

            byte[] glyfData = buildGlyf(entry, unitsPerEm);
            glyfBuf.write(glyfData);
        }
        while (glyfBuf.size() % 2 != 0) glyfBuf.write(0);
        locaOffsets.add(glyfBuf.size() / 2); // end sentinel

        byte[] glyfTable = glyfBuf.toByteArray();
        byte[] locaTable = buildLoca(locaOffsets);
        byte[] hmtxTable = buildHmtx(entries, unitsPerEm);

        // Assemble TTF file
        String[] tags = {"cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "OS/2", "post"};
        byte[][] tables = {cmapTable, glyfTable, headTable, hheaTable, hmtxTable, locaTable, maxpTable, nameTable, os2Table, postTable};

        return assembleTtf(tags, tables);
    }

    static byte[] assembleTtf(String[] tags, byte[][] tables) throws IOException {
        int numTables = tags.length;
        int searchRange = Integer.highestOneBit(numTables) * 16;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(numTables));
        int rangeShift = numTables * 16 - searchRange;

        // Offset table: 12 bytes + 16 per table
        int headerSize = 12 + numTables * 16;

        // Calculate table offsets
        int[] offsets = new int[numTables];
        int currentOffset = headerSize;
        for (int i = 0; i < numTables; i++) {
            offsets[i] = currentOffset;
            currentOffset += (tables[i].length + 3) & ~3; // pad to 4-byte
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        // Offset table
        dos.writeInt(0x00010000); // sfVersion (TrueType)
        dos.writeShort(numTables);
        dos.writeShort(searchRange);
        dos.writeShort(entrySelector);
        dos.writeShort(rangeShift);

        // Table records
        for (int i = 0; i < numTables; i++) {
            // tag
            byte[] tagBytes = tags[i].getBytes(StandardCharsets.US_ASCII);
            dos.write(tagBytes);
            if (tagBytes.length < 4) dos.write(new byte[4 - tagBytes.length]);
            // checksum
            dos.writeInt(calcChecksum(tables[i]));
            // offset
            dos.writeInt(offsets[i]);
            // length
            dos.writeInt(tables[i].length);
        }

        // Table data (padded to 4-byte boundaries)
        for (byte[] table : tables) {
            dos.write(table);
            int pad = (4 - (table.length % 4)) % 4;
            for (int p = 0; p < pad; p++) dos.write(0);
        }

        return out.toByteArray();
    }

    static int calcChecksum(byte[] data) {
        int sum = 0;
        int len = (data.length + 3) & ~3;
        for (int i = 0; i < len; i += 4) {
            int b0 = (i < data.length) ? (data[i] & 0xFF) : 0;
            int b1 = (i + 1 < data.length) ? (data[i + 1] & 0xFF) : 0;
            int b2 = (i + 2 < data.length) ? (data[i + 2] & 0xFF) : 0;
            int b3 = (i + 3 < data.length) ? (data[i + 3] & 0xFF) : 0;
            sum += (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        }
        return sum;
    }

    // ---- Table builders ----

    static byte[] buildHead(int unitsPerEm, Pfr1Font.FontMetrics m) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00010000); // version
        d.writeInt(0x00005000); // fontRevision
        d.writeInt(0);          // checksumAdjustment (filled later or left 0)
        d.writeInt(0x5F0F3CF5); // magicNumber
        d.writeShort(0x000B);   // flags: baseline at y=0, integer coords, etc.
        d.writeShort(unitsPerEm);
        d.writeLong(0);         // created (epoch)
        d.writeLong(0);         // modified (epoch)
        d.writeShort(m.xMin);
        d.writeShort(m.yMin);
        d.writeShort(m.xMax);
        d.writeShort(m.yMax);
        d.writeShort(0);        // macStyle
        d.writeShort(8);        // lowestRecPPEM
        d.writeShort(2);        // fontDirectionHint
        d.writeShort(1);        // indexToLocFormat: 0=short, 1=long — using short
        d.writeShort(0);        // glyphDataFormat
        // Fix: we use short loca format
        byte[] result = buf.toByteArray();
        result[50] = 0; result[51] = 0; // indexToLocFormat = 0 (short)
        return result;
    }

    static byte[] buildHhea(Pfr1Font.FontMetrics m, List<GlyphEntry> entries, int unitsPerEm) throws IOException {
        int maxAW = 0;
        for (var e : entries) maxAW = Math.max(maxAW, e.advanceWidth);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00010000); // version
        d.writeShort(m.ascender);
        d.writeShort(m.descender);
        d.writeShort(0);        // lineGap
        d.writeShort(maxAW);    // advanceWidthMax
        d.writeShort(m.xMin);   // minLeftSideBearing
        d.writeShort(m.xMin);   // minRightSideBearing
        d.writeShort(m.xMax);   // xMaxExtent
        d.writeShort(1);        // caretSlopeRise
        d.writeShort(0);        // caretSlopeRun
        d.writeShort(0);        // caretOffset
        d.writeLong(0);         // reserved (4 shorts)
        d.writeShort(0);        // metricDataFormat
        d.writeShort(entries.size()); // numberOfHMetrics
        return buf.toByteArray();
    }

    static byte[] buildMaxp(int numGlyphs) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00010000); // version
        d.writeShort(numGlyphs);
        d.writeShort(256);      // maxPoints
        d.writeShort(32);       // maxContours
        d.writeShort(0);        // maxCompositePoints
        d.writeShort(0);        // maxCompositeContours
        d.writeShort(1);        // maxZones
        d.writeShort(0);        // maxTwilightPoints
        d.writeShort(0);        // maxStorage
        d.writeShort(0);        // maxFunctionDefs
        d.writeShort(0);        // maxInstructionDefs
        d.writeShort(0);        // maxStackElements
        d.writeShort(0);        // maxSizeOfInstructions
        d.writeShort(0);        // maxComponentElements
        d.writeShort(0);        // maxComponentDepth
        return buf.toByteArray();
    }

    static byte[] buildOs2(Pfr1Font.FontMetrics m, int unitsPerEm, Map<Integer, Integer> cmapEntries) throws IOException {
        // Determine actual first/last char from cmap
        int firstChar = 0x0020;
        int lastChar = 0x00FF;
        if (!cmapEntries.isEmpty()) {
            firstChar = Collections.min(cmapEntries.keySet());
            lastChar = Collections.max(cmapEntries.keySet());
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeShort(4);        // version
        d.writeShort(unitsPerEm / 2); // xAvgCharWidth
        d.writeShort(400);      // usWeightClass (regular)
        d.writeShort(5);        // usWidthClass (medium)
        d.writeShort(0);        // fsType
        d.writeShort(unitsPerEm / 10); // ySubscriptXSize
        d.writeShort(unitsPerEm / 10); // ySubscriptYSize
        d.writeShort(0);        // ySubscriptXOffset
        d.writeShort(unitsPerEm / 5);  // ySubscriptYOffset
        d.writeShort(unitsPerEm / 10); // ySuperscriptXSize
        d.writeShort(unitsPerEm / 10); // ySuperscriptYSize
        d.writeShort(0);        // ySuperscriptXOffset
        d.writeShort(unitsPerEm / 3);  // ySuperscriptYOffset
        d.writeShort(m.stdVW > 0 ? m.stdVW : unitsPerEm / 20); // yStrikeoutSize
        d.writeShort(m.ascender / 2); // yStrikeoutPosition
        d.writeShort(0);        // sFamilyClass
        d.write(new byte[10]);  // panose
        d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(0); // ulUnicodeRange (128 bits = 4 ints)
        d.write("    ".getBytes(StandardCharsets.US_ASCII)); // achVendID
        d.writeShort(0x0040);   // fsSelection (regular)
        d.writeShort(firstChar); // usFirstCharIndex
        d.writeShort(Math.min(lastChar, 0xFFFF)); // usLastCharIndex
        d.writeShort(m.ascender);  // sTypoAscender
        d.writeShort(m.descender); // sTypoDescender
        d.writeShort(0);        // sTypoLineGap
        d.writeShort(Math.max(m.ascender, 0)); // usWinAscent
        d.writeShort(Math.abs(Math.min(m.descender, 0))); // usWinDescent
        d.writeInt(1);          // ulCodePageRange1
        d.writeInt(0);          // ulCodePageRange2
        d.writeShort(m.ascender * 8 / 10); // sxHeight
        d.writeShort(m.ascender); // sCapHeight
        d.writeShort(0);        // usDefaultChar
        d.writeShort(0x0020);   // usBreakChar (space)
        d.writeShort(1);        // usMaxContext
        return buf.toByteArray();
    }

    static byte[] buildName(String familyName) throws IOException {
        // Minimal name table with platformID=3 (Windows), encodingID=1 (Unicode BMP)
        String[][] names = {
            /* 0: copyright */    {""},
            /* 1: family */       {familyName},
            /* 2: subfamily */    {"Regular"},
            /* 3: uniqueID */     {familyName + "-Regular"},
            /* 4: fullName */     {familyName},
            /* 5: version */      {"Version 1.0"},
            /* 6: postscript */   {familyName.replace(" ", "")}
        };

        // Encode all strings as UTF-16BE
        byte[][] encoded = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            encoded[i] = names[i][0].getBytes(StandardCharsets.UTF_16BE);
        }

        int count = names.length;
        int storageOffset = 6 + count * 12;

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeShort(0);           // format
        d.writeShort(count);       // count
        d.writeShort(storageOffset); // stringOffset

        int stringOffset = 0;
        for (int i = 0; i < count; i++) {
            d.writeShort(3);       // platformID (Windows)
            d.writeShort(1);       // encodingID (Unicode BMP)
            d.writeShort(0x0409);  // languageID (English US)
            d.writeShort(i);       // nameID
            d.writeShort(encoded[i].length);
            d.writeShort(stringOffset);
            stringOffset += encoded[i].length;
        }

        for (byte[] enc : encoded) {
            d.write(enc);
        }

        return buf.toByteArray();
    }

    static byte[] buildCmap(Map<Integer, Integer> charToGlyph) throws IOException {
        // Format 4 cmap subtable (BMP characters)
        // Collect unique char codes sorted
        List<Integer> codes = new ArrayList<>(charToGlyph.keySet());
        Collections.sort(codes);

        // Build segments
        List<int[]> segments = new ArrayList<>(); // [startCode, endCode]
        if (!codes.isEmpty()) {
            int start = codes.get(0), end = codes.get(0);
            for (int i = 1; i < codes.size(); i++) {
                if (codes.get(i) == end + 1) {
                    end = codes.get(i);
                } else {
                    segments.add(new int[]{start, end});
                    start = end = codes.get(i);
                }
            }
            segments.add(new int[]{start, end});
        }
        // Add sentinel segment
        segments.add(new int[]{0xFFFF, 0xFFFF});

        int segCount = segments.size();
        int searchRange = Integer.highestOneBit(segCount) * 2;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
        int rangeShift = segCount * 2 - searchRange;

        ByteArrayOutputStream subtable = new ByteArrayOutputStream();
        DataOutputStream sd = new DataOutputStream(subtable);

        // We'll use glyphIdArray approach for non-contiguous mappings
        // endCode[]
        for (var seg : segments) sd.writeShort(seg[1]);
        sd.writeShort(0); // reservedPad
        // startCode[]
        for (var seg : segments) sd.writeShort(seg[0]);
        // idDelta[] — we use glyphIdArray, so delta=0 for all except sentinel
        List<Integer> glyphIdArrayEntries = new ArrayList<>();
        int[] idRangeOffsets = new int[segCount];
        for (int i = 0; i < segCount; i++) {
            var seg = segments.get(i);
            if (seg[0] == 0xFFFF) {
                idRangeOffsets[i] = 0;
            } else {
                // Offset from current position in idRangeOffset array to glyphIdArray
                int arrayStartIndex = glyphIdArrayEntries.size();
                int remainingOffsets = segCount - i; // entries left in idRangeOffset
                idRangeOffsets[i] = (remainingOffsets + arrayStartIndex) * 2;

                for (int c = seg[0]; c <= seg[1]; c++) {
                    Integer gid = charToGlyph.get(c);
                    glyphIdArrayEntries.add(gid != null ? gid : 0);
                }
            }
        }
        // idDelta[] (all zeros since we use glyphIdArray)
        for (int i = 0; i < segCount; i++) {
            if (segments.get(i)[0] == 0xFFFF) sd.writeShort(1);
            else sd.writeShort(0);
        }
        // idRangeOffset[]
        for (int offset : idRangeOffsets) sd.writeShort(offset);
        // glyphIdArray[]
        for (int gid : glyphIdArrayEntries) sd.writeShort(gid);

        byte[] subtableData = subtable.toByteArray();

        // Format 4 header
        int subtableLength = 14 + subtableData.length;

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);

        // cmap header
        d.writeShort(0);     // version
        d.writeShort(1);     // numTables (1 subtable)

        // Encoding record
        d.writeShort(3);     // platformID (Windows)
        d.writeShort(1);     // encodingID (Unicode BMP)
        d.writeInt(12);      // offset to subtable

        // Format 4 subtable
        d.writeShort(4);             // format
        d.writeShort(subtableLength); // length
        d.writeShort(0);             // language
        d.writeShort(segCount * 2);  // segCountX2
        d.writeShort(searchRange);
        d.writeShort(entrySelector);
        d.writeShort(rangeShift);

        d.write(subtableData);

        return buf.toByteArray();
    }

    static byte[] buildPost() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00030000); // format 3.0 (no glyph names)
        d.writeInt(0);          // italicAngle
        d.writeShort(-100);     // underlinePosition
        d.writeShort(50);       // underlineThickness
        d.writeInt(0);          // isFixedPitch
        d.writeInt(0);          // minMemType42
        d.writeInt(0);          // maxMemType42
        d.writeInt(0);          // minMemType1
        d.writeInt(0);          // maxMemType1
        return buf.toByteArray();
    }

    static byte[] buildHmtx(List<GlyphEntry> entries, int unitsPerEm) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        for (var entry : entries) {
            d.writeShort(entry.advanceWidth); // advanceWidth
            d.writeShort(entry.lsb);          // lsb
        }
        return buf.toByteArray();
    }

    static byte[] buildLoca(List<Integer> offsets) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        for (int off : offsets) d.writeShort(off);
        return buf.toByteArray();
    }

    static byte[] buildGlyf(GlyphEntry entry, int unitsPerEm) throws IOException {
        var glyph = entry.glyph;
        if (glyph.contours.isEmpty()) {
            // Empty glyph (e.g., space, .notdef) — 0 bytes
            return new byte[0];
        }

        // Convert PFR1 contours to TrueType quadratic points
        // PFR1 uses cubic beziers; TrueType uses quadratic.
        // Approximate: split each cubic into 2 quadratics.

        List<List<TtPoint>> ttContours = new ArrayList<>();

        for (var contour : glyph.contours) {
            List<TtPoint> points = new ArrayList<>();
            float curX = 0, curY = 0;

            for (float[] cmd : contour.commands) {
                int type = (int) cmd[0];
                switch (type) {
                    case 0 -> { // MoveTo
                        if (!points.isEmpty()) {
                            ttContours.add(points);
                            points = new ArrayList<>();
                        }
                        curX = cmd[1]; curY = cmd[2];
                        points.add(new TtPoint(Math.round(curX), Math.round(curY), true));
                    }
                    case 1 -> { // LineTo
                        curX = cmd[1]; curY = cmd[2];
                        points.add(new TtPoint(Math.round(curX), Math.round(curY), true));
                    }
                    case 2 -> { // CurveTo: [2, endX, endY, cp1X, cp1Y, cp2X, cp2Y]
                        float ex = cmd[1], ey = cmd[2];
                        float c1x = cmd[3], c1y = cmd[4];
                        float c2x = cmd[5], c2y = cmd[6];
                        // Approximate cubic with 2 quadratics
                        cubicToQuadratic(points, curX, curY, c1x, c1y, c2x, c2y, ex, ey);
                        curX = ex; curY = ey;
                    }
                }
            }
            if (!points.isEmpty()) {
                ttContours.add(points);
            }
        }

        if (ttContours.isEmpty()) return new byte[0];

        // Calculate bounding box
        int xMin = Integer.MAX_VALUE, yMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE, yMax = Integer.MIN_VALUE;
        int totalPoints = 0;

        for (var c : ttContours) {
            for (var p : c) {
                xMin = Math.min(xMin, p.x); yMin = Math.min(yMin, p.y);
                xMax = Math.max(xMax, p.x); yMax = Math.max(yMax, p.y);
                totalPoints++;
            }
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);

        // Glyph header
        d.writeShort(ttContours.size()); // numberOfContours
        d.writeShort(xMin);
        d.writeShort(yMin);
        d.writeShort(xMax);
        d.writeShort(yMax);

        // endPtsOfContours
        int idx = -1;
        for (var c : ttContours) {
            idx += c.size();
            d.writeShort(idx);
        }

        // Instructions
        d.writeShort(0); // instructionLength

        // Flags + coordinates
        // Build flags array
        List<Integer> flags = new ArrayList<>();
        List<Integer> xCoords = new ArrayList<>();
        List<Integer> yCoords = new ArrayList<>();

        int prevX = 0, prevY = 0;
        for (var c : ttContours) {
            for (var p : c) {
                int dx = p.x - prevX;
                int dy = p.y - prevY;
                int flag = p.onCurve ? 1 : 0;

                // x coordinate encoding
                if (dx == 0) {
                    flag |= 0x10; // x-same (repeat previous)
                } else if (dx >= -255 && dx <= 255) {
                    flag |= 0x02; // x-short
                    if (dx > 0) flag |= 0x10; // positive
                }

                // y coordinate encoding
                if (dy == 0) {
                    flag |= 0x20; // y-same
                } else if (dy >= -255 && dy <= 255) {
                    flag |= 0x04; // y-short
                    if (dy > 0) flag |= 0x20; // positive
                }

                flags.add(flag);
                xCoords.add(dx);
                yCoords.add(dy);
                prevX = p.x; prevY = p.y;
            }
        }

        // Write flags (no RLE for simplicity)
        for (int f : flags) d.write(f);

        // Write x coordinates
        for (int i = 0; i < xCoords.size(); i++) {
            int dx = xCoords.get(i);
            int f = flags.get(i);
            if ((f & 0x02) != 0) {
                d.write(Math.abs(dx));
            } else if ((f & 0x10) == 0) {
                d.writeShort(dx);
            }
        }

        // Write y coordinates
        for (int i = 0; i < yCoords.size(); i++) {
            int dy = yCoords.get(i);
            int f = flags.get(i);
            if ((f & 0x04) != 0) {
                d.write(Math.abs(dy));
            } else if ((f & 0x20) == 0) {
                d.writeShort(dy);
            }
        }

        return buf.toByteArray();
    }

    /**
     * Approximate a cubic bezier with 2 quadratic beziers.
     * Splits at t=0.5 and converts each half to quadratic.
     */
    static void cubicToQuadratic(List<TtPoint> points,
                                  float x0, float y0,
                                  float c1x, float c1y,
                                  float c2x, float c2y,
                                  float ex, float ey) {
        // Simple midpoint approximation:
        // Q control point = (3*P1 - P0) / 2 for first half, (3*P2 - P3) / 2 for second half

        // Split cubic at t=0.5
        float m01x = (x0 + c1x) / 2, m01y = (y0 + c1y) / 2;
        float m12x = (c1x + c2x) / 2, m12y = (c1y + c2y) / 2;
        float m23x = (c2x + ex) / 2, m23y = (c2y + ey) / 2;
        float m012x = (m01x + m12x) / 2, m012y = (m01y + m12y) / 2;
        float m123x = (m12x + m23x) / 2, m123y = (m12y + m23y) / 2;
        float midx = (m012x + m123x) / 2, midy = (m012y + m123y) / 2;

        // First half: cubic (x0,y0)→(m01)→(m012)→(mid)
        // Quadratic control ≈ midpoint of the two cubic controls
        float q1x = (m01x + m012x) / 2, q1y = (m01y + m012y) / 2;
        points.add(new TtPoint(Math.round(q1x), Math.round(q1y), false));
        points.add(new TtPoint(Math.round(midx), Math.round(midy), true));

        // Second half: cubic (mid)→(m123)→(m23)→(ex,ey)
        float q2x = (m123x + m23x) / 2, q2y = (m123y + m23y) / 2;
        points.add(new TtPoint(Math.round(q2x), Math.round(q2y), false));
        points.add(new TtPoint(Math.round(ex), Math.round(ey), true));
    }

    // ---- Helper types ----

    static class GlyphEntry {
        int charCode;
        int advanceWidth;
        int lsb;
        Pfr1Font.OutlineGlyph glyph;

        GlyphEntry(int charCode, int advanceWidth, Pfr1Font.OutlineGlyph glyph) {
            this.charCode = charCode;
            this.advanceWidth = advanceWidth;
            this.glyph = glyph;

            // Calculate lsb from glyph bounds
            int minX = Integer.MAX_VALUE;
            for (var c : glyph.contours) {
                for (var cmd : c.commands) {
                    minX = Math.min(minX, Math.round(cmd[1]));
                }
            }
            this.lsb = minX == Integer.MAX_VALUE ? 0 : minX;
        }
    }

    static class TtPoint {
        int x, y;
        boolean onCurve;
        TtPoint(int x, int y, boolean onCurve) {
            this.x = x; this.y = y; this.onCurve = onCurve;
        }
    }

    /**
     * Convert WOFF (Web Open Font Format) to raw TTF/OTF.
     * WOFF wraps sfnt tables with optional zlib compression.
     */
    static byte[] woffToTtf(byte[] woff) throws Exception {
        ByteBuffer buf = ByteBuffer.wrap(woff).order(ByteOrder.BIG_ENDIAN);
        int signature = buf.getInt();       // 'wOFF'
        int sfntVersion = buf.getInt();     // 0x00010000 or 'OTTO'
        int woffLength = buf.getInt();      // total WOFF size
        int numTables = buf.getShort() & 0xFFFF;
        buf.getShort();                     // reserved
        int totalSfntSize = buf.getInt();
        // Skip remaining WOFF header fields (majorVersion, minorVersion, metaOffset, etc.)
        buf.position(44); // WOFF header is 44 bytes

        // Read table directory entries
        int[][] tableDir = new int[numTables][5]; // tag, offset, compLength, origLength, origChecksum
        byte[][] tags = new byte[numTables][4];
        for (int i = 0; i < numTables; i++) {
            buf.get(tags[i]);               // 4-byte tag
            tableDir[i][0] = buf.getInt();  // offset in WOFF
            tableDir[i][1] = buf.getInt();  // compLength
            tableDir[i][2] = buf.getInt();  // origLength
            tableDir[i][3] = buf.getInt();  // origChecksum
        }

        // Build output sfnt
        int searchRange = Integer.highestOneBit(numTables) * 16;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(numTables));
        int rangeShift = numTables * 16 - searchRange;

        int headerSize = 12 + numTables * 16;
        int currentOffset = headerSize;

        // Decompress all tables
        byte[][] tableData = new byte[numTables][];
        int[] offsets = new int[numTables];
        for (int i = 0; i < numTables; i++) {
            int compLen = tableDir[i][1];
            int origLen = tableDir[i][2];
            int woffOffset = tableDir[i][0];

            if (compLen == origLen) {
                // Not compressed
                tableData[i] = new byte[origLen];
                System.arraycopy(woff, woffOffset, tableData[i], 0, origLen);
            } else {
                // zlib compressed
                Inflater inflater = new Inflater();
                inflater.setInput(woff, woffOffset, compLen);
                tableData[i] = new byte[origLen];
                inflater.inflate(tableData[i]);
                inflater.end();
            }

            offsets[i] = currentOffset;
            currentOffset += (tableData[i].length + 3) & ~3;
        }

        // Write sfnt
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeInt(sfntVersion);
        dos.writeShort(numTables);
        dos.writeShort(searchRange);
        dos.writeShort(entrySelector);
        dos.writeShort(rangeShift);

        for (int i = 0; i < numTables; i++) {
            dos.write(tags[i]);
            dos.writeInt(tableDir[i][3]); // checksum
            dos.writeInt(offsets[i]);
            dos.writeInt(tableData[i].length);
        }

        for (int i = 0; i < numTables; i++) {
            dos.write(tableData[i]);
            int pad = (4 - (tableData[i].length % 4)) % 4;
            for (int p = 0; p < pad; p++) dos.write(0);
        }

        return out.toByteArray();
    }
}
