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

    public byte[] getData() {
        return data;
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
        byte[] b = readBytes(2);
        if (order == ByteOrder.BIG_ENDIAN) {
            return (short) (((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
        } else {
            return (short) (((b[1] & 0xFF) << 8) | (b[0] & 0xFF));
        }
    }

    public int readU16() {
        return readI16() & 0xFFFF;
    }

    public int readI32() {
        byte[] b = readBytes(4);
        if (order == ByteOrder.BIG_ENDIAN) {
            return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) |
                   ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        } else {
            return ((b[3] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) |
                   ((b[1] & 0xFF) << 8) | (b[0] & 0xFF);
        }
    }

    public long readU32() {
        return readI32() & 0xFFFFFFFFL;
    }

    public long readI64() {
        byte[] b = readBytes(8);
        if (order == ByteOrder.BIG_ENDIAN) {
            return ((long)(b[0] & 0xFF) << 56) | ((long)(b[1] & 0xFF) << 48) |
                   ((long)(b[2] & 0xFF) << 40) | ((long)(b[3] & 0xFF) << 32) |
                   ((long)(b[4] & 0xFF) << 24) | ((long)(b[5] & 0xFF) << 16) |
                   ((long)(b[6] & 0xFF) << 8) | (long)(b[7] & 0xFF);
        } else {
            return ((long)(b[7] & 0xFF) << 56) | ((long)(b[6] & 0xFF) << 48) |
                   ((long)(b[5] & 0xFF) << 40) | ((long)(b[4] & 0xFF) << 32) |
                   ((long)(b[3] & 0xFF) << 24) | ((long)(b[2] & 0xFF) << 16) |
                   ((long)(b[1] & 0xFF) << 8) | (long)(b[0] & 0xFF);
        }
    }

    public float readF32() {
        return Float.intBitsToFloat(readI32());
    }

    public double readF64() {
        return Double.longBitsToDouble(readI64());
    }

    // Byte array reads

    public byte[] readBytes(int length) {
        if (length < 0 || length > data.length - position) {
            throw new IndexOutOfBoundsException(
                "Cannot read " + length + " bytes at position " + position +
                " (data length: " + data.length + ", remaining: " + (data.length - position) + ")");
        }
        byte[] result = new byte[length];
        System.arraycopy(data, position, result, 0, length);
        position += length;
        return result;
    }

    public byte[] peekBytes(int length) {
        if (length < 0 || length > data.length - position) {
            throw new IndexOutOfBoundsException(
                "Cannot peek " + length + " bytes at position " + position +
                " (data length: " + data.length + ", remaining: " + (data.length - position) + ")");
        }
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
        byte[] b = readBytes(4);
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) |
               ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
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
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) |
               ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    public static String fourCCToString(int fourcc) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) ((fourcc >> 24) & 0xFF);
        bytes[1] = (byte) ((fourcc >> 16) & 0xFF);
        bytes[2] = (byte) ((fourcc >> 8) & 0xFF);
        bytes[3] = (byte) (fourcc & 0xFF);
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
            if (position >= data.length) return value;
            b = data[position++] & 0xFF;
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
                return 0.0; // Exponent out of range for double
            }
            f64exp = normexp + 0x3FF;
        }

        long f64bits = (sign << 63) | (f64exp << 52) | (fraction >> 11);
        return Double.longBitsToDouble(f64bits);
    }

    // Zlib decompression

    public byte[] readZlibBytes(int compressedLength) {
        byte[] compressed = readBytes(compressedLength);
        return decompressZlib(compressed);
    }

    /**
     * Decompress zlib-compressed data.
     * Uses array-based growing buffer instead of ByteBuffer for TeaVM WASM compatibility.
     */
    public byte[] decompressZlib(byte[] compressed) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        byte[] output = new byte[Math.max(compressed.length * 4, 4096)];
        int outputPos = 0;
        byte[] buffer = new byte[4096];

        try {
            int zeroCount = 0; // Safety: detect stalled decompression
            int maxOutput = Math.max(compressed.length * 20, 16 * 1024 * 1024); // 20x or 16MB
            long deadline = System.currentTimeMillis() + 2000; // 2s max
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput()) {
                        break;
                    }
                    // Safety: if inflate returns 0 but not finished and doesn't need input,
                    // we're stuck (possible TeaVM WASM zlib edge case). Break after a few tries.
                    if (++zeroCount > 3) {
                        break;
                    }
                    continue;
                }
                zeroCount = 0;
                // Grow output array if needed
                while (outputPos + count > output.length) {
                    byte[] newOutput = new byte[output.length * 2];
                    System.arraycopy(output, 0, newOutput, 0, outputPos);
                    output = newOutput;
                }
                System.arraycopy(buffer, 0, output, outputPos, count);
                outputPos += count;
                // Safety limits: prevent runaway decompression
                if (outputPos > maxOutput || System.currentTimeMillis() > deadline) {
                    break;
                }
                // Check global parse deadline
                if (com.libreshockwave.DirectorFile.isParseTimedOut()) {
                    break;
                }
            }
        } catch (java.util.zip.DataFormatException e) {
            // Return whatever was decompressed so far instead of throwing
            // (WASM: exceptions → unreachable trap)
        } catch (Throwable e) {
            // Catch any unexpected errors during decompression
        } finally {
            inflater.end();
        }

        if (outputPos == output.length) {
            return output;
        }
        byte[] result = new byte[outputPos];
        System.arraycopy(output, 0, result, 0, outputPos);
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
