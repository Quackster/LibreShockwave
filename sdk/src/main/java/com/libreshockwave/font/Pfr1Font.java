package com.libreshockwave.font;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PFR1 (Portable Font Resource) parser.
 * Parses PFR1 font data from XMED chunks in Director files.
 * Produces outline glyphs that are rasterized to bitmap grids for text rendering.
 *
 * Ported from dirplayer-rs vm-rust/src/director/chunks/pfr1/
 */
public class Pfr1Font {

    // ---- Data types ----

    public static class CharacterRecord {
        public int charCode;
        public int setWidth;
        public int gpsSize;
        public int gpsOffset;
    }

    public static class FontMetrics {
        public int outlineResolution = 2048;
        public int metricsResolution = 2048;
        public int xMin, yMin, xMax, yMax;
        public int ascender, descender;
        public int stdVW, stdHW;
        public boolean hasBitmapSection;
        public String fontId = "";
    }

    public static class Contour {
        public final List<float[]> commands = new ArrayList<>();
        // Each command: [type, x, y] or [type, x, y, x1, y1, x2, y2]
        // type: 0=MoveTo, 1=LineTo, 2=CurveTo
        public void moveTo(float x, float y) { commands.add(new float[]{0, x, y}); }
        public void lineTo(float x, float y) { commands.add(new float[]{1, x, y}); }
        public void curveTo(float x1, float y1, float x2, float y2, float x, float y) {
            commands.add(new float[]{2, x, y, x1, y1, x2, y2});
        }
    }

    public static class OutlineGlyph {
        public int charCode;
        public float setWidth;
        public final List<Contour> contours = new ArrayList<>();
    }

    public static class BitmapGlyph {
        public int charCode;
        public int imageFormat;
        public int xPos, yPos;
        public int xSize, ySize;
        public int setWidth;
        public byte[] imageData;
    }

    // ---- Parsed font data ----
    public String fontName = "";
    public FontMetrics metrics = new FontMetrics();
    public List<CharacterRecord> charRecords = new ArrayList<>();
    public Map<Integer, OutlineGlyph> glyphs = new HashMap<>();
    public Map<Integer, BitmapGlyph> bitmapGlyphs = new HashMap<>();
    public int[] fontMatrix = {256, 0, 0, 256};
    public boolean pfrBlackPixel;
    public int gpsOffset;
    public int gpsSize;

    // ---- Parse PFR1 data ----

    public static Pfr1Font parse(byte[] data) {
        if (data == null || data.length < 58) return null;
        if (data[0] != 'P' || data[1] != 'F' || data[2] != 'R' || data[3] != '1') return null;

        Pfr1Font font = new Pfr1Font();
        try {
            font.parseHeader(data);
            font.parsePhysicalFont(data);
            font.parseGlyphs(data);
        } catch (Exception e) {
            // Parsing error - return what we have
        }
        return font;
    }

    private void parseHeader(byte[] data) {
        PfrBitReader r = new PfrBitReader(data, 4); // skip "PFR1" magic

        /* signature */ r.readU16();
        /* sig2 */ r.readU16();
        /* headerSize */ r.readU16();
        /* logFontDirSize */ int logFontDirSize = r.readU16();
        /* logFontDirOffset */ int logFontDirOffset = r.readU16();
        /* logFontMaxSize */ r.readU16();
        /* logFontSectionSize */ int logFontSectionSize = r.readU24();
        /* logFontSectionOffset */ int logFontSectionOffset = r.readU24();
        /* physFontMaxSize */ r.readU16();
        int physFontSectionSize = r.readU24();
        int physFontSectionOffset = r.readU24();
        /* gpsMaxSize */ r.readU16();
        this.gpsSize = r.readU24();
        this.gpsOffset = r.readU24();
        /* maxBlueValues */ r.readU8();
        /* maxXOrus */ r.readU8();
        /* maxYOrus */ r.readU8();
        int physFontMaxSizeHigh = r.readU8();

        int flagsByte = r.readU8();
        this.pfrBlackPixel = (flagsByte & 0x01) != 0;

        /* bctMaxSize */ r.readU24();
        /* bctSetMaxSize */ r.readU24();
        /* pftBctSetMaxSize */ r.readU24();
        int nPhysFonts = r.readU16();
        /* maxStemSnapV */ r.readU8();
        /* maxStemSnapH */ r.readU8();
        int maxChars = r.readU16();

        // Parse logical font directory to get font matrix
        parseLogicalFontDirectory(data, logFontDirSize, logFontDirOffset,
                logFontSectionSize, logFontSectionOffset, physFontMaxSizeHigh);

        // Store for physical font parsing
        this.metrics.outlineResolution = 2048; // default, overridden by physical font
    }

    private void parseLogicalFontDirectory(byte[] data, int dirSize, int dirOffset,
                                            int sectionSize, int sectionOffset,
                                            int physFontMaxSizeHigh) {
        if (dirOffset == 0 || dirSize == 0) return;

        // PFR1 with small LogFontDir: read from LogFontSection
        if (dirSize < 14) {
            if (sectionSize >= 18 && sectionOffset > 0 && sectionOffset < data.length) {
                PfrBitReader r = new PfrBitReader(data, sectionOffset);
                for (int j = 0; j < 4; j++) {
                    fontMatrix[j] = r.readI24();
                }
            }
            return;
        }

        // Large LogFontDir
        if (dirOffset >= data.length) return;
        PfrBitReader r = new PfrBitReader(data, dirOffset);
        int nLogFonts = r.readU16();
        if (nLogFonts > 0) {
            for (int j = 0; j < 4; j++) {
                fontMatrix[j] = r.readI24();
            }
        }
    }

    private int storedMaxChars; // for probe fallback

    private void parsePhysicalFont(byte[] data) {
        int physOffset = 0;
        // Re-read header to get physFontSectionOffset
        PfrBitReader hr = new PfrBitReader(data, 4);
        hr.readU16(); hr.readU16(); hr.readU16(); // sig, sig2, headerSize
        hr.readU16(); hr.readU16(); hr.readU16(); // logFontDir
        hr.readU24(); hr.readU24(); // logFontSection
        hr.readU16(); // physFontMaxSize
        int physFontSectionSize = hr.readU24();
        physOffset = hr.readU24();

        if (physOffset >= data.length) return;
        int physEnd = Math.min(data.length, physOffset + physFontSectionSize);
        if (gpsOffset > physOffset && gpsOffset <= data.length) {
            physEnd = Math.min(physEnd, gpsOffset);
        }

        // Re-read maxChars from header
        PfrBitReader hr2 = new PfrBitReader(data, 4);
        for (int i = 0; i < 22; i++) hr2.readU8(); // skip to flags area
        // Actually, let me re-parse properly
        hr2 = new PfrBitReader(data, 4);
        hr2.skip(2+2+2+2+2+2+3+3+2+3+3+2+3+3+1+1+1+1+1+3+3+3+2+1+1);
        int maxChars = hr2.readU16();
        this.storedMaxChars = maxChars;

        PfrBitReader r = new PfrBitReader(data, physOffset);

        /* fontRefNumber */ r.readU16();
        metrics.outlineResolution = r.readU16();
        if (metrics.outlineResolution == 0) metrics.outlineResolution = 2048;
        metrics.metricsResolution = r.readU16();
        if (metrics.metricsResolution == 0) metrics.metricsResolution = metrics.outlineResolution;

        metrics.xMin = (short) r.readU16();
        metrics.yMin = (short) r.readU16();
        metrics.xMax = (short) r.readU16();
        metrics.yMax = (short) r.readU16();
        metrics.ascender = metrics.yMax;
        metrics.descender = metrics.yMin;

        // 8 flag bits
        boolean extraItemsPresent = r.readBit();
        r.readBit(); // zero
        r.readBit(); // threeByteGpsOffset
        r.readBit(); // twoByteGpsSize
        r.readBit(); // asciiCodeSpecified
        boolean proportionalEscapement = r.readBit();
        r.readBit(); // twoByteCharCode
        r.readBit(); // verticalEscapement

        int standardSetWidth = 0;
        if (!proportionalEscapement) {
            standardSetWidth = (short) r.readU16();
        }

        // Extra items
        if (extraItemsPresent) {
            int nExtraItems = r.readU8();
            for (int i = 0; i < nExtraItems; i++) {
                if (r.remaining() < 2) break;
                int itemSize = r.readU8();
                int itemType = r.readU8();
                int itemStart = r.position();

                if (itemType == 1) {
                    // Bitmap section specification
                    metrics.hasBitmapSection = true;
                    r.skip(itemSize);
                } else if (itemType == 2) {
                    // FontID string
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < itemSize; j++) {
                        int ch = r.readU8();
                        if (ch == 0) break;
                        sb.append((char) ch);
                    }
                    metrics.fontId = sb.toString();
                    fontName = metrics.fontId;
                    // Skip remaining
                    int consumed = r.position() - itemStart;
                    if (consumed < itemSize) r.skip(itemSize - consumed);
                } else {
                    r.skip(itemSize);
                }
            }
        }

        // nAuxBytes (24-bit)
        int nAuxBytes = r.readU24();
        if (nAuxBytes > 0 && nAuxBytes < 10000) {
            r.skip(nAuxBytes);
        } else if (nAuxBytes >= 10000) {
            // Probe for correct position (same algorithm as dirplayer-rs)
            int probeEnd = physEnd;
            while (r.position() < probeEnd) {
                int probePos = r.position();
                int nBlueValues = r.readU8();
                int byteCounter = (nBlueValues * 2) + 6;
                int nCharsPos = r.position() + byteCounter;
                if (nCharsPos + 2 > probeEnd) {
                    r.setPosition(probePos + 1);
                    continue;
                }
                r.setPosition(nCharsPos);
                int nCharacters = r.readU16();
                if (nCharacters == maxChars) {
                    r.setPosition(probePos);
                    break;
                }
                r.setPosition(probePos + 1);
            }
        }

        // Blue values
        int nBlueValues = r.readU8();
        for (int i = 0; i < nBlueValues; i++) r.readU16(); // skip blue values
        r.readU8(); // blueFuzz
        r.readU8(); // blueScale

        // StdVW and StdHW
        metrics.stdVW = (short) r.readU16();
        metrics.stdHW = (short) r.readU16();

        // Number of characters
        int nCharacters = r.readU16();

        // Parse delta-encoded character records
        parseDeltaEncodedCharRecords(r, nCharacters, standardSetWidth);
    }

    private void parseDeltaEncodedCharRecords(PfrBitReader r, int nCharacters, int standardSetWidth) {
        int charCode = -1;
        int setWidth = standardSetWidth;
        int gSize = 0;
        int gOffset = 0;

        for (int i = 0; i < nCharacters; i++) {
            if (r.remaining() < 1) break;

            int flags = r.readU8();
            int nextGpsOffset = gOffset + gSize;

            // bits 0-1: char code delta
            int charCodeMode = flags & 0x03;
            charCode++; // unconditional +1
            switch (charCodeMode) {
                case 1 -> charCode += r.readU8();
                case 2 -> charCode += r.readU16();
            }

            // bits 2-3: set width
            int setWidthMode = (flags >> 2) & 0x03;
            switch (setWidthMode) {
                case 1 -> setWidth += r.readU8();
                case 2 -> setWidth -= r.readU8();
                case 3 -> setWidth = (short) r.readU16();
            }

            // bits 4-5: gps size
            int gpsSizeMode = (flags >> 4) & 0x03;
            switch (gpsSizeMode) {
                case 0 -> gSize = r.readU8();
                case 1 -> gSize = r.readU8() + 256;
                case 2 -> gSize = r.readU8() + 512;
                case 3 -> gSize = r.readU16();
            }

            // bits 6-7: gps offset
            int gpsOffsetMode = (flags >> 6) & 0x03;
            switch (gpsOffsetMode) {
                case 0 -> gOffset = nextGpsOffset;
                case 1 -> gOffset = nextGpsOffset + r.readU8();
                case 2 -> gOffset = r.readU16();
                case 3 -> gOffset = r.readU24();
            }

            CharacterRecord cr = new CharacterRecord();
            cr.charCode = charCode;
            cr.setWidth = setWidth;
            cr.gpsSize = gSize;
            cr.gpsOffset = gOffset;
            charRecords.add(cr);
        }
    }

    private void parseGlyphs(byte[] data) {
        if (gpsOffset + gpsSize > data.length) return;

        // Build sorted known GPS offsets for compound glyph size limiting
        int[] knownOffsets = new int[charRecords.size()];
        for (int i = 0; i < charRecords.size(); i++) {
            knownOffsets[i] = charRecords.get(i).gpsOffset;
        }
        java.util.Arrays.sort(knownOffsets);

        for (CharacterRecord cr : charRecords) {
            int charCode = cr.charCode;
            // Empty glyphs (space, control) still need width
            if (cr.gpsSize <= 1) {
                OutlineGlyph g = new OutlineGlyph();
                g.charCode = charCode;
                g.setWidth = cr.setWidth;
                glyphs.put(charCode, g);
                continue;
            }

            int start = gpsOffset + cr.gpsOffset;
            int size = cr.gpsSize;
            if (start + size > data.length) continue;

            // Bitmap parsing — validate bounds before any array access (WASM traps are uncatchable)
            if (start >= 0 && start < data.length && size >= 2 && start + size <= data.length
                    && metrics.hasBitmapSection) {
                int zerosField = (data[start] >> 4) & 0x07;
                if (zerosField != 0) {
                    try {
                        BitmapGlyph bmp = parseBitmapGlyph(data, start, size, charCode);
                        if (bmp != null) {
                            if (cr.setWidth > 0) bmp.setWidth = cr.setWidth;
                            bitmapGlyphs.put(charCode, bmp);
                        }
                    } catch (Exception e) {
                        // Bitmap parse failed — outline may still work
                    }
                }
            }

            try {
                OutlineGlyph g = parseOutlineGlyph(data, start, size, cr, knownOffsets, 0);
                if (g != null) {
                    glyphs.put(charCode, g);
                }
            } catch (Exception e) {
                // Skip problematic glyphs
            }
        }

        // Case-folding fallback: copy uppercase to empty lowercase
        for (int lc = 'a'; lc <= 'z'; lc++) {
            int uc = lc - 32;
            OutlineGlyph lcGlyph = glyphs.get(lc);
            if (lcGlyph == null || lcGlyph.contours.isEmpty()) {
                OutlineGlyph ucGlyph = glyphs.get(uc);
                if (ucGlyph != null && !ucGlyph.contours.isEmpty()) {
                    OutlineGlyph copy = new OutlineGlyph();
                    copy.charCode = lc;
                    copy.setWidth = ucGlyph.setWidth;
                    copy.contours.addAll(ucGlyph.contours);
                    glyphs.put(lc, copy);
                }
            }
        }
    }

    /**
     * Parse outline glyph - handles both simple outlines and compound glyphs.
     */
    private OutlineGlyph parseOutlineGlyph(byte[] data, int start, int size,
                                            CharacterRecord cr, int[] knownOffsets, int depth) {
        OutlineGlyph glyph = new OutlineGlyph();
        glyph.charCode = cr.charCode;
        glyph.setWidth = cr.setWidth;

        if (size <= 0 || start + size > data.length) return glyph;

        int flags = data[start] & 0xFF;
        int outlineFormat = (flags >> 6) & 3;
        boolean isCompound = outlineFormat >= 2 && (flags & 0x3F) > 0;

        if (isCompound) {
            parseCompoundGlyph(data, start, size, glyph, knownOffsets, depth);
        } else {
            parseSimpleGlyph(data, start, size, glyph);
        }

        return glyph;
    }

    /**
     * Parse a compound glyph - references sub-glyphs from the GPS section.
     */
    private void parseCompoundGlyph(byte[] data, int start, int size,
                                     OutlineGlyph glyph, int[] knownOffsets, int depth) {
        if (depth >= 8) return;

        int componentCount = data[start] & 0x3F;
        int pos = start + 1;

        // Skip extra data if bit 6 set
        if ((data[start] & 0x40) != 0 && pos + 2 <= start + size) {
            int extraCount = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
            pos += 2;
            for (int i = 0; i < extraCount; i++) {
                if (pos >= start + size) break;
                int len = data[pos] & 0xFF;
                pos += len + 2;
            }
        }

        // GPS-section-relative offset of this glyph
        int glyphGpsOffset = start - gpsOffset;
        int offsetAccumulator = glyphGpsOffset;

        for (int comp = 0; comp < componentCount; comp++) {
            if (pos >= start + size) break;

            int formatByte = data[pos++] & 0xFF;
            int xFormat = formatByte % 6;
            int yFormat = (formatByte / 6) % 6;
            int offsetFormat = formatByte / 36;

            // Parse X transform (scale, offset)
            int[] xTransform = parseTransformModulo6(data, pos, xFormat);
            int xScale = xTransform[0]; int xOffset = xTransform[1]; pos = xTransform[2];

            // Parse Y transform
            int[] yTransform = parseTransformModulo6(data, pos, yFormat);
            int yScale = yTransform[0]; int yOffset = yTransform[1]; pos = yTransform[2];

            // Parse glyph offset
            int[] glyphOffset = parseGlyphOffsetModulo6(data, pos, offsetFormat, offsetAccumulator);
            int subGlyphOffset = glyphOffset[0]; int subGlyphSize = glyphOffset[1];
            pos = glyphOffset[2]; offsetAccumulator = glyphOffset[3];

            // Compute absolute position of sub-glyph
            int absPos = gpsOffset + subGlyphOffset;
            if (absPos < 0 || absPos >= data.length) continue;

            // Determine max size for sub-glyph
            int maxSize = data.length - absPos;
            if (gpsSize > 0 && subGlyphOffset < gpsSize) {
                maxSize = Math.min(maxSize, gpsSize - subGlyphOffset);
            }
            // Use known offsets to limit size
            int nextOffset = findNextOffset(knownOffsets, subGlyphOffset);
            if (nextOffset > subGlyphOffset) {
                maxSize = Math.min(maxSize, nextOffset - subGlyphOffset);
            }

            int effectiveSize = subGlyphSize > 0 ? Math.min(subGlyphSize, maxSize) : Math.min(64, maxSize);
            if (effectiveSize <= 0) continue;

            // Create a dummy CharacterRecord for sub-glyph parsing
            CharacterRecord subCr = new CharacterRecord();
            subCr.charCode = glyph.charCode;
            subCr.setWidth = (int) glyph.setWidth;

            // Parse sub-glyph recursively
            OutlineGlyph subGlyph = parseOutlineGlyph(data, absPos, effectiveSize, subCr, knownOffsets, depth + 1);

            // Merge sub-glyph contours with offset applied
            if (subGlyph != null && !subGlyph.contours.isEmpty()) {
                float pixelScale = metrics.outlineResolution > 0 ?
                        1.0f : 1.0f;
                float xOff = xOffset * pixelScale;
                float yOff = yOffset * pixelScale;
                float xScaleF = xScale / 4096.0f;
                float yScaleF = yScale / 4096.0f;

                for (Contour srcContour : subGlyph.contours) {
                    Contour transformed = new Contour();
                    for (float[] cmd : srcContour.commands) {
                        int type = (int) cmd[0];
                        float x = cmd[1] * xScaleF + xOff;
                        float y = cmd[2] * yScaleF + yOff;
                        if (type == 0) transformed.moveTo(x, y);
                        else if (type == 1) transformed.lineTo(x, y);
                        else if (type == 2 && cmd.length >= 7) {
                            transformed.curveTo(
                                    cmd[3] * xScaleF + xOff, cmd[4] * yScaleF + yOff,
                                    cmd[5] * xScaleF + xOff, cmd[6] * yScaleF + yOff,
                                    x, y);
                        }
                    }
                    glyph.contours.add(transformed);
                }
            }
        }
    }

    /** Parse transform (scale + offset) for one axis using modulo-6 format. */
    private int[] parseTransformModulo6(byte[] data, int pos, int format) {
        int scale = 4096; // 1.0 in fixed-point
        int offset = 0;

        // Parse scale
        if (format <= 2) {
            scale = 4096;
        } else if (format == 5) {
            scale = 0;
        } else {
            // format 3 or 4: 2-byte scale
            if (pos + 2 <= data.length) {
                scale = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                pos += 2;
            }
        }

        // Parse offset
        if (format == 0 || format == 5) {
            offset = 0;
        } else if (format == 1 || format == 3) {
            if (pos < data.length) {
                offset = (byte) data[pos]; // signed
                pos++;
            }
        } else {
            // format 2 or 4: 2-byte offset
            if (pos + 2 <= data.length) {
                offset = (short) (((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF));
                pos += 2;
            }
        }

        return new int[]{scale, offset, pos};
    }

    /** Parse glyph offset using modulo-6 encoding. Returns [offset, size, newPos, newAccumulator]. */
    private int[] parseGlyphOffsetModulo6(byte[] data, int pos, int format, int accumulator) {
        int offset = 0;
        int subglyphSize = 0;

        switch (format) {
            case 0 -> {
                if (pos < data.length) {
                    int delta = data[pos++] & 0xFF;
                    subglyphSize = delta;
                    accumulator -= delta;
                    offset = accumulator;
                }
            }
            case 1 -> {
                if (pos < data.length) {
                    int delta = (data[pos++] & 0xFF) + 256;
                    subglyphSize = delta;
                    accumulator -= delta;
                    offset = accumulator;
                }
            }
            case 2 -> {
                if (pos + 2 <= data.length) {
                    int delta = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                    pos += 2;
                    subglyphSize = delta;
                    accumulator -= delta;
                    offset = accumulator;
                }
            }
            case 3 -> {
                if (pos + 3 <= data.length) {
                    int combined = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
                    pos += 3;
                    subglyphSize = combined >> 15;
                    int delta = combined & 0x7FFF;
                    offset = accumulator - delta;
                }
            }
            case 4 -> {
                if (pos + 3 <= data.length) {
                    int combined = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
                    pos += 3;
                    subglyphSize = combined >> 15;
                    offset = combined & 0x7FFF;
                }
            }
            case 5 -> {
                if (pos + 4 <= data.length) {
                    int combined = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                            | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                    pos += 4;
                    subglyphSize = (combined >> 23) & 0x1FF;
                    offset = combined & 0x7FFFFF;
                }
            }
            default -> {
                if (pos + 5 <= data.length) {
                    subglyphSize = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                    pos += 2;
                    offset = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
                    pos += 3;
                }
            }
        }

        return new int[]{offset, subglyphSize, pos, accumulator};
    }

    /** Find the next known GPS offset after the given offset. */
    private int findNextOffset(int[] sortedOffsets, int currentOffset) {
        int idx = java.util.Arrays.binarySearch(sortedOffsets, currentOffset);
        if (idx < 0) idx = -(idx + 1);
        else idx++;
        if (idx < sortedOffsets.length) return sortedOffsets[idx];
        return Integer.MAX_VALUE;
    }

    /**
     * Parse a simple (non-compound) outline glyph using nibble commands.
     */
    private void parseSimpleGlyph(byte[] data, int start, int size, OutlineGlyph glyph) {
        int flags = data[start] & 0xFF;
        int pos = start + 1;
        int countEncoding = flags & 3;

        // Parse control coordinate counts
        int xOrusCount = 0, yOrusCount = 0;
        switch (countEncoding) {
            case 0 -> { /* no control points */ }
            case 1 -> {
                if (pos < start + size) {
                    int countByte = data[pos++] & 0xFF;
                    xOrusCount = countByte & 0x0F;
                    yOrusCount = (countByte >> 4) & 0x0F;
                }
            }
            case 2, 3 -> {
                if (pos + 1 < start + size) {
                    xOrusCount = data[pos++] & 0xFF;
                    yOrusCount = data[pos++] & 0xFF;
                }
            }
        }

        // Parse control coordinate values (we need these for orus lookups)
        int[] ctrlX = new int[xOrusCount];
        int[] ctrlY = new int[yOrusCount];
        pos = parseControlValues(data, pos, flags, xOrusCount, yOrusCount, ctrlX, ctrlY);

        // Skip extra items if flag bit 3 is set
        if ((flags & 0x08) != 0 && pos < start + size) {
            int extraCount = data[pos++] & 0xFF;
            for (int i = 0; i < extraCount; i++) {
                if (pos + 1 >= start + size) break;
                int itemLen = data[pos] & 0xFF;
                pos += itemLen + 2;
            }
        }

        // Parse nibble commands
        parseNibbleCommands(data, pos, start + size, glyph, ctrlX, ctrlY);
    }

    /**
     * Parse control coordinate values using CE9D accumulative delta encoding.
     * Returns position after control values.
     */
    private int parseControlValues(byte[] data, int pos, int flags,
                                    int xCount, int yCount, int[] ctrlX, int[] ctrlY) {
        if (xCount == 0 && yCount == 0) return pos;

        boolean threeByteMode = (flags & 3) == 3;
        boolean flagPerCoord = (flags & 0x40) != 0;
        boolean nibbleAligned = false;
        int flagCache = 0, flagCacheCount = 0;

        // Parse X control values with accumulative deltas
        int accumX = 0;
        for (int i = 0; i < xCount; i++) {
            if (pos >= data.length) break;

            int v8;
            if (i == 0) {
                v8 = (flags >> 4) & 1;
            } else if (flagPerCoord) {
                // Read flag nibble with caching
                if (flagCacheCount > 0) {
                    v8 = (flagCache >> 1) & 1;
                    flagCache >>= 1;
                    flagCacheCount--;
                } else {
                    if (pos < data.length) {
                        if (nibbleAligned) {
                            int nibVal = data[pos] & 0x0F;
                            pos++;
                            nibbleAligned = false;
                            v8 = nibVal & 1;
                            flagCache = nibVal;
                            flagCacheCount = 3;
                        } else {
                            int nibVal = (data[pos] >> 4) & 0x0F;
                            nibbleAligned = true;
                            v8 = nibVal & 1;
                            flagCache = nibVal;
                            flagCacheCount = 3;
                        }
                    } else {
                        v8 = 0;
                    }
                }
            } else {
                v8 = 0;
            }

            int[] result = readCe9dCoordValue(data, pos, v8, threeByteMode, nibbleAligned);
            int delta = result[0];
            pos = result[1];
            nibbleAligned = result[2] != 0;
            accumX += delta;
            ctrlX[i] = accumX;
        }

        // Parse Y control values
        int accumY = 0;
        for (int i = 0; i < yCount; i++) {
            if (pos >= data.length) break;

            int v8;
            if (i == 0) {
                v8 = (flags >> 5) & 1;
            } else if (flagPerCoord) {
                if (flagCacheCount > 0) {
                    v8 = (flagCache >> 1) & 1;
                    flagCache >>= 1;
                    flagCacheCount--;
                } else {
                    if (pos < data.length) {
                        if (nibbleAligned) {
                            int nibVal = data[pos] & 0x0F;
                            pos++;
                            nibbleAligned = false;
                            v8 = nibVal & 1;
                            flagCache = nibVal;
                            flagCacheCount = 3;
                        } else {
                            int nibVal = (data[pos] >> 4) & 0x0F;
                            nibbleAligned = true;
                            v8 = nibVal & 1;
                            flagCache = nibVal;
                            flagCacheCount = 3;
                        }
                    } else {
                        v8 = 0;
                    }
                }
            } else {
                v8 = 0;
            }

            int[] result = readCe9dCoordValue(data, pos, v8, threeByteMode, nibbleAligned);
            int delta = result[0];
            pos = result[1];
            nibbleAligned = result[2] != 0;
            accumY += delta;
            ctrlY[i] = accumY;
        }

        if (nibbleAligned) pos++;
        return pos;
    }

    /** Read a CE9D coordinate value. Returns [value, newPos, nibbleAligned]. */
    private int[] readCe9dCoordValue(byte[] data, int pos, int v8, boolean threeByteMode, boolean nibbleAligned) {
        if (pos >= data.length) return new int[]{0, pos, nibbleAligned ? 1 : 0};

        if ((v8 & 1) == 0) {
            // Single-byte mode
            int result;
            if (nibbleAligned) {
                int lo = data[pos] & 0x0F;
                pos++;
                int hi = (pos < data.length) ? (data[pos] >> 4) & 0x0F : 0;
                result = (short) ((lo << 4) | hi);
            } else {
                result = (short) (data[pos] & 0xFF);
                pos++;
            }
            return new int[]{result, pos, nibbleAligned ? 1 : 0};
        } else {
            if (threeByteMode) {
                int result;
                if (nibbleAligned) {
                    int b0Low = (pos > 0) ? (data[pos - 1] & 0x0F) : 0;
                    int b1 = (pos < data.length) ? data[pos] & 0xFF : 0;
                    pos++;
                    int b2High = (pos < data.length) ? (data[pos] >> 4) & 0x0F : 0;
                    result = (short) ((b0Low << 12) | (b1 << 4) | b2High);
                } else {
                    int b0 = (pos < data.length) ? data[pos] & 0xFF : 0;
                    pos++;
                    int b1 = (pos < data.length) ? data[pos] & 0xFF : 0;
                    pos++;
                    result = (short) ((b0 << 8) | b1);
                }
                return new int[]{result, pos, nibbleAligned ? 1 : 0};
            } else {
                int result;
                if (nibbleAligned) {
                    int lo = data[pos] & 0x0F;
                    pos++;
                    int nextByte = (pos < data.length) ? data[pos] & 0xFF : 0;
                    pos++;
                    int shiftedNibble = (byte) (lo << 4);
                    result = (short) (nextByte + 16 * shiftedNibble);
                    nibbleAligned = false;
                } else {
                    int signedByte = (byte) data[pos];
                    pos++;
                    int nextHigh = (pos < data.length) ? (data[pos] >> 4) & 0x0F : 0;
                    result = (short) (nextHigh + 16 * signedByte);
                    nibbleAligned = true;
                }
                return new int[]{result, pos, nibbleAligned ? 1 : 0};
            }
        }
    }

    /**
     * Parse nibble-based outline commands.
     */
    private void parseNibbleCommands(byte[] data, int startPos, int endLimit,
                                      OutlineGlyph glyph, int[] ctrlX, int[] ctrlY) {
        // Use instance fields for nibble state
        int pos = startPos;
        int endPos = endLimit > 0 ? endLimit - 1 : 0;
        boolean nibbleHigh = false;
        int curX = 0, curY = 0;
        int prevX = 0, prevY = 0;
        Contour currentContour = new Contour();
        boolean firstIteration = true;
        int iterations = 0;

        while (iterations < 500) {
            iterations++;
            if (pos >= endPos && (pos != endPos || nibbleHigh)) break;
            if (pos >= data.length) break;

            int cmd;
            if (firstIteration) {
                cmd = 6;
                firstIteration = false;
            } else {
                nibbleHigh = !nibbleHigh;
                if (nibbleHigh) {
                    cmd = (data[pos] >> 4) & 0x0F;
                } else {
                    cmd = data[pos] & 0x0F;
                    pos++;
                }
            }

            int beforeX = curX, beforeY = curY;

            switch (cmd) {
                case 0 -> {
                    // Small delta - orus lookup
                    nibbleHigh = !nibbleHigh;
                    if (pos >= data.length) break;
                    int nibble;
                    if (nibbleHigh) { nibble = (data[pos] >> 4) & 0x0F; }
                    else { nibble = data[pos] & 0x0F; pos++; }

                    int direction = ((nibble & 4) != 0) ? (nibble & 7) - 8 : (nibble & 7) + 1;
                    if ((nibble & 8) != 0) {
                        curY = orusLookup(ctrlY, curY, direction);
                    } else {
                        curX = orusLookup(ctrlX, curX, direction);
                    }
                    prevX = beforeX; prevY = beforeY;
                    currentContour.lineTo(curX, curY);
                }
                case 1 -> {
                    // X byte delta
                    int delta = readByteAligned(data, pos, nibbleHigh);
                    pos = readByteAlignedPos;
                    curX = (short)(curX + (byte) delta);
                    prevX = beforeX; prevY = beforeY;
                    currentContour.lineTo(curX, curY);
                }
                case 2 -> {
                    // Y byte delta
                    int delta = readByteAligned(data, pos, nibbleHigh);
                    pos = readByteAlignedPos;
                    curY = (short)(curY + (byte) delta);
                    prevX = beforeX; prevY = beforeY;
                    currentContour.lineTo(curX, curY);
                }
                case 3 -> {
                    // X word delta (12-bit, extending to 16 if small)
                    int hiByte = readByteAligned(data, pos, nibbleHigh);
                    pos = readByteAlignedPos;
                    int hiSigned = (byte) hiByte;
                    nibbleHigh = !nibbleHigh;
                    int lo;
                    if (pos >= data.length) { lo = 0; }
                    else if (nibbleHigh) { lo = (data[pos] >> 4) & 0x0F; }
                    else { lo = data[pos] & 0x0F; pos++; }
                    int d12 = (hiSigned << 4) | lo;
                    if (d12 >= -128 && d12 < 128) {
                        int extra = readByteAligned(data, pos, nibbleHigh);
                        pos = readByteAlignedPos;
                        int d16 = (d12 << 8) | (extra & 0xFF);
                        curX += (short) d16;
                    } else {
                        curX += (short) d12;
                    }
                    prevX = beforeX; prevY = beforeY;
                    currentContour.lineTo(curX, curY);
                }
                case 4 -> {
                    // Y word delta (12-bit, extending to 16 if small)
                    int hiByte = readByteAligned(data, pos, nibbleHigh);
                    pos = readByteAlignedPos;
                    int hiSigned = (byte) hiByte;
                    nibbleHigh = !nibbleHigh;
                    int lo;
                    if (pos >= data.length) { lo = 0; }
                    else if (nibbleHigh) { lo = (data[pos] >> 4) & 0x0F; }
                    else { lo = data[pos] & 0x0F; pos++; }
                    int d12 = (hiSigned << 4) | lo;
                    if (d12 >= -128 && d12 < 128) {
                        int extra = readByteAligned(data, pos, nibbleHigh);
                        pos = readByteAlignedPos;
                        int d16 = (d12 << 8) | (extra & 0xFF);
                        curY += (short) d16;
                    } else {
                        curY += (short) d12;
                    }
                    prevX = beforeX; prevY = beforeY;
                    currentContour.lineTo(curX, curY);
                }
                case 5 -> {
                    // LineTo with encoded coords
                    nibbleHigh = !nibbleHigh;
                    if (pos >= data.length) break;
                    int enc;
                    if (nibbleHigh) { enc = (data[pos] >> 4) & 0x0F; }
                    else { enc = data[pos] & 0x0F; pos++; }

                    int[] r = readEncodedCoords(data, pos, enc, curX, curY, nibbleHigh, ctrlX, ctrlY);
                    curX = r[0]; curY = r[1]; pos = r[2]; nibbleHigh = r[3] != 0;
                    prevX = beforeX; prevY = beforeY;
                    currentContour.lineTo(curX, curY);
                }
                case 6 -> {
                    // MoveTo (new contour)
                    nibbleHigh = !nibbleHigh;
                    if (pos >= data.length) break;
                    int enc;
                    if (nibbleHigh) { enc = (data[pos] >> 4) & 0x0F; }
                    else { enc = data[pos] & 0x0F; pos++; }

                    int[] r = readEncodedCoords(data, pos, enc, curX, curY, nibbleHigh, ctrlX, ctrlY);
                    curX = r[0]; curY = r[1]; pos = r[2]; nibbleHigh = r[3] != 0;
                    prevX = beforeX; prevY = beforeY;

                    if (!currentContour.commands.isEmpty()) {
                        glyph.contours.add(currentContour);
                        currentContour = new Contour();
                    }
                    currentContour.moveTo(curX, curY);
                }
                default -> {
                    // Commands 7-15 (curves) - approximate as line to endpoint
                    if (cmd >= 7) {
                        // Skip curve data by reading encoded coordinate pairs
                        for (int i = 0; i < 3; i++) {
                            nibbleHigh = !nibbleHigh;
                            if (pos >= data.length) break;
                            int enc;
                            if (nibbleHigh) { enc = (data[pos] >> 4) & 0x0F; }
                            else { enc = data[pos] & 0x0F; pos++; }
                            int[] r = readEncodedCoords(data, pos, enc, curX, curY, nibbleHigh, ctrlX, ctrlY);
                            curX = r[0]; curY = r[1]; pos = r[2]; nibbleHigh = r[3] != 0;
                        }
                        prevX = beforeX; prevY = beforeY;
                        currentContour.lineTo(curX, curY);
                    }
                }
            }

            if (Math.abs(curX - beforeX) > 8192 || Math.abs(curY - beforeY) > 8192) {
                curX = beforeX; curY = beforeY;
            }
            if (currentContour.commands.size() > 300) break;
        }

        if (!currentContour.commands.isEmpty()) {
            glyph.contours.add(currentContour);
        }
    }

    // Temp storage for readByteAligned result position
    private int readByteAlignedPos;

    private int readByteAligned(byte[] data, int pos, boolean nibbleHigh) {
        if (pos >= data.length) { readByteAlignedPos = pos; return 0; }
        if (nibbleHigh) {
            int lo = data[pos] & 0x0F;
            pos++;
            int hi = (pos < data.length) ? (data[pos] >> 4) & 0x0F : 0;
            readByteAlignedPos = pos;
            return (lo << 4) | hi;
        } else {
            int val = data[pos] & 0xFF;
            readByteAlignedPos = pos + 1;
            return val;
        }
    }

    /** Orus lookup: control point coordinate lookup matching dirplayer-rs. */
    private static int orusLookup(int[] ctrl, int current, int direction) {
        if (ctrl.length == 0) return current;
        if (direction == 0) return current;

        if (direction > 0) {
            // Forward: find first ctrl > current
            int v9 = 0;
            while (v9 < ctrl.length && ctrl[v9] <= current) v9++;
            if (v9 >= ctrl.length) return current;
            int v8 = Math.min(v9 + direction - 1, ctrl.length - 1);
            if (v8 < 0) v8 = 0;
            return ctrl[v8];
        } else {
            // Backward: find last ctrl < current
            for (int i = ctrl.length - 1; i >= 0; i--) {
                if (ctrl[i] < current) {
                    int v8 = Math.max(i + direction + 1, 0);
                    return ctrl[v8];
                }
            }
            return current;
        }
    }

    /** Read encoded coordinate pair. Returns [x, y, newPos, nibbleHigh, prevX, prevY]. */
    private int[] readEncodedCoords(byte[] data, int pos, int enc,
                                     int curX, int curY, boolean nibbleHigh,
                                     int[] ctrlX, int[] ctrlY) {
        int xEnc = enc & 3;
        int yEnc = (enc >> 2) & 3;

        int x = curX, y = curY;
        int prevX = curX, prevY = curY;

        if (xEnc != 0) {
            int[] r = readEncodedCoordValue(data, pos, xEnc, nibbleHigh, curX, ctrlX);
            x = r[0]; pos = r[1]; nibbleHigh = r[2] != 0;
        }
        // Update curX before reading Y (matching reference)
        prevX = curX;
        curX = x;

        if (yEnc != 0) {
            int[] r = readEncodedCoordValue(data, pos, yEnc, nibbleHigh, curY, ctrlY);
            y = r[0]; pos = r[1]; nibbleHigh = r[2] != 0;
        }
        prevY = curY;

        return new int[]{x, y, pos, nibbleHigh ? 1 : 0};
    }

    /** Read a single encoded coordinate value. Returns [value, newPos, nibbleHigh]. */
    private int[] readEncodedCoordValue(byte[] data, int pos, int enc, boolean nibbleHigh,
                                         int current, int[] ctrl) {
        switch (enc) {
            case 1 -> {
                // Nibble delta: current + nibble - 8
                nibbleHigh = !nibbleHigh;
                if (pos >= data.length) return new int[]{current, pos, nibbleHigh ? 1 : 0};
                int nib;
                if (nibbleHigh) { nib = (data[pos] >> 4) & 0x0F; }
                else { nib = data[pos] & 0x0F; pos++; }
                int result = (short)(current + nib - 8);
                return new int[]{result, pos, nibbleHigh ? 1 : 0};
            }
            case 2 -> {
                // Byte value: orus lookup if small, else byte delta
                int b = readByteAligned(data, pos, nibbleHigh);
                pos = readByteAlignedPos;
                byte sb = (byte) b;
                if (sb >= -8 && sb < 8) {
                    int direction = (sb & 0x80) == 0 ? sb + 1 : sb;
                    return new int[]{orusLookup(ctrl, current, direction), pos, nibbleHigh ? 1 : 0};
                } else {
                    return new int[]{(short)(current + sb), pos, nibbleHigh ? 1 : 0};
                }
            }
            case 3 -> {
                // 12/16-bit signed delta
                int hi = readByteAligned(data, pos, nibbleHigh);
                pos = readByteAlignedPos;
                nibbleHigh = !nibbleHigh;
                int lo;
                if (pos >= data.length) { lo = 0; }
                else if (nibbleHigh) { lo = (data[pos] >> 4) & 0x0F; }
                else { lo = data[pos] & 0x0F; pos++; }
                int d12 = ((byte) hi << 4) | lo;
                if (d12 >= -128 && d12 < 128) {
                    // Read extra byte for 16-bit delta
                    int extra = readByteAligned(data, pos, nibbleHigh);
                    pos = readByteAlignedPos;
                    int d16 = (d12 << 8) | (extra & 0xFF);
                    return new int[]{(short)(current + d16), pos, nibbleHigh ? 1 : 0};
                } else {
                    return new int[]{(short)(current + d12), pos, nibbleHigh ? 1 : 0};
                }
            }
            default -> {
                return new int[]{current, pos, nibbleHigh ? 1 : 0};
            }
        }
    }

    // ---- Bitmap Glyph Parser ----

    private BitmapGlyph parseBitmapGlyph(byte[] data, int start, int size, int charCode) {
        if (size < 2) return null;
        int end = start + size;

        int pos = start;
        if (pos >= data.length) return null;
        int formatByte = data[pos++] & 0xFF;

        int imageFormat = (formatByte >> 6) & 0x03;
        int escapementFormat = (formatByte >> 4) & 0x03;
        int sizeFormat = (formatByte >> 2) & 0x03;
        int positionFormat = formatByte & 0x03;

        // Calculate bytes needed for position fields
        int posBytes = positionFormat == 0 ? 0 : positionFormat == 1 ? 2 : positionFormat == 2 ? 4 : 8;
        // Calculate bytes needed for size fields
        int sizeBytes = sizeFormat == 0 ? 2 : sizeFormat == 1 ? 4 : sizeFormat == 2 ? 6 : 8;
        // Calculate min bytes for escapement
        int escBytes = escapementFormat == 0 ? 0 : escapementFormat == 1 ? 1 : escapementFormat == 2 ? 2 : 4;

        if (pos + posBytes + sizeBytes + escBytes > end || pos + posBytes + sizeBytes + escBytes > data.length) {
            return null;
        }

        int xPos = 0, yPos = 0;
        switch (positionFormat) {
            case 0 -> { /* 0, 0 */ }
            case 1 -> { xPos = readSignedN(data, pos, 1); pos += 1; yPos = readSignedN(data, pos, 1); pos += 1; }
            case 2 -> { xPos = readSignedN(data, pos, 2); pos += 2; yPos = readSignedN(data, pos, 2); pos += 2; }
            default -> { xPos = readSignedN(data, pos, 4); pos += 4; yPos = readSignedN(data, pos, 4); pos += 4; }
        }

        int xSize = 0, ySize = 0;
        switch (sizeFormat) {
            case 0 -> { xSize = readUnsignedN(data, pos, 1); pos += 1; ySize = readUnsignedN(data, pos, 1); pos += 1; }
            case 1 -> { xSize = readUnsignedN(data, pos, 2); pos += 2; ySize = readUnsignedN(data, pos, 2); pos += 2; }
            case 2 -> { xSize = readUnsignedN(data, pos, 3); pos += 3; ySize = readUnsignedN(data, pos, 3); pos += 3; }
            default -> { xSize = readUnsignedN(data, pos, 4); pos += 4; ySize = readUnsignedN(data, pos, 4); pos += 4; }
        }

        int setWidth;
        switch (escapementFormat) {
            case 0 -> setWidth = xSize;
            case 1 -> { setWidth = readUnsignedN(data, pos, 1); pos += 1; }
            case 2 -> { setWidth = readUnsignedN(data, pos, 2); pos += 2; }
            default -> { setWidth = readUnsignedN(data, pos, 4); pos += 4; }
        }

        if (xSize <= 0 || ySize <= 0 || xSize > 4096 || ySize > 4096) return null;

        int totalBits = xSize * ySize;
        if (totalBits <= 0 || totalBits > 1_000_000) return null;

        int remaining = end - pos;
        if (remaining <= 0 || pos > data.length) return null;

        byte[] imageData;
        if (imageFormat == 0) {
            // Packed bits
            int expected = (totalBits + 7) / 8;
            if (expected > remaining || expected <= 0) return null;
            imageData = new byte[expected];
            System.arraycopy(data, pos, imageData, 0, expected);
        } else if (imageFormat == 1) {
            // 4-bit RLE
            if (remaining > data.length - pos) return null;
            imageData = decodeRleBitmap(data, pos, remaining, xSize, ySize);
        } else {
            if (remaining > data.length - pos || remaining <= 0) return null;
            imageData = new byte[remaining];
            System.arraycopy(data, pos, imageData, 0, remaining);
        }

        BitmapGlyph bmp = new BitmapGlyph();
        bmp.charCode = charCode;
        bmp.imageFormat = imageFormat;
        bmp.xPos = (short) xPos;
        bmp.yPos = (short) yPos;
        bmp.xSize = xSize;
        bmp.ySize = ySize;
        bmp.setWidth = setWidth;
        bmp.imageData = imageData;
        return bmp;
    }

    private static byte[] decodeRleBitmap(byte[] data, int offset, int dataLen, int width, int height) {
        int totalBits = width * height;
        if (totalBits <= 0 || totalBits > 1_000_000) return new byte[0];
        int totalBytes = (totalBits + 7) / 8;
        if (totalBytes <= 0) return new byte[0];
        byte[] result = new byte[totalBytes];
        int outPos = 0;
        int pos = offset;
        int end = offset + dataLen;

        while (pos < end && outPos < totalBits) {
            int b = data[pos++] & 0xFF;
            int count = (b >> 4) & 0x0F;
            int value = b & 0x0F;
            for (int i = 0; i < count; i++) {
                if (outPos >= totalBits) break;
                if (value != 0) {
                    result[outPos / 8] |= (byte) (1 << (7 - (outPos % 8)));
                }
                outPos++;
            }
        }
        return result;
    }

    private static int readUnsignedN(byte[] data, int pos, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            if (pos + i >= data.length) break;
            v = (v << 8) | (data[pos + i] & 0xFF);
        }
        return v;
    }

    private static int readSignedN(byte[] data, int pos, int n) {
        int v = readUnsignedN(data, pos, n);
        if (n == 0) return 0;
        int signBit = 1 << (n * 8 - 1);
        if ((v & signBit) != 0) {
            int mask = (-1) << (n * 8);
            return v | mask;
        }
        return v;
    }
}
