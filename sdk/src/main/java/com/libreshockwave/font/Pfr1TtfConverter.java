package com.libreshockwave.font;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Converts PFR1 font data to TrueType (.ttf) byte array.
 * Used to create java.awt.Font instances for proper text rendering on desktop.
 */
public class Pfr1TtfConverter {

    public static byte[] convert(Pfr1Font font, String familyName) throws IOException {
        List<GlyphEntry> entries = new ArrayList<>();
        entries.add(new GlyphEntry(0, 0, new Pfr1Font.OutlineGlyph())); // .notdef

        int unitsPerEm = font.metrics.outlineResolution > 0 ? font.metrics.outlineResolution : 2048;

        for (var cr : font.charRecords) {
            var glyph = font.glyphs.get(cr.charCode);
            if (glyph == null) continue;
            entries.add(new GlyphEntry(cr.charCode, cr.setWidth, glyph));
        }

        Map<Integer, Integer> cmapEntries = new LinkedHashMap<>();
        for (int i = 1; i < entries.size(); i++) {
            cmapEntries.put(entries.get(i).charCode, i);
        }

        byte[] headTable = buildHead(unitsPerEm, font.metrics);
        byte[] hheaTable = buildHhea(font.metrics, entries, unitsPerEm);
        byte[] maxpTable = buildMaxp(entries.size());
        byte[] os2Table = buildOs2(font.metrics, unitsPerEm, cmapEntries);
        byte[] nameTable = buildName(familyName);
        byte[] cmapTable = buildCmap(cmapEntries);
        byte[] postTable = buildPost();

        ByteArrayOutputStream glyfBuf = new ByteArrayOutputStream();
        List<Integer> locaOffsets = new ArrayList<>();

        for (GlyphEntry entry : entries) {
            while (glyfBuf.size() % 2 != 0) glyfBuf.write(0);
            locaOffsets.add(glyfBuf.size() / 2);
            byte[] glyfData = buildGlyf(entry, unitsPerEm);
            glyfBuf.write(glyfData);
        }
        while (glyfBuf.size() % 2 != 0) glyfBuf.write(0);
        locaOffsets.add(glyfBuf.size() / 2);

        byte[] glyfTable = glyfBuf.toByteArray();
        byte[] locaTable = buildLoca(locaOffsets);
        byte[] hmtxTable = buildHmtx(entries, unitsPerEm);

        String[] tags = {"cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "OS/2", "post"};
        byte[][] tables = {cmapTable, glyfTable, headTable, hheaTable, hmtxTable, locaTable, maxpTable, nameTable, os2Table, postTable};

        return assembleTtf(tags, tables);
    }

    static byte[] assembleTtf(String[] tags, byte[][] tables) throws IOException {
        int numTables = tags.length;
        int searchRange = Integer.highestOneBit(numTables) * 16;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(numTables));
        int rangeShift = numTables * 16 - searchRange;

        int headerSize = 12 + numTables * 16;
        int[] offsets = new int[numTables];
        int currentOffset = headerSize;
        for (int i = 0; i < numTables; i++) {
            offsets[i] = currentOffset;
            currentOffset += (tables[i].length + 3) & ~3;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        dos.writeInt(0x00010000);
        dos.writeShort(numTables);
        dos.writeShort(searchRange);
        dos.writeShort(entrySelector);
        dos.writeShort(rangeShift);

        for (int i = 0; i < numTables; i++) {
            byte[] tagBytes = tags[i].getBytes(StandardCharsets.US_ASCII);
            dos.write(tagBytes);
            if (tagBytes.length < 4) dos.write(new byte[4 - tagBytes.length]);
            dos.writeInt(calcChecksum(tables[i]));
            dos.writeInt(offsets[i]);
            dos.writeInt(tables[i].length);
        }

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

    static byte[] buildHead(int unitsPerEm, Pfr1Font.FontMetrics m) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00010000);
        d.writeInt(0x00005000);
        d.writeInt(0);
        d.writeInt(0x5F0F3CF5);
        d.writeShort(0x000B);
        d.writeShort(unitsPerEm);
        d.writeLong(0);
        d.writeLong(0);
        d.writeShort(m.xMin);
        d.writeShort(m.yMin);
        d.writeShort(m.xMax);
        d.writeShort(m.yMax);
        d.writeShort(0);
        d.writeShort(8);
        d.writeShort(2);
        d.writeShort(1);
        d.writeShort(0);
        byte[] result = buf.toByteArray();
        result[50] = 0; result[51] = 0; // indexToLocFormat = 0 (short)
        return result;
    }

    static byte[] buildHhea(Pfr1Font.FontMetrics m, List<GlyphEntry> entries, int unitsPerEm) throws IOException {
        int maxAW = 0;
        for (var e : entries) maxAW = Math.max(maxAW, e.advanceWidth);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00010000);
        d.writeShort(m.ascender);
        d.writeShort(m.descender);
        d.writeShort(0);
        d.writeShort(maxAW);
        d.writeShort(m.xMin);
        d.writeShort(m.xMin);
        d.writeShort(m.xMax);
        d.writeShort(1);
        d.writeShort(0);
        d.writeShort(0);
        d.writeLong(0);
        d.writeShort(0);
        d.writeShort(entries.size());
        return buf.toByteArray();
    }

    static byte[] buildMaxp(int numGlyphs) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00010000);
        d.writeShort(numGlyphs);
        d.writeShort(256);
        d.writeShort(32);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(1);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        return buf.toByteArray();
    }

    static byte[] buildOs2(Pfr1Font.FontMetrics m, int unitsPerEm, Map<Integer, Integer> cmapEntries) throws IOException {
        int firstChar = 0x0020;
        int lastChar = 0x00FF;
        if (!cmapEntries.isEmpty()) {
            firstChar = Collections.min(cmapEntries.keySet());
            lastChar = Collections.max(cmapEntries.keySet());
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeShort(4);
        d.writeShort(unitsPerEm / 2);
        d.writeShort(400);
        d.writeShort(5);
        d.writeShort(0);
        d.writeShort(unitsPerEm / 10);
        d.writeShort(unitsPerEm / 10);
        d.writeShort(0);
        d.writeShort(unitsPerEm / 5);
        d.writeShort(unitsPerEm / 10);
        d.writeShort(unitsPerEm / 10);
        d.writeShort(0);
        d.writeShort(unitsPerEm / 3);
        d.writeShort(m.stdVW > 0 ? m.stdVW : unitsPerEm / 20);
        d.writeShort(m.ascender / 2);
        d.writeShort(0);
        d.write(new byte[10]);
        d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(0);
        d.write("    ".getBytes(StandardCharsets.US_ASCII));
        d.writeShort(0x0040);
        d.writeShort(firstChar);
        d.writeShort(Math.min(lastChar, 0xFFFF));
        d.writeShort(m.ascender);
        d.writeShort(m.descender);
        d.writeShort(0);
        d.writeShort(Math.max(m.ascender, 0));
        d.writeShort(Math.abs(Math.min(m.descender, 0)));
        d.writeInt(1);
        d.writeInt(0);
        d.writeShort(m.ascender * 8 / 10);
        d.writeShort(m.ascender);
        d.writeShort(0);
        d.writeShort(0x0020);
        d.writeShort(1);
        return buf.toByteArray();
    }

    static byte[] buildName(String familyName) throws IOException {
        String[][] names = {
            {""},
            {familyName},
            {"Regular"},
            {familyName + "-Regular"},
            {familyName},
            {"Version 1.0"},
            {familyName.replace(" ", "")}
        };

        byte[][] encoded = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            encoded[i] = names[i][0].getBytes(StandardCharsets.UTF_16BE);
        }

        int count = names.length;
        int storageOffset = 6 + count * 12;

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeShort(0);
        d.writeShort(count);
        d.writeShort(storageOffset);

        int stringOffset = 0;
        for (int i = 0; i < count; i++) {
            d.writeShort(3);
            d.writeShort(1);
            d.writeShort(0x0409);
            d.writeShort(i);
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
        List<Integer> codes = new ArrayList<>(charToGlyph.keySet());
        Collections.sort(codes);

        List<int[]> segments = new ArrayList<>();
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
        segments.add(new int[]{0xFFFF, 0xFFFF});

        int segCount = segments.size();
        int searchRange = Integer.highestOneBit(segCount) * 2;
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
        int rangeShift = segCount * 2 - searchRange;

        ByteArrayOutputStream subtable = new ByteArrayOutputStream();
        DataOutputStream sd = new DataOutputStream(subtable);

        for (var seg : segments) sd.writeShort(seg[1]);
        sd.writeShort(0);
        for (var seg : segments) sd.writeShort(seg[0]);

        List<Integer> glyphIdArrayEntries = new ArrayList<>();
        int[] idRangeOffsets = new int[segCount];
        for (int i = 0; i < segCount; i++) {
            var seg = segments.get(i);
            if (seg[0] == 0xFFFF) {
                idRangeOffsets[i] = 0;
            } else {
                int arrayStartIndex = glyphIdArrayEntries.size();
                int remainingOffsets = segCount - i;
                idRangeOffsets[i] = (remainingOffsets + arrayStartIndex) * 2;
                for (int c = seg[0]; c <= seg[1]; c++) {
                    Integer gid = charToGlyph.get(c);
                    glyphIdArrayEntries.add(gid != null ? gid : 0);
                }
            }
        }

        for (int i = 0; i < segCount; i++) {
            if (segments.get(i)[0] == 0xFFFF) sd.writeShort(1);
            else sd.writeShort(0);
        }
        for (int offset : idRangeOffsets) sd.writeShort(offset);
        for (int gid : glyphIdArrayEntries) sd.writeShort(gid);

        byte[] subtableData = subtable.toByteArray();
        int subtableLength = 14 + subtableData.length;

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);

        d.writeShort(0);
        d.writeShort(1);
        d.writeShort(3);
        d.writeShort(1);
        d.writeInt(12);
        d.writeShort(4);
        d.writeShort(subtableLength);
        d.writeShort(0);
        d.writeShort(segCount * 2);
        d.writeShort(searchRange);
        d.writeShort(entrySelector);
        d.writeShort(rangeShift);

        d.write(subtableData);

        return buf.toByteArray();
    }

    static byte[] buildPost() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        d.writeInt(0x00030000);
        d.writeInt(0);
        d.writeShort(-100);
        d.writeShort(50);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(0);
        return buf.toByteArray();
    }

    static byte[] buildHmtx(List<GlyphEntry> entries, int unitsPerEm) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        for (var entry : entries) {
            d.writeShort(entry.advanceWidth);
            d.writeShort(entry.lsb);
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
            return new byte[0];
        }

        List<List<TtPoint>> ttContours = new ArrayList<>();

        for (var contour : glyph.contours) {
            List<TtPoint> points = new ArrayList<>();
            float curX = 0, curY = 0;

            for (float[] cmd : contour.commands) {
                int type = (int) cmd[0];
                switch (type) {
                    case 0 -> {
                        if (!points.isEmpty()) {
                            ttContours.add(points);
                            points = new ArrayList<>();
                        }
                        curX = cmd[1]; curY = cmd[2];
                        points.add(new TtPoint(Math.round(curX), Math.round(curY), true));
                    }
                    case 1 -> {
                        curX = cmd[1]; curY = cmd[2];
                        points.add(new TtPoint(Math.round(curX), Math.round(curY), true));
                    }
                    case 2 -> {
                        float ex = cmd[1], ey = cmd[2];
                        float c1x = cmd[3], c1y = cmd[4];
                        float c2x = cmd[5], c2y = cmd[6];
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

        int xMin = Integer.MAX_VALUE, yMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE, yMax = Integer.MIN_VALUE;

        for (var c : ttContours) {
            for (var p : c) {
                xMin = Math.min(xMin, p.x); yMin = Math.min(yMin, p.y);
                xMax = Math.max(xMax, p.x); yMax = Math.max(yMax, p.y);
            }
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);

        d.writeShort(ttContours.size());
        d.writeShort(xMin);
        d.writeShort(yMin);
        d.writeShort(xMax);
        d.writeShort(yMax);

        int idx = -1;
        for (var c : ttContours) {
            idx += c.size();
            d.writeShort(idx);
        }

        d.writeShort(0);

        List<Integer> flags = new ArrayList<>();
        List<Integer> xCoords = new ArrayList<>();
        List<Integer> yCoords = new ArrayList<>();

        int prevX = 0, prevY = 0;
        for (var c : ttContours) {
            for (var p : c) {
                int dx = p.x - prevX;
                int dy = p.y - prevY;
                int flag = p.onCurve ? 1 : 0;

                if (dx == 0) {
                    flag |= 0x10;
                } else if (dx >= -255 && dx <= 255) {
                    flag |= 0x02;
                    if (dx > 0) flag |= 0x10;
                }

                if (dy == 0) {
                    flag |= 0x20;
                } else if (dy >= -255 && dy <= 255) {
                    flag |= 0x04;
                    if (dy > 0) flag |= 0x20;
                }

                flags.add(flag);
                xCoords.add(dx);
                yCoords.add(dy);
                prevX = p.x; prevY = p.y;
            }
        }

        for (int f : flags) d.write(f);

        for (int i = 0; i < xCoords.size(); i++) {
            int dx = xCoords.get(i);
            int f = flags.get(i);
            if ((f & 0x02) != 0) {
                d.write(Math.abs(dx));
            } else if ((f & 0x10) == 0) {
                d.writeShort(dx);
            }
        }

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

    static void cubicToQuadratic(List<TtPoint> points,
                                  float x0, float y0,
                                  float c1x, float c1y,
                                  float c2x, float c2y,
                                  float ex, float ey) {
        float m01x = (x0 + c1x) / 2, m01y = (y0 + c1y) / 2;
        float m12x = (c1x + c2x) / 2, m12y = (c1y + c2y) / 2;
        float m23x = (c2x + ex) / 2, m23y = (c2y + ey) / 2;
        float m012x = (m01x + m12x) / 2, m012y = (m01y + m12y) / 2;
        float m123x = (m12x + m23x) / 2, m123y = (m12y + m23y) / 2;
        float midx = (m012x + m123x) / 2, midy = (m012y + m123y) / 2;

        float q1x = (m01x + m012x) / 2, q1y = (m01y + m012y) / 2;
        points.add(new TtPoint(Math.round(q1x), Math.round(q1y), false));
        points.add(new TtPoint(Math.round(midx), Math.round(midy), true));

        float q2x = (m123x + m23x) / 2, q2y = (m123y + m23y) / 2;
        points.add(new TtPoint(Math.round(q2x), Math.round(q2y), false));
        points.add(new TtPoint(Math.round(ex), Math.round(ey), true));
    }

    static class GlyphEntry {
        int charCode;
        int advanceWidth;
        int lsb;
        Pfr1Font.OutlineGlyph glyph;

        GlyphEntry(int charCode, int advanceWidth, Pfr1Font.OutlineGlyph glyph) {
            this.charCode = charCode;
            this.advanceWidth = advanceWidth;
            this.glyph = glyph;

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
}
