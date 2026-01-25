package com.libreshockwave.cast;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Shape-specific cast member data.
 */
public record ShapeInfo(
    ShapeType shapeType,
    int regX,
    int regY,
    int width,
    int height,
    int color
) implements Dimensioned {

    public enum ShapeType {
        RECT(0x01),
        OVAL_RECT(0x02),
        OVAL(0x03),
        LINE(0x08),
        UNKNOWN(0);

        private final int code;

        ShapeType(int code) {
            this.code = code;
        }

        public static ShapeType fromCode(int code) {
            for (ShapeType type : values()) {
                if (type.code == code) return type;
            }
            return UNKNOWN;
        }
    }

    public static ShapeInfo parse(byte[] data) {
        if (data == null || data.length < 14) {
            return new ShapeInfo(ShapeType.UNKNOWN, 0, 0, 0, 0, 0);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        int shapeTypeRaw = reader.readU16();
        int regY = reader.readU16();
        int regX = reader.readU16();
        int height = reader.readU16();
        int width = reader.readU16();
        reader.skip(2); // unknown
        int color = reader.readU8();

        return new ShapeInfo(
            ShapeType.fromCode(shapeTypeRaw),
            regX, regY,
            width, height,
            color
        );
    }
}
