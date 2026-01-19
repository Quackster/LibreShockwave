package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.vm.LingoVM;

import java.util.ArrayList;
import java.util.List;

/**
 * Styled text chunk (STXT).
 * Contains text content with formatting information.
 */
public record TextChunk(
    DirectorFile file,
    int id,
    String text,
    List<TextRun> runs
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.STXT;
    }

    public record TextRun(
        int startOffset,
        int endOffset,
        int fontId,
        int fontSize,
        int fontStyle
    ) {}

    public static TextChunk read(DirectorFile file, BinaryReader reader, int id) {
        // STXT chunks always use big-endian format regardless of file endianness
        java.nio.ByteOrder originalOrder = reader.getOrder();
        reader.setOrder(java.nio.ByteOrder.BIG_ENDIAN);

        int headerLen = reader.readI32();
        int textLen = reader.readI32();

        reader.skip(headerLen - 8);

        String text = reader.readStringMacRoman(textLen);

        // Parse formatting runs if present
        List<TextRun> runs = new ArrayList<>();
        if (reader.bytesLeft() >= 4) {
            int runCount = reader.readI16();
            reader.skip(2);

            for (int i = 0; i < runCount && reader.bytesLeft() >= 16; i++) {
                int startOffset = reader.readI32();
                int fontId = reader.readI16();
                int fontStyle = reader.readU8();
                reader.skip(1);
                int fontSize = reader.readI16();
                reader.skip(6);

                runs.add(new TextRun(startOffset, textLen, fontId, fontSize, fontStyle));
            }
        }

        // Restore original byte order
        reader.setOrder(originalOrder);

        return new TextChunk(file, id, text, runs);
    }
}
