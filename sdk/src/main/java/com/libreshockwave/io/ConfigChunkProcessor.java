package com.libreshockwave.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Processes ConfigChunk (DRCF) raw data for unprotection.
 * Based on ProjectorRays implementation.
 */
public class ConfigChunkProcessor {

    /**
     * Unprotect a config chunk by modifying the raw bytes.
     * Sets fileVersion = directorVersion and adjusts protection if needed.
     * Also recalculates the checksum.
     *
     * @param rawData The raw DRCF chunk data
     * @param bigEndian True if the data is big-endian (always true for DRCF)
     * @return The modified raw data
     */
    public static byte[] unprotect(byte[] rawData, boolean bigEndian) {
        if (rawData.length < 68) {
            return rawData; // Too short, can't process
        }

        byte[] result = rawData.clone();
        ByteBuffer buf = ByteBuffer.wrap(result);
        buf.order(ByteOrder.BIG_ENDIAN); // Config chunks are always big-endian

        // Read directorVersion at offset 36
        short directorVersion = buf.getShort(36);

        // Set fileVersion at offset 2 to directorVersion
        buf.putShort(2, directorVersion);

        // Read protection at offset 58
        short protection = buf.getShort(58);

        // If protection is divisible by 23, increment it
        if (protection % 23 == 0) {
            buf.putShort(58, (short)(protection + 1));
        }

        // Recalculate and write checksum
        int checksum = computeChecksum(result, bigEndian);
        buf.putInt(64, checksum);

        return result;
    }

    /**
     * Compute the checksum for a config chunk.
     * Based on ProjectorRays ConfigChunk::computeChecksum()
     */
    public static int computeChecksum(byte[] rawData, boolean bigEndian) {
        ByteBuffer buf = ByteBuffer.wrap(rawData);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Read all the fields needed for checksum
        int len = buf.getShort(0) & 0xFFFF;
        int fileVersion = buf.getShort(2) & 0xFFFF;
        int movieTop = buf.getShort(4);
        int movieLeft = buf.getShort(6);
        int movieBottom = buf.getShort(8);
        int movieRight = buf.getShort(10);
        int minMember = buf.getShort(12);
        int maxMember = buf.getShort(14);
        int field9 = buf.get(16) & 0xFF;
        int field10 = buf.get(17) & 0xFF;

        int directorVersion = buf.getShort(36) & 0xFFFF;
        int humanVer = humanVersion(directorVersion);

        int operand11;
        if (humanVer < 700) {
            operand11 = buf.getShort(18);
        } else {
            int d7StageColorG = buf.get(18) & 0xFF;
            int d7StageColorB = buf.get(19) & 0xFF;
            // Endianness affects byte order interpretation
            operand11 = bigEndian
                ? (short)((d7StageColorB << 8) | d7StageColorG)
                : (short)((d7StageColorG << 8) | d7StageColorB);
        }

        int commentFont = buf.getShort(20);
        int commentSize = buf.getShort(22);
        int commentStyle = buf.getShort(24) & 0xFFFF;

        int operand15;
        if (humanVer < 700) {
            operand15 = buf.getShort(26);
        } else {
            operand15 = buf.get(27) & 0xFF; // D7stageColorR
        }

        int bitDepth = buf.getShort(28);
        int field17 = buf.get(30) & 0xFF;
        int field18 = buf.get(31) & 0xFF;
        int field19 = buf.getInt(32);
        // directorVersion at 36 already read
        int field21 = buf.getShort(38);
        int field22 = buf.getInt(40);
        int field23 = buf.getInt(44);
        int field24 = buf.getInt(48);
        int field25 = buf.get(52);
        // field26 at 53 (not used in checksum)
        int frameRate = buf.getShort(54);
        int platform = buf.getShort(56);
        int protection = buf.getShort(58);

        // Compute checksum using unsigned 32-bit arithmetic
        long check = (len + 1) & 0xFFFFFFFFL;
        check = (check * (fileVersion + 2)) & 0xFFFFFFFFL;
        check = Integer.toUnsignedLong((int)(check / (movieTop + 3)));
        check = (check * (movieLeft + 4)) & 0xFFFFFFFFL;
        check = Integer.toUnsignedLong((int)(check / (movieBottom + 5)));
        check = (check * (movieRight + 6)) & 0xFFFFFFFFL;
        check = (check - (minMember + 7)) & 0xFFFFFFFFL;
        check = (check * (maxMember + 8)) & 0xFFFFFFFFL;
        check = (check - (field9 + 9)) & 0xFFFFFFFFL;
        check = (check - (field10 + 10)) & 0xFFFFFFFFL;
        check = (check + (operand11 + 11)) & 0xFFFFFFFFL;
        check = (check * (commentFont + 12)) & 0xFFFFFFFFL;
        check = (check + (commentSize + 13)) & 0xFFFFFFFFL;

        int operand14 = (humanVer < 800) ? ((commentStyle >> 8) & 0xFF) : commentStyle;
        check = (check * (operand14 + 14)) & 0xFFFFFFFFL;

        check = (check + (operand15 + 15)) & 0xFFFFFFFFL;
        check = (check + (bitDepth + 16)) & 0xFFFFFFFFL;
        check = (check + (field17 + 17)) & 0xFFFFFFFFL;
        check = (check * (field18 + 18)) & 0xFFFFFFFFL;
        check = (check + (field19 + 19)) & 0xFFFFFFFFL;
        check = (check * (directorVersion + 20)) & 0xFFFFFFFFL;
        check = (check + (field21 + 21)) & 0xFFFFFFFFL;
        check = (check + (field22 + 22)) & 0xFFFFFFFFL;
        check = (check + (field23 + 23)) & 0xFFFFFFFFL;
        check = (check + (field24 + 24)) & 0xFFFFFFFFL;
        check = (check * (field25 + 25)) & 0xFFFFFFFFL;
        check = (check + (frameRate + 26)) & 0xFFFFFFFFL;
        check = (check * (platform + 27)) & 0xFFFFFFFFL;
        check = (check * ((protection * 0xE06) + 0xFF450000L)) & 0xFFFFFFFFL;
        check = check ^ fourCC("ralf");

        return (int) check;
    }

    private static int humanVersion(int directorVersion) {
        if (directorVersion >= 1800) return 1200;  // D12
        if (directorVersion >= 1700) return 1150;  // D11.5
        if (directorVersion >= 1600) return 1100;  // D11
        if (directorVersion >= 1500) return 1000;  // D10
        if (directorVersion >= 1400) return 850;   // D8.5
        if (directorVersion >= 1300) return 800;   // D8
        if (directorVersion >= 1200) return 700;   // D7
        if (directorVersion >= 1100) return 600;   // D6
        if (directorVersion >= 1000) return 500;   // D5
        if (directorVersion >= 900) return 450;    // D4.5
        if (directorVersion >= 800) return 400;    // D4
        return 300; // D3 and earlier
    }

    private static int fourCC(String s) {
        return BinaryReader.fourCC(s);
    }
}
