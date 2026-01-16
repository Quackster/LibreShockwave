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

        // Parse common info fields if present
        if (info.length >= 20) {
            BinaryReader infoReader = new BinaryReader(info);
            infoReader.setOrder(reader.getOrder());

            infoReader.skip(4); // unknown
            scriptId = infoReader.readI32();

            // Skip to name offset table
            if (info.length >= 32) {
                infoReader.setPosition(20);
                regPointX = infoReader.readI16();
                regPointY = infoReader.readI16();
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
