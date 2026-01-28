package com.libreshockwave.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writer for CastMemberChunk (CASt) data with script text restoration.
 * Based on ProjectorRays CastMemberChunk/CastInfoChunk implementation.
 */
public class CastMemberChunkWriter {

    /**
     * Rewrite a CastMemberChunk with updated script text.
     *
     * @param originalData The original raw CASt chunk data
     * @param scriptText The decompiled script text to include
     * @param version Director version
     * @param bigEndian True if file is big-endian (RIFX), false for little-endian (XFIR)
     * @return The new raw chunk data with script text
     */
    public static byte[] rewriteWithScriptText(byte[] originalData, String scriptText, int version, boolean bigEndian) {
        if (originalData.length < 12) {
            return originalData; // Too short to process
        }

        // CASt chunk internal data is always big-endian, regardless of file container format
        ByteBuffer buf = ByteBuffer.wrap(originalData);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Parse the original structure
        int memberType;
        int infoLen;
        int specificDataLen;
        byte[] specificData;
        int infoStart;

        if (version >= 1024) { // Director 6+
            memberType = buf.getInt(0);
            infoLen = buf.getInt(4);
            specificDataLen = buf.getInt(8);
            infoStart = 12;
        } else {
            // Director 5 and earlier
            specificDataLen = buf.getShort(0) & 0xFFFF;
            int totalSize = buf.getInt(2);
            memberType = buf.get(6) & 0xFF;
            infoLen = totalSize - specificDataLen;
            infoStart = 7;
            if (originalData.length > 7) {
                infoStart = 8; // Has flags byte
            }
        }

        // Read specific data
        buf.position(infoStart);
        specificData = new byte[Math.min(specificDataLen, buf.remaining())];
        buf.get(specificData);

        // Read original info data
        byte[] originalInfo = new byte[Math.min(infoLen, buf.remaining())];
        if (buf.remaining() > 0) {
            buf.get(originalInfo);
        }

        // Create new info with script text (always big-endian)
        byte[] newInfo = createInfoWithScriptText(originalInfo, scriptText);

        // Build new chunk (always big-endian)
        return buildChunk(memberType, specificData, newInfo, version);
    }

    /**
     * Create a new CastInfoChunk (ListChunk) with the script text.
     * Preserves the original header structure and only modifies the items.
     * CASt chunk data is always big-endian.
     */
    private static byte[] createInfoWithScriptText(byte[] originalInfo, String scriptText) {
        if (originalInfo.length < 20) {
            return originalInfo; // Too short to parse
        }

        ByteBuffer origBuf = ByteBuffer.wrap(originalInfo);
        origBuf.order(ByteOrder.BIG_ENDIAN);

        // Read dataOffset - first try as 32-bit, then fall back to 16-bit if invalid
        int dataOffset = origBuf.getInt(0);

        // If 32-bit value seems wrong, try 16-bit (used in some versions)
        if (dataOffset <= 0 || dataOffset >= originalInfo.length) {
            dataOffset = origBuf.getShort(0) & 0xFFFF;
        }

        if (dataOffset <= 0 || dataOffset >= originalInfo.length) {
            return originalInfo; // Can't process
        }

        // Copy the original header (everything before dataOffset)
        byte[] header = new byte[dataOffset];
        System.arraycopy(originalInfo, 0, header, 0, dataOffset);

        // Parse offset table at dataOffset
        origBuf.position(dataOffset);

        int offsetTableLen = origBuf.getShort() & 0xFFFF;

        // Read existing items
        List<byte[]> items = new ArrayList<>();

        if (offsetTableLen > 0) {
            // Read offsets
            int[] offsets = new int[offsetTableLen];
            for (int i = 0; i < offsetTableLen; i++) {
                offsets[i] = origBuf.getInt();
            }

            int itemsLen = origBuf.getInt();
            int itemsStart = origBuf.position();

            for (int i = 0; i < offsetTableLen; i++) {
                int itemStart = offsets[i];
                int itemEnd = (i + 1 < offsetTableLen) ? offsets[i + 1] : itemsLen;
                int itemLen = itemEnd - itemStart;

                if (itemLen > 0 && itemsStart + itemStart + itemLen <= originalInfo.length) {
                    byte[] itemData = new byte[itemLen];
                    System.arraycopy(originalInfo, itemsStart + itemStart, itemData, 0, itemLen);
                    items.add(itemData);
                } else {
                    items.add(new byte[0]);
                }
            }
        }

        // Ensure we have at least 2 items (scriptText at 0, name at 1)
        while (items.size() < 2) {
            items.add(new byte[0]);
        }

        // Update item 0 with script text
        byte[] scriptBytes = scriptText.getBytes(StandardCharsets.ISO_8859_1);
        items.set(0, scriptBytes);

        // Rebuild the info chunk with original header preserved
        return buildInfoChunk(header, items);
    }

    /**
     * Build a CastInfoChunk (ListChunk) from header and items.
     * Preserves the original header and rebuilds the ListChunk portion.
     * CASt chunk data is always big-endian.
     */
    private static byte[] buildInfoChunk(byte[] header, List<byte[]> items) {
        // Calculate item offsets and total items length
        int[] offsets = new int[items.size()];
        int currentOffset = 0;
        for (int i = 0; i < items.size(); i++) {
            offsets[i] = currentOffset;
            currentOffset += items.get(i).length;
        }
        int itemsLen = currentOffset;

        // Calculate sizes
        int offsetTableSize = 2 + (items.size() * 4); // offsetTableLen(2) + offsets(4 each)
        int totalSize = header.length + offsetTableSize + 4 + itemsLen; // +4 for itemsLen field

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Write original header (with dataOffset already set correctly)
        buf.put(header);

        // Write offset table
        buf.putShort((short) items.size());
        for (int offset : offsets) {
            buf.putInt(offset);
        }

        // Write items length and items
        buf.putInt(itemsLen);
        for (byte[] item : items) {
            buf.put(item);
        }

        return buf.array();
    }

    /**
     * Build a complete CASt chunk from components.
     * CASt chunk data is always big-endian.
     */
    private static byte[] buildChunk(int memberType, byte[] specificData, byte[] info, int version) {
        if (version >= 1024) { // Director 6+
            // Header: type(4) + infoLen(4) + specificDataLen(4) = 12 bytes
            int totalSize = 12 + info.length + specificData.length;
            ByteBuffer buf = ByteBuffer.allocate(totalSize);
            buf.order(ByteOrder.BIG_ENDIAN);

            buf.putInt(memberType);
            buf.putInt(info.length);
            buf.putInt(specificData.length);
            buf.put(specificData);  // specificData comes first after header
            buf.put(info);          // info comes after specificData

            return buf.array();
        } else {
            // Director 5 format
            // Header: specificDataLen(2) + totalSize(4) + type(1) + flags(1) = 8 bytes
            int totalSize = specificData.length + info.length;
            ByteBuffer buf = ByteBuffer.allocate(8 + specificData.length + info.length);
            buf.order(ByteOrder.BIG_ENDIAN);

            buf.putShort((short) specificData.length);
            buf.putInt(totalSize);
            buf.put((byte) memberType);
            buf.put((byte) 0); // flags
            buf.put(specificData);
            buf.put(info);

            return buf.array();
        }
    }
}
