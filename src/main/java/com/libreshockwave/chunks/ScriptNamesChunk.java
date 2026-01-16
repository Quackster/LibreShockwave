package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Script names chunk (Lnam).
 * Contains the symbol table for scripts (handler names, variable names, etc.).
 */
public record ScriptNamesChunk(
    int id,
    List<String> names
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.Lnam;
    }

    public String getName(int index) {
        if (index >= 0 && index < names.size()) {
            return names.get(index);
        }
        return "<unknown:" + index + ">";
    }

    public int findName(String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public static ScriptNamesChunk read(BinaryReader reader, int id, int version) {
        // Lingo scripts are ALWAYS big endian regardless of file byte order
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        int unknown0 = reader.readI32();
        int unknown1 = reader.readI32();
        int len1 = reader.readI32();
        int len2 = reader.readI32();
        int namesOffset = reader.readU16();
        int namesCount = reader.readU16();

        List<String> names = new ArrayList<>();

        if (namesCount > 0 && namesOffset > 0 && namesOffset < reader.length()) {
            reader.setPosition(namesOffset);
            for (int i = 0; i < namesCount; i++) {
                int len = reader.readU8();
                if (reader.bytesLeft() >= len) {
                    names.add(reader.readStringMacRoman(len));
                }
            }
        }

        return new ScriptNamesChunk(id, names);
    }
}
