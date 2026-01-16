package com.libreshockwave.io;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Endian-aware binary reader for Director file formats.
 * Supports both big-endian (Macintosh) and little-endian (Windows) byte orders.
 */
public class BinaryReader implements AutoCloseable {

    // Mac Roman charset with fallback for environments that don't support it (e.g., TeaVM)
    private static final Charset MAC_ROMAN;
    static {
        Charset charset;
        try {
            charset = Charset.forName("x-MacRoman");
        } catch (Exception e) {
            // Fall back to ISO-8859-1 which covers most MacRoman characters
            charset = StandardCharsets.ISO_8859_1;
        }
        MAC_ROMAN = charset;
    }

    private final byte[] data;
    private int position;
    private ByteOrder order;

    public BinaryReader(byte[] data) {
        this.data = data;
        this.position = 0;
        this.order = ByteOrder.BIG_ENDIAN;
    }

    public BinaryReader(byte[] data, ByteOrder order) {
        this.data = data;
        this.position = 0;
        this.order = order;
    }

    public static BinaryReader fromInputStream(InputStream is) throws IOException {
        return new BinaryReader(is.readAllBytes());
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

    // Position control

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void skip(int bytes) {
        position += bytes;
    }

    public void seek(int offset) {
        this.position = offset;
    }

    public int length() {
        return data.length;
    }

    public int bytesLeft() {
        return Math.max(0, data.length - position);
    }

    public boolean eof() {
        return position >= data.length;
    }

    // Primitive reads

    public byte readI8() {
        return data[position++];
    }

    public int readU8() {
        return data[position++] & 0xFF;
    }

    public short readI16() {
        byte[] bytes = readBytes(2);
        return ByteBuffer.wrap(bytes).order(order).getShort();
    }

    public int readU16() {
        return readI16() & 0xFFFF;
    }

    public int readI32() {
        byte[] bytes = readBytes(4);
        return ByteBuffer.wrap(bytes).order(order).getInt();
    }

    public long readU32() {
        return readI32() & 0xFFFFFFFFL;
    }

    public long readI64() {
        byte[] bytes = readBytes(8);
        return ByteBuffer.wrap(bytes).order(order).getLong();
    }

    public float readF32() {
        byte[] bytes = readBytes(4);
        return ByteBuffer.wrap(bytes).order(order).getFloat();
    }

    public double readF64() {
        byte[] bytes = readBytes(8);
        return ByteBuffer.wrap(bytes).order(order).getDouble();
    }

    // Byte array reads

    public byte[] readBytes(int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, position, result, 0, length);
        position += length;
        return result;
    }

    public byte[] peekBytes(int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, position, result, 0, length);
        return result;
    }

    // FourCC reads (4-byte ASCII identifier)

    /**
     * Read a FourCC as a big-endian integer for comparison.
     * Always reads as big-endian regardless of the reader's byte order.
     */
    public int readFourCC() {
        byte[] bytes = readBytes(4);
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    /**
     * Read a FourCC as a String.
     */
    public String readFourCCString() {
        byte[] bytes = readBytes(4);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /**
     * Convert a 4-character string to a FourCC integer (big-endian).
     */
    public static int fourCC(String s) {
        if (s.length() != 4) {
            throw new IllegalArgumentException("FourCC must be exactly 4 characters");
        }
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public static String fourCCToString(int fourcc) {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(fourcc).array();
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    // String reads

    public String readString(int length) {
        byte[] bytes = readBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String readStringMacRoman(int length) {
        byte[] bytes = readBytes(length);
        return new String(bytes, MAC_ROMAN);
    }

    public String readPascalString() {
        int length = readU8();
        return readStringMacRoman(length);
    }

    public String readNullTerminatedString() {
        int start = position;
        while (position < data.length && data[position] != 0) {
            position++;
        }
        String result = new String(data, start, position - start, StandardCharsets.UTF_8);
        if (position < data.length) {
            position++; // skip null terminator
        }
        return result;
    }

    // Variable-length integer (used in Afterburner format)

    public int readVarInt() {
        int value = 0;
        int b;
        do {
            b = readU8();
            value = (value << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0);
        return value;
    }

    // Apple 80-bit extended float (SANE format)

    public double readAppleFloat80() {
        byte[] bytes = readBytes(10);

        int exponent = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        long sign = (exponent & 0x8000) != 0 ? 1L : 0L;
        exponent &= 0x7FFF;

        long fraction = 0;
        for (int i = 2; i < 10; i++) {
            fraction = (fraction << 8) | (bytes[i] & 0xFF);
        }
        fraction &= 0x7FFFFFFFFFFFFFFFL;

        long f64exp;
        if (exponent == 0) {
            f64exp = 0;
        } else if (exponent == 0x7FFF) {
            f64exp = 0x7FF;
        } else {
            long normexp = exponent - 0x3FFF;
            if (normexp < -0x3FE || normexp >= 0x3FF) {
                throw new ArithmeticException("Float exponent too large for double");
            }
            f64exp = normexp + 0x3FF;
        }

        long f64bits = (sign << 63) | (f64exp << 52) | (fraction >> 11);
        return Double.longBitsToDouble(f64bits);
    }

    // Zlib decompression

    public byte[] readZlibBytes(int compressedLength) throws IOException {
        byte[] compressed = readBytes(compressedLength);
        return decompressZlib(compressed);
    }

    /**
     * Decompress zlib-compressed data.
     */
    public byte[] decompressZlib(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        ByteBuffer output = ByteBuffer.allocate(compressed.length * 4);
        byte[] buffer = new byte[4096];

        try {
            while (!inflater.finished()) {
                if (output.remaining() < buffer.length) {
                    ByteBuffer newOutput = ByteBuffer.allocate(output.capacity() * 2);
                    output.flip();
                    newOutput.put(output);
                    output = newOutput;
                }
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) {
                    break; // No more input available
                }
                output.put(buffer, 0, count);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Invalid zlib data", e);
        } finally {
            inflater.end();
        }

        output.flip();
        byte[] result = new byte[output.remaining()];
        output.get(result);
        return result;
    }

    // Alias methods for compatibility

    public int readInt() {
        return readI32();
    }

    public short readShort() {
        return readI16();
    }

    public int readUnsignedByte() {
        return readU8();
    }

    public int readUnsignedShort() {
        return readU16();
    }

    public BinaryReader sliceReader(int length) {
        BinaryReader slice = new BinaryReader(readBytes(length));
        slice.setOrder(this.order);
        return slice;
    }

    public BinaryReader sliceReaderAt(int offset, int length) {
        byte[] sliceData = new byte[length];
        System.arraycopy(data, offset, sliceData, 0, length);
        BinaryReader slice = new BinaryReader(sliceData);
        slice.setOrder(this.order);
        return slice;
    }

    @Override
    public void close() {
        // No resources to close for byte array based reader
    }
}
