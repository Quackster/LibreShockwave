package com.libreshockwave.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/**
 * Endian-aware binary writer for Director file formats.
 * Supports both big-endian (Macintosh) and little-endian (Windows) byte orders.
 * Counterpart to {@link BinaryReader}.
 */
public class BinaryWriter {

    // Mac Roman charset with fallback for environments that don't support it
    private static final Charset MAC_ROMAN;
    static {
        Charset charset;
        try {
            charset = Charset.forName("x-MacRoman");
        } catch (Exception e) {
            charset = StandardCharsets.ISO_8859_1;
        }
        MAC_ROMAN = charset;
    }

    private final ByteArrayOutputStream buffer;
    private ByteOrder order;

    public BinaryWriter() {
        this.buffer = new ByteArrayOutputStream();
        this.order = ByteOrder.BIG_ENDIAN;
    }

    public BinaryWriter(int initialCapacity) {
        this.buffer = new ByteArrayOutputStream(initialCapacity);
        this.order = ByteOrder.BIG_ENDIAN;
    }

    public BinaryWriter(ByteOrder order) {
        this.buffer = new ByteArrayOutputStream();
        this.order = order;
    }

    // Endian control

    public ByteOrder getOrder() {
        return order;
    }

    public void setOrder(ByteOrder order) {
        this.order = order;
    }

    public boolean isBigEndian() {
        return order == ByteOrder.BIG_ENDIAN;
    }

    public boolean isLittleEndian() {
        return order == ByteOrder.LITTLE_ENDIAN;
    }

    // Position/size info

    public int getPosition() {
        return buffer.size();
    }

    public int size() {
        return buffer.size();
    }

    // Primitive writes

    public void writeI8(byte value) {
        buffer.write(value);
    }

    public void writeU8(int value) {
        buffer.write(value & 0xFF);
    }

    public void writeI16(short value) {
        byte[] bytes = ByteBuffer.allocate(2).order(order).putShort(value).array();
        buffer.write(bytes, 0, 2);
    }

    public void writeU16(int value) {
        writeI16((short) (value & 0xFFFF));
    }

    public void writeI32(int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(order).putInt(value).array();
        buffer.write(bytes, 0, 4);
    }

    public void writeU32(long value) {
        writeI32((int) (value & 0xFFFFFFFFL));
    }

    public void writeI64(long value) {
        byte[] bytes = ByteBuffer.allocate(8).order(order).putLong(value).array();
        buffer.write(bytes, 0, 8);
    }

    public void writeF32(float value) {
        byte[] bytes = ByteBuffer.allocate(4).order(order).putFloat(value).array();
        buffer.write(bytes, 0, 4);
    }

    public void writeF64(double value) {
        byte[] bytes = ByteBuffer.allocate(8).order(order).putDouble(value).array();
        buffer.write(bytes, 0, 8);
    }

    // Byte array writes

    public void writeBytes(byte[] data) {
        buffer.write(data, 0, data.length);
    }

    public void writeBytes(byte[] data, int offset, int length) {
        buffer.write(data, offset, length);
    }

    // FourCC writes (4-byte ASCII identifier, always big-endian)

    /**
     * Write a FourCC from an integer value.
     * Always writes as big-endian regardless of the writer's byte order.
     */
    public void writeFourCC(int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
        buffer.write(bytes, 0, 4);
    }

    /**
     * Write a FourCC from a String.
     */
    public void writeFourCCString(String s) {
        if (s.length() != 4) {
            throw new IllegalArgumentException("FourCC must be exactly 4 characters");
        }
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        buffer.write(bytes, 0, 4);
    }

    // String writes

    public void writeString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buffer.write(bytes, 0, bytes.length);
    }

    public void writeStringMacRoman(String s) {
        byte[] bytes = s.getBytes(MAC_ROMAN);
        buffer.write(bytes, 0, bytes.length);
    }

    public void writePascalString(String s) {
        byte[] bytes = s.getBytes(MAC_ROMAN);
        if (bytes.length > 255) {
            throw new IllegalArgumentException("Pascal string cannot exceed 255 bytes");
        }
        writeU8(bytes.length);
        buffer.write(bytes, 0, bytes.length);
    }

    public void writeNullTerminatedString(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buffer.write(bytes, 0, bytes.length);
        buffer.write(0);
    }

    public void writeNullTerminatedStringMacRoman(String s) {
        byte[] bytes = s.getBytes(MAC_ROMAN);
        buffer.write(bytes, 0, bytes.length);
        buffer.write(0);
    }

    // Variable-length integer (used in Afterburner format)

    public void writeVarInt(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("VarInt cannot be negative");
        }

        // Count how many 7-bit groups we need
        int temp = value;
        int numGroups = 1;
        while (temp >= 0x80) {
            temp >>>= 7;
            numGroups++;
        }

        // Write groups from most significant to least significant
        for (int i = numGroups - 1; i >= 0; i--) {
            int b = (value >>> (i * 7)) & 0x7F;
            if (i > 0) {
                b |= 0x80; // Set continuation bit
            }
            buffer.write(b);
        }
    }

    // Padding/alignment

    public void writePadding(int count) {
        for (int i = 0; i < count; i++) {
            buffer.write(0);
        }
    }

    public void writePadding(int count, int value) {
        for (int i = 0; i < count; i++) {
            buffer.write(value & 0xFF);
        }
    }

    /**
     * Pad to align to a boundary.
     * @param alignment The alignment boundary (e.g., 2, 4, 8)
     */
    public void alignTo(int alignment) {
        int padding = (alignment - (buffer.size() % alignment)) % alignment;
        writePadding(padding);
    }

    // Zlib compression

    /**
     * Compress data using zlib.
     */
    public static byte[] compressZlib(byte[] data) throws IOException {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        byte[] buf = new byte[4096];

        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            output.write(buf, 0, count);
        }

        deflater.end();
        return output.toByteArray();
    }

    // Overwrite at position (for fixing up offsets)

    /**
     * Get the underlying byte array for direct manipulation.
     * Use with caution - primarily for fixing up offsets.
     */
    public byte[] getInternalBuffer() {
        return buffer.toByteArray();
    }

    /**
     * Create a new writer with modified bytes at a specific position.
     * Useful for patching offset values after writing.
     */
    public void patchI32(byte[] data, int position, int value) {
        byte[] bytes = ByteBuffer.allocate(4).order(order).putInt(value).array();
        System.arraycopy(bytes, 0, data, position, 4);
    }

    /**
     * Create a new writer with modified bytes at a specific position.
     */
    public void patchU16(byte[] data, int position, int value) {
        byte[] bytes = ByteBuffer.allocate(2).order(order).putShort((short) value).array();
        System.arraycopy(bytes, 0, data, position, 2);
    }

    // Result extraction

    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    public void reset() {
        buffer.reset();
    }

    // Alias methods for compatibility with BinaryReader naming

    public void writeInt(int value) {
        writeI32(value);
    }

    public void writeShort(short value) {
        writeI16(value);
    }

    public void writeUnsignedByte(int value) {
        writeU8(value);
    }

    public void writeUnsignedShort(int value) {
        writeU16(value);
    }
}
