package com.libreshockwave.font;

/**
 * Bit-level reader for PFR1 font data.
 * PFR1 format requires reading individual bits and multi-bit values
 * that don't align to byte boundaries.
 */
public class PfrBitReader {

    private final byte[] data;
    private int pos;
    private int bitBuffer;
    private int bitsLeft;

    public PfrBitReader(byte[] data) {
        this(data, 0);
    }

    public PfrBitReader(byte[] data, int offset) {
        this.data = data;
        this.pos = offset;
    }

    public int position() { return pos; }
    public void setPosition(int p) { pos = p; bitBuffer = 0; bitsLeft = 0; }
    public int remaining() { return pos >= data.length ? 0 : data.length - pos; }

    private void alignToByte() { bitBuffer = 0; bitsLeft = 0; }

    // ---- Byte-level reads (big-endian) ----

    public int readU8() {
        alignToByte();
        if (pos >= data.length) return 0;
        return data[pos++] & 0xFF;
    }

    public int readI8() {
        return (byte) readU8();
    }

    public int readU16() {
        int hi = readU8();
        int lo = readU8();
        return (hi << 8) | lo;
    }

    public int readI16() {
        return (short) readU16();
    }

    public int readU24() {
        int b0 = readU8();
        int b1 = readU8();
        int b2 = readU8();
        return (b0 << 16) | (b1 << 8) | b2;
    }

    public int readI24() {
        int val = readU24();
        if ((val & 0x800000) != 0) {
            return val | 0xFF000000;
        }
        return val;
    }

    public void skip(int count) {
        alignToByte();
        pos += count;
        if (pos > data.length) pos = data.length;
    }

    // ---- Bit-level reads (MSB first) ----

    public int readBits(int count) {
        if (count == 0) return 0;
        int result = 0;
        int remaining = count;
        while (remaining > 0) {
            if (bitsLeft == 0) {
                if (pos >= data.length) return result;
                bitBuffer = data[pos++] & 0xFF;
                bitsLeft = 8;
            }
            int take = Math.min(remaining, bitsLeft);
            int shift = bitsLeft - take;
            int mask = ((1 << take) - 1) << shift;
            int bits = (bitBuffer & mask) >>> shift;
            result = (result << take) | bits;
            bitsLeft -= take;
            remaining -= take;
        }
        return result;
    }

    public boolean readBit() {
        return readBits(1) != 0;
    }
}
