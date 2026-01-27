package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Director configuration chunk (DRCF/VWCF).
 * Contains movie properties like stage size, FPS, version.
 */
public record ConfigChunk(
    DirectorFile file,
    int id,
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
    int commentFont,
    int commentSize,
    int commentStyle,
    int defaultPalette,
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

    public static ConfigChunk read(DirectorFile file, BinaryReader reader, int id, int version, ByteOrder endian) {
        // Config chunk is ALWAYS big endian regardless of file byte order
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        // First read directorVersion from offset 36 to determine format
        reader.seek(36);
        int directorVersion = reader.readI16() & 0xFFFF;

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
        /* 18 */ reader.skip(2); // preD7field11 or D7stageColorGB
        /* 20 */ int commentFont = reader.readI16();
        /* 22 */ int commentSize = reader.readI16();
        /* 24 */ int commentStyle = reader.readU16();
        /* 26 */ int stageColor = reader.readI16(); // preD7stageColor or D7stageColorIsRGB+R
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

        int defaultPalette = 0;
        int movieVersion = fileVersion;

        return new ConfigChunk(
            file,
            id,
            directorVersion,
            stageTop, stageLeft, stageBottom, stageRight,
            minMember, maxMember,
            tempo, bgColor, stageColor,
            commentFont, commentSize, commentStyle,
            defaultPalette, movieVersion, platform
        );
    }
}
