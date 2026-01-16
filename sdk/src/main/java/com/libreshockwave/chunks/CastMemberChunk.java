package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

/**
 * Cast member definition chunk (CASt).
 * Defines an individual cast member with its type and properties.
 */
public record CastMemberChunk(
    int id,
    MemberType memberType,
    int infoLen,
    int dataLen,
    byte[] info,
    byte[] specificData,
    String name,
    int scriptId,
    int regPointX,
    int regPointY
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.CASt;
    }

    public enum MemberType {
        NULL(0, "null"),
        BITMAP(1, "bitmap"),
        FILM_LOOP(2, "filmLoop"),
        TEXT(3, "text"),
        PALETTE(4, "palette"),
        PICTURE(5, "picture"),
        SOUND(6, "sound"),
        BUTTON(7, "button"),
        SHAPE(8, "shape"),
        MOVIE(9, "movie"),
        DIGITAL_VIDEO(10, "digitalVideo"),
        SCRIPT(11, "script"),
        RTE(12, "rte"),
        OLE(13, "ole"),
        TRANSITION(14, "transition"),
        XTRA(15, "xtra"),
        FONT(16, "font"),
        UNKNOWN(-1, "unknown");

        private final int code;
        private final String name;

        MemberType(int code, String name) {
            this.code = code;
            this.name = name;
        }

        public int getCode() {
            return code;
        }

        public String getName() {
            return name;
        }

        public static MemberType fromCode(int code) {
            for (MemberType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }

    public boolean isBitmap() {
        return memberType == MemberType.BITMAP;
    }

    public boolean isScript() {
        return memberType == MemberType.SCRIPT;
    }

    public boolean isText() {
        return memberType == MemberType.TEXT;
    }

    public boolean isSound() {
        return memberType == MemberType.SOUND;
    }

    public static CastMemberChunk read(BinaryReader reader, int id, int version) {
        // CASt chunks are ALWAYS big endian regardless of file byte order
        reader.setOrder(java.nio.ByteOrder.BIG_ENDIAN);

        MemberType memberType;
        int infoLen;
        int dataLen;
        String name = "";
        int scriptId = 0;
        int regPointX = 0;
        int regPointY = 0;

        if (version >= 1024) {
            // Director 6+
            int type = reader.readI32();
            infoLen = reader.readI32();
            dataLen = reader.readI32();
            memberType = MemberType.fromCode(type);
        } else {
            // Director 5 and earlier
            int dataSize = reader.readI16() & 0xFFFF;
            int totalSize = reader.readI32();
            int type = reader.readU8();
            memberType = MemberType.fromCode(type);
            infoLen = dataSize;
            dataLen = totalSize - dataSize;

            if (reader.bytesLeft() > 0) {
                reader.skip(1); // flags
            }
        }

        byte[] info = new byte[0];
        byte[] specificData = new byte[0];

        if (infoLen > 0 && reader.bytesLeft() >= infoLen) {
            info = reader.readBytes(infoLen);
        }

        if (dataLen > 0 && reader.bytesLeft() >= dataLen) {
            specificData = reader.readBytes(dataLen);
        }

        // Parse CastInfoChunk (ListChunk structure) to extract name and scriptId
        // Structure: header (dataOffset, unk1, unk2, flags, scriptId), then offset table, then items
        if (info.length >= 20) {
            BinaryReader infoReader = new BinaryReader(info);
            infoReader.setOrder(java.nio.ByteOrder.BIG_ENDIAN);  // Info is always big endian

            // Read ListChunk header (CastInfoChunk header)
            int dataOffset = infoReader.readI32();
            int unk1 = infoReader.readI32();
            int unk2 = infoReader.readI32();
            int flags = infoReader.readI32();
            scriptId = infoReader.readI32();

            // Read offset table (at dataOffset position)
            if (dataOffset > 0 && dataOffset < info.length) {
                infoReader.setPosition(dataOffset);
                int offsetTableLen = infoReader.readU16();

                if (offsetTableLen > 0) {
                    int[] offsets = new int[offsetTableLen];
                    for (int i = 0; i < offsetTableLen; i++) {
                        offsets[i] = infoReader.readI32();
                    }

                    // Read items length
                    int itemsLen = infoReader.readI32();
                    int itemsStart = infoReader.getPosition();

                    // Item at index 1 is the name (Pascal string)
                    if (offsetTableLen > 1) {
                        int nameOffset = offsets[1];
                        int nameEnd = (offsetTableLen > 2) ? offsets[2] : itemsLen;
                        int nameLen = nameEnd - nameOffset;

                        if (nameLen > 0 && itemsStart + nameOffset < info.length) {
                            infoReader.setPosition(itemsStart + nameOffset);
                            int pascalLen = infoReader.readU8();
                            if (pascalLen > 0 && infoReader.bytesLeft() >= pascalLen) {
                                name = infoReader.readStringMacRoman(pascalLen);
                            }
                        }
                    }
                }
            }
        }

        return new CastMemberChunk(
            id,
            memberType,
            infoLen,
            dataLen,
            info,
            specificData,
            name,
            scriptId,
            regPointX,
            regPointY
        );
    }
}
