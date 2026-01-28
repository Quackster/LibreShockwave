package com.libreshockwave.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Endian-aware binary writer for Director file formats.
 * Counterpart to BinaryReader, supports both big-endian (Macintosh) and little-endian (Windows) byte orders.
 */
public class BinaryWriter {

    private byte[] data;
    private int position;
    private int highWaterMark;  // Tracks the highest position written to
    private ByteOrder order;

    public BinaryWriter(int initialCapacity) {
        this.data = new byte[initialCapacity];
        this.position = 0;
        this.highWaterMark = 0;
        this.order = ByteOrder.BIG_ENDIAN;
    }

    public BinaryWriter(byte[] data) {
        this.data = data;
        this.position = 0;
        this.highWaterMark = 0;
        this.order = ByteOrder.BIG_ENDIAN;
    }

    public BinaryWriter(byte[] data, ByteOrder order) {
        this.data = data;
        this.position = 0;
        this.highWaterMark = 0;
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

    // Position control

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void seek(int offset) {
        this.position = offset;
    }

    public int length() {
        return data.length;
    }

    /**
     * Get the underlying byte array.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Get a copy of the data up to the highest written position.
     */
    public byte[] toByteArray() {
        byte[] result = new byte[highWaterMark];
        System.arraycopy(data, 0, result, 0, highWaterMark);
        return result;
    }

    /**
     * Get a copy of all data in the buffer (full allocated size).
     */
    public byte[] toByteArrayFull() {
        byte[] result = new byte[data.length];
        System.arraycopy(data, 0, result, 0, data.length);
        return result;
    }

    // Ensure capacity
    private void ensureCapacity(int additionalBytes) {
        if (position + additionalBytes > data.length) {
            int newCapacity = Math.max(data.length * 2, position + additionalBytes);
            byte[] newData = new byte[newCapacity];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }
    }

    // Update high water mark
    private void updateHighWaterMark() {
        if (position > highWaterMark) {
            highWaterMark = position;
        }
    }

    // Primitive writes

    public void writeI8(byte value) {
        ensureCapacity(1);
        data[position++] = value;
        updateHighWaterMark();
    }

    public void writeU8(int value) {
        ensureCapacity(1);
        data[position++] = (byte) (value & 0xFF);
        updateHighWaterMark();
    }

    public void writeI16(short value) {
        ensureCapacity(2);
        ByteBuffer buffer = ByteBuffer.allocate(2).order(order).putShort(value);
        System.arraycopy(buffer.array(), 0, data, position, 2);
        position += 2;
        updateHighWaterMark();
    }

    public void writeU16(int value) {
        writeI16((short) (value & 0xFFFF));
    }

    public void writeI32(int value) {
        ensureCapacity(4);
        ByteBuffer buffer = ByteBuffer.allocate(4).order(order).putInt(value);
        System.arraycopy(buffer.array(), 0, data, position, 4);
        position += 4;
        updateHighWaterMark();
    }

    public void writeU32(long value) {
        writeI32((int) (value & 0xFFFFFFFFL));
    }

    public void writeI64(long value) {
        ensureCapacity(8);
        ByteBuffer buffer = ByteBuffer.allocate(8).order(order).putLong(value);
        System.arraycopy(buffer.array(), 0, data, position, 8);
        position += 8;
        updateHighWaterMark();
    }

    public void writeF32(float value) {
        ensureCapacity(4);
        ByteBuffer buffer = ByteBuffer.allocate(4).order(order).putFloat(value);
        System.arraycopy(buffer.array(), 0, data, position, 4);
        position += 4;
        updateHighWaterMark();
    }

    public void writeF64(double value) {
        ensureCapacity(8);
        ByteBuffer buffer = ByteBuffer.allocate(8).order(order).putDouble(value);
        System.arraycopy(buffer.array(), 0, data, position, 8);
        position += 8;
        updateHighWaterMark();
    }

    // Byte array writes

    public void writeBytes(byte[] bytes) {
        ensureCapacity(bytes.length);
        System.arraycopy(bytes, 0, data, position, bytes.length);
        position += bytes.length;
        updateHighWaterMark();
    }

    public void writeBytes(byte[] bytes, int offset, int length) {
        ensureCapacity(length);
        System.arraycopy(bytes, offset, data, position, length);
        position += length;
        updateHighWaterMark();
    }

    // FourCC writes (4-byte ASCII identifier)

    /**
     * Write a FourCC as a big-endian integer.
     * Always writes as big-endian regardless of the writer's byte order.
     */
    public void writeFourCC(int fourcc) {
        ensureCapacity(4);
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(fourcc);
        System.arraycopy(buffer.array(), 0, data, position, 4);
        position += 4;
        updateHighWaterMark();
    }

    /**
     * Write a FourCC from a String.
     */
    public void writeFourCCString(String fourcc) {
        if (fourcc.length() != 4) {
            throw new IllegalArgumentException("FourCC must be exactly 4 characters");
        }
        byte[] bytes = fourcc.getBytes(StandardCharsets.US_ASCII);
        writeBytes(bytes);  // writeBytes already calls updateHighWaterMark
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

    // String writes

    public void writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeBytes(bytes);
    }

    public void writePascalString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 255) {
            throw new IllegalArgumentException("Pascal string too long (max 255 bytes)");
        }
        writeU8(bytes.length);
        writeBytes(bytes);
    }

    public void writeNullTerminatedString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeBytes(bytes);
        writeU8(0);
    }

    // Alias methods for compatibility

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
