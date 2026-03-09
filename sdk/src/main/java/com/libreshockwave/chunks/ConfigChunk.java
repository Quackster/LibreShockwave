package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Director configuration chunk (DRCF/VWCF).
 * Contains movie properties like stage size, FPS, version.
 */
public record ConfigChunk(
    DirectorFile file,
    ChunkId id,
    int directorVersion,
    int stageTop,
    int stageLeft,
    int stageBottom,
    int stageRight,
    int minMember,
    int maxMember,
    int tempo,
    int bgColor,
    int stageColor,
    int stageColorRGB,
    int commentFont,
    int commentSize,
    int commentStyle,
    int defaultPaletteCastLib,
    int defaultPaletteMember,
    int movieVersion,
    short platform
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.DRCF;
    }

    public int stageWidth() {
        return stageRight - stageLeft;
    }

    public int stageHeight() {
        return stageBottom - stageTop;
    }

    public static ConfigChunk read(DirectorFile file, BinaryReader reader, ChunkId id, int version, ByteOrder endian) {
        // Config chunk is ALWAYS big endian regardless of file byte order
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        // First read directorVersion from offset 36 to determine format
        reader.seek(36);
        int directorVersion = reader.readI16() & 0xFFFF;
        // Director 7+ uses raw version >= 0x208 (520)
        boolean isD7Plus = directorVersion >= 0x208;

        // Now read from the beginning
        reader.seek(0);

        /*  0 */ int len = reader.readI16() & 0xFFFF;
        /*  2 */ int fileVersion = reader.readI16() & 0xFFFF;
        /*  4 */ int stageTop = reader.readI16();
        /*  6 */ int stageLeft = reader.readI16();
        /*  8 */ int stageBottom = reader.readI16();
        /* 10 */ int stageRight = reader.readI16();
        /* 12 */ int minMember = reader.readI16();
        /* 14 */ int maxMember = reader.readI16();
        /* 16 */ reader.skip(2); // field9, field10

        // Offset 18-19: D7+ stores stage color G and B bytes here
        int d7StageColorG = 0, d7StageColorB = 0;
        if (isD7Plus) {
            /* 18 */ d7StageColorG = reader.readU8();
            /* 19 */ d7StageColorB = reader.readU8();
        } else {
            /* 18 */ reader.skip(2); // preD7field11
        }

        /* 20 */ int commentFont = reader.readI16();
        /* 22 */ int commentSize = reader.readI16();
        /* 24 */ int commentStyle = reader.readU16();

        // Offset 26-27: D7+ stores isRGB flag + R byte; pre-D7 stores palette index as u16
        int stageColor;
        int stageColorRGB;
        if (isD7Plus) {
            int d7IsRgb = reader.readU8();    // offset 26
            int d7StageColorR = reader.readU8(); // offset 27
            stageColor = (d7IsRgb << 8) | d7StageColorR; // raw value for backward compat
            if (d7IsRgb != 0) {
                // RGB mode: use the color components directly
                stageColorRGB = (d7StageColorR << 16) | (d7StageColorG << 8) | d7StageColorB;
            } else {
                // Palette index mode: resolve via Mac system palette
                stageColorRGB = Palette.SYSTEM_MAC_PALETTE.getColor(d7StageColorR & 0xFF);
            }
        } else {
            /* 26 */ stageColor = reader.readI16();
            // Pre-D7: palette index stored as u16, resolve via Mac system palette
            stageColorRGB = Palette.SYSTEM_MAC_PALETTE.getColor(stageColor & 0xFF);
        }

        /* 28 */ int bgColor = reader.readI16(); // bitDepth
        /* 30 */ reader.skip(2); // field17, field18
        /* 32 */ reader.skip(4); // field19
        /* 36 */ reader.skip(2); // directorVersion (already read)
        /* 38 */ reader.skip(2); // field21
        /* 40 */ reader.skip(4); // field22
        /* 44 */ reader.skip(4); // field23
        /* 48 */ reader.skip(4); // field24
        /* 52 */ reader.skip(2); // field25, field26
        /* 54 */ int tempo = reader.readI16();
        /* 56 */ short platform = reader.readShort();

        // Read default palette (D5+) — stored at offsets 76-80 in the DRCF/VWCF chunk.
        // ScummVM: Cast::loadConfig() reads castLib (int16) at 76, member (int16) at 78.
        int defaultPaletteCastLib = 0;
        int defaultPaletteMember = 0;
        if (reader.bytesLeft() >= 22) { // Need at least 22 bytes from offset 58 to 80
            reader.skip(18); // Skip offsets 58-75 (field30, defPaletteNum, chunkBaseNum, etc.)
            /* 76 */ defaultPaletteCastLib = reader.readI16();
            /* 78 */ defaultPaletteMember = reader.readI16();
        }

        int movieVersion = fileVersion;

        return new ConfigChunk(
            file,
            id,
            directorVersion,
            stageTop, stageLeft, stageBottom, stageRight,
            minMember, maxMember,
            tempo, bgColor, stageColor, stageColorRGB,
            commentFont, commentSize, commentStyle,
            defaultPaletteCastLib, defaultPaletteMember, movieVersion, platform
        );
    }
}
