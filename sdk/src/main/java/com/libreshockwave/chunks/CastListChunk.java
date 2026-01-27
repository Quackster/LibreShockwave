package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Cast list chunk (MCsL).
 * Contains the list of cast libraries in the movie.
 */
public record CastListChunk(
    DirectorFile file,
    int id,
    int dataOffset,
    int itemsPerEntry,
    int itemCount,
    List<CastListEntry> entries
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.MCsL;
    }

    public record CastListEntry(
        String name,
        String path,
        int minMember,
        int maxMember,
        int memberCount,
        int id
    ) {}

    public static CastListChunk read(DirectorFile file, BinaryReader reader, int id, int version, ByteOrder endian) {
        // CastListChunk uses BIG ENDIAN
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        // Read header
        int dataOffset = reader.readI32();
        int unk0 = reader.readU16();
        int castCount = reader.readU16();
        int itemsPerCast = reader.readU16();
        int unk1 = reader.readU16();

        // Sanity check
        if (dataOffset < 0 || dataOffset >= reader.length() || castCount < 0 || castCount > 1000) {
            return new CastListChunk(file, id, dataOffset, itemsPerCast, 0, new ArrayList<>());
        }

        // Seek to data offset to read offset table
        reader.setPosition(dataOffset);
        int offsetTableLen = reader.readU16();

        // Sanity check
        if (offsetTableLen > 10000) {
            return new CastListChunk(file, id, dataOffset, itemsPerCast, castCount, new ArrayList<>());
        }

        int[] offsetTable = new int[offsetTableLen];
        for (int i = 0; i < offsetTableLen; i++) {
            offsetTable[i] = reader.readI32();
        }

        int itemsLen = reader.readI32();
        int listOffset = reader.getPosition();

        // Read items as byte arrays
        byte[][] items = new byte[offsetTableLen][];
        for (int i = 0; i < offsetTableLen; i++) {
            int offset = offsetTable[i];
            int nextOffset = (i == offsetTableLen - 1) ? itemsLen : offsetTable[i + 1];
            int itemLen = nextOffset - offset;
            if (itemLen > 0 && itemLen < 10000) {
                reader.setPosition(listOffset + offset);
                items[i] = reader.readBytes(itemLen);
            } else {
                items[i] = new byte[0];
            }
        }

        // Parse entries
        List<CastListEntry> entries = new ArrayList<>();
        for (int i = 0; i < castCount; i++) {
            String name = "";
            String path = "";
            int minMember = 0;
            int maxMember = 0;
            int memberCount = 0;
            int castId = i + 1;

            if (itemsPerCast >= 1 && i * itemsPerCast + 1 < offsetTableLen) {
                byte[] nameData = items[i * itemsPerCast + 1];
                if (nameData.length > 0) {
                    int nameLen = nameData[0] & 0xFF;
                    if (nameLen <= nameData.length - 1) {
                        name = new String(nameData, 1, nameLen);
                    }
                }
            }
            if (itemsPerCast >= 2 && i * itemsPerCast + 2 < offsetTableLen) {
                byte[] pathData = items[i * itemsPerCast + 2];
                if (pathData.length > 0) {
                    int pathLen = pathData[0] & 0xFF;
                    if (pathLen <= pathData.length - 1) {
                        path = new String(pathData, 1, pathLen);
                    }
                }
            }
            if (itemsPerCast >= 4 && i * itemsPerCast + 4 < offsetTableLen) {
                byte[] memberData = items[i * itemsPerCast + 4];
                if (memberData.length >= 8) {
                    BinaryReader itemReader = new BinaryReader(memberData, ByteOrder.BIG_ENDIAN);
                    minMember = itemReader.readU16();
                    maxMember = itemReader.readU16();
                    castId = itemReader.readI32();
                }
            }

            if (maxMember >= minMember) {
                memberCount = maxMember - minMember + 1;
            }

            entries.add(new CastListEntry(name, path, minMember, maxMember, memberCount, castId));
        }

        return new CastListChunk(file, id, dataOffset, itemsPerCast, castCount, entries);
    }
}
