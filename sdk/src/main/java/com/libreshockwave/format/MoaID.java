package com.libreshockwave.format;

import com.libreshockwave.io.BinaryReader;

import java.io.IOException;
import java.util.Objects;

/**
 * Macromedia Open Architecture ID - used to identify compression types
 * in Afterburner files. This is essentially a GUID structure.
 */
public record MoaID(int data1, short data2, short data3, int data4, int data5) {

    // Known compression type GUIDs
    public static final MoaID ZLIB_COMPRESSION = new MoaID(
        0xAC99E904, (short) 0x0070, (short) 0x0B36, 0x00080000, 0x347A3707
    );

    public static final MoaID ZLIB_COMPRESSION_ALT = new MoaID(
        0xAC99E904, (short) 0x0070, (short) 0x0B36, 0x00000800, 0x07377A34
    );

    public static final MoaID SND_COMPRESSION = new MoaID(
        0x7204A889, (short) 0xAFD0, (short) 0x11CF, 0xA00022A2, 0x4C445323
    );

    public static final MoaID NULL_COMPRESSION = new MoaID(
        0xAC99982E, (short) 0x005D, (short) 0x0D50, 0x00080000, 0x347A3707
    );

    public static final MoaID FONTMAP_COMPRESSION = new MoaID(
        0x8A4679A1, (short) 0x3720, (short) 0x11D0, 0xA0002392, 0xB16808C9
    );

    /**
     * Read a MoaID from a binary reader.
     */
    public static MoaID read(BinaryReader reader) throws IOException {
        int d1 = reader.readInt();
        short d2 = reader.readShort();
        short d3 = reader.readShort();
        int d4 = reader.readInt();
        int d5 = reader.readInt();
        return new MoaID(d1, d2, d3, d4, d5);
    }

    /**
     * Check if this MoaID represents zlib compression.
     */
    public boolean isZlib() {
        return this.equals(ZLIB_COMPRESSION) || this.equals(ZLIB_COMPRESSION_ALT);
    }

    /**
     * Check if this MoaID represents sound compression.
     */
    public boolean isSound() {
        return this.equals(SND_COMPRESSION);
    }

    /**
     * Check if this MoaID represents no compression (raw data).
     */
    public boolean isNull() {
        return this.equals(NULL_COMPRESSION);
    }

    /**
     * Check if this MoaID represents font map compression.
     */
    public boolean isFontMap() {
        return this.equals(FONTMAP_COMPRESSION);
    }

    @Override
    public String toString() {
        return String.format("MoaID[%08X-%04X-%04X-%08X-%08X]",
            data1 & 0xFFFFFFFFL,
            data2 & 0xFFFF,
            data3 & 0xFFFF,
            data4 & 0xFFFFFFFFL,
            data5 & 0xFFFFFFFFL);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MoaID moaID)) return false;
        return data1 == moaID.data1 &&
               data2 == moaID.data2 &&
               data3 == moaID.data3 &&
               data4 == moaID.data4 &&
               data5 == moaID.data5;
    }

    @Override
    public int hashCode() {
        return Objects.hash(data1, data2, data3, data4, data5);
    }
}
