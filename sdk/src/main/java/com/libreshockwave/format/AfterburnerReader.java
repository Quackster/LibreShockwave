package com.libreshockwave.format;

import com.libreshockwave.io.BinaryReader;

import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for Afterburner-compressed Director files (.dcr).
 * Handles the FGDM/FGDC container format with zlib compression.
 */
public class AfterburnerReader {

    private final BinaryReader reader;
    private final ByteOrder byteOrder;

    // Parsed metadata
    private int directorVersion;
    private int imapVersion;
    private String versionString;

    // Compression types from Fcdr chunk
    private final List<MoaID> compressionTypes = new ArrayList<>();

    // Resource map from ABMP chunk
    private final Map<Integer, ChunkInfo> chunkInfoMap = new LinkedHashMap<>();

    // ILS (Initial Load Segment) data
    private byte[] ilsData;
    private int ilsBodyOffset;

    // Cached chunk data extracted from ILS
    private final Map<Integer, byte[]> cachedChunkData = new HashMap<>();

    public AfterburnerReader(BinaryReader reader, ByteOrder byteOrder) {
        this.reader = reader;
        this.byteOrder = byteOrder;
    }

    /**
     * Parse the Afterburner map and prepare for chunk extraction.
     */
    public void parse() throws IOException {
        // Read Fver (File Version) chunk
        readFverChunk();

        // Read Fcdr (Compression Directory) chunk
        readFcdrChunk();

        // Read ABMP (Afterburner Map) chunk
        readAbmpChunk();

        // Read FGEI/ILS (Initial Load Segment) chunk
        readIlsChunk();
    }

    /**
     * Read the Fver (File Version) chunk.
     */
    private void readFverChunk() throws IOException {
        String fourcc = readFourCCOrdered();
        if (!fourcc.equals("Fver")) {
            throw new IOException("Expected Fver chunk, got: " + fourcc);
        }

        int length = readVarInt();
        int endPos = reader.getPosition() + length;

        // Sanity check - endPos should be within file bounds
        if (endPos > reader.length()) {
            throw new IOException("Fver chunk length " + length + " extends past end of file (endPos=" + endPos + ", fileLen=" + reader.length() + ")");
        }

        // Read version fields
        imapVersion = readVarInt();
        directorVersion = readVarInt();

        // Version string (optional, v0x501+)
        if (reader.getPosition() < endPos && reader.bytesLeft() > 0) {
            int strLen = readVarInt();
            int maxStrLen = endPos - reader.getPosition();
            if (strLen > 0 && strLen <= maxStrLen && strLen < 10000) {
                byte[] strBytes = reader.readBytes(strLen);
                versionString = new String(strBytes).trim();
            }
        }

        // Skip any remaining data (but don't seek past file end)
        if (endPos <= reader.length()) {
            reader.seek(endPos);
        }
    }

    /**
     * Read the Fcdr (Compression Directory) chunk.
     */
    private void readFcdrChunk() throws IOException {
        String fourcc = readFourCCOrdered();
        if (!fourcc.equals("Fcdr")) {
            throw new IOException("Expected Fcdr chunk, got: " + fourcc);
        }

        int compressedLength = readVarInt();
        byte[] compressedData = reader.readBytes(compressedLength);
        byte[] data = reader.decompressZlib(compressedData);

        BinaryReader fcdrReader = new BinaryReader(data, byteOrder);

        // Read compression type count
        int typeCount = fcdrReader.readUnsignedShort();

        // Read MoaID compression types
        for (int i = 0; i < typeCount; i++) {
            MoaID moaId = MoaID.read(fcdrReader);
            compressionTypes.add(moaId);
        }

        // Read compression type description strings (null-terminated)
        for (int i = 0; i < typeCount; i++) {
            int b;
            while ((b = fcdrReader.readUnsignedByte()) != 0) {
                // Description string is informational only, not stored
            }
        }
    }

    /**
     * Read the ABMP (Afterburner Map) chunk.
     */
    private void readAbmpChunk() throws IOException {
        String fourcc = readFourCCOrdered();
        if (!fourcc.equals("ABMP")) {
            throw new IOException("Expected ABMP chunk, got: " + fourcc);
        }

        int chunkLength = readVarInt();
        int chunkEndPos = reader.getPosition() + chunkLength;

        // Read header fields: unknown VarInt and uncompressed length VarInt
        readVarInt(); // unk
        readVarInt(); // uncompLength

        // Read remaining bytes as compressed data
        int compressedLength = chunkEndPos - reader.getPosition();
        byte[] compressedData = reader.readBytes(compressedLength);
        byte[] data = reader.decompressZlib(compressedData);

        BinaryReader abmpReader = new BinaryReader(data, byteOrder);

        // Unknown fields
        readVarInt(abmpReader); // unk1
        readVarInt(abmpReader); // unk2

        // Resource count
        int resCount = readVarInt(abmpReader);

        // Read resource entries
        // Note: offset can be -1 (0xFFFFFFFF as VarInt), meaning "accumulate from previous"
        // The first entry (ILS) describes the ILS blob itself and uses offset=0
        // Subsequent entries are chunks within the DECOMPRESSED ILS
        int runningOffset = 0;
        for (int i = 0; i < resCount; i++) {
            int resId = readVarInt(abmpReader);
            int rawOffset = readVarInt(abmpReader);
            int compSize = readVarInt(abmpReader);
            int uncompSize = readVarInt(abmpReader);
            int compressionTypeIndex = readVarInt(abmpReader);
            // Tag is stored as a u32 in the file's byte order. In LE files, tags are
            // byte-swapped (e.g., "STXT" stored as 54 58 54 53). readI32() with file's
            // endian produces the correct integer, and fourCCToString converts it back.
            int tagInt = abmpReader.readI32();
            String tag = BinaryReader.fourCCToString(tagInt);

            // Calculate actual offset
            int offset;
            if (rawOffset == 0 && i == 0 && tag.trim().equals("ILS")) {
                // First entry with offset=0 and tag ILS is the ILS descriptor
                // It doesn't have a real offset within the decompressed data
                offset = 0;  // Special: this entry is meta-info, not extractable
            } else if (rawOffset == -1) {
                // -1 means "accumulate from previous"
                offset = runningOffset;
                runningOffset = offset + compSize;
            } else {
                offset = rawOffset;
                runningOffset = offset + compSize;
            }

            MoaID compressionType = compressionTypeIndex < compressionTypes.size()
                ? compressionTypes.get(compressionTypeIndex)
                : MoaID.NULL_COMPRESSION;

            ChunkInfo info = new ChunkInfo(resId, tag, offset, compSize, uncompSize, compressionType);
            chunkInfoMap.put(resId, info);
        }
    }

    /**
     * Read the FGEI/ILS (Initial Load Segment) chunk.
     */
    private void readIlsChunk() throws IOException {
        String fourcc = readFourCCOrdered();
        if (!fourcc.equals("FGEI")) {
            throw new IOException("Expected FGEI chunk, got: " + fourcc);
        }

        // The ILS entry (ID=2) in ABMP tells us the compressed/uncompressed sizes
        ChunkInfo ilsInfo = chunkInfoMap.get(2);
        if (ilsInfo == null) {
            throw new IOException("No ILS entry (ID=2) found in ABMP");
        }

        // Read the unknown field (usually 0)
        readVarInt();

        // Store the position where ILS body starts
        ilsBodyOffset = reader.getPosition();

        // Read and decompress the ILS data using size from ABMP
        byte[] compressedData = reader.readBytes(ilsInfo.compressedSize());
        ilsData = reader.decompressZlib(compressedData);

        // Parse the ILS stream: it contains (resId, data) pairs
        // where data length comes from ABMP chunkInfo
        BinaryReader ilsReader = new BinaryReader(ilsData, byteOrder);
        while (!ilsReader.eof() && ilsReader.bytesLeft() > 0) {
            int resId = readVarInt(ilsReader);
            ChunkInfo info = chunkInfoMap.get(resId);
            if (info == null) {
                break;
            }

            // Read the chunk data using length from ABMP
            int dataLen = info.compressedSize();
            if (ilsReader.bytesLeft() < dataLen) {
                break;
            }

            byte[] chunkData = ilsReader.readBytes(dataLen);
            cachedChunkData.put(resId, chunkData);
        }
    }

    /**
     * Get chunk data by resource ID.
     */
    public byte[] getChunkData(int resourceId) throws IOException {
        // Skip the ILS meta entry (ID=2) - it describes the ILS blob, not a chunk
        if (resourceId == 2) {
            ChunkInfo info = chunkInfoMap.get(resourceId);
            if (info != null && info.fourCC().trim().equals("ILS")) {
                return ilsData;  // Return the decompressed ILS data itself
            }
        }

        // Check if already cached (from ILS parsing)
        if (cachedChunkData.containsKey(resourceId)) {
            byte[] chunkData = cachedChunkData.get(resourceId);

            // Decompress if needed (check if sizes differ)
            ChunkInfo info = chunkInfoMap.get(resourceId);
            if (info != null && info.compressedSize() != info.uncompressedSize()) {
                if (info.isZlibCompressed()) {
                    chunkData = reader.decompressZlib(chunkData);
                }
            }
            return chunkData;
        }

        ChunkInfo info = chunkInfoMap.get(resourceId);
        if (info == null) {
            throw new IOException("Unknown resource ID: " + resourceId);
        }

        // For non-ILS chunks, read from the remaining file data after ILS
        // The offset in ABMP is relative to ilsBodyOffset
        int fileOffset = ilsBodyOffset + info.offset();
        if (fileOffset >= reader.length()) {
            throw new IOException("Chunk " + resourceId + " (" + info.fourCC() +
                ") offset " + fileOffset + " exceeds file length " + reader.length());
        }

        // Save current position and seek to chunk
        int savedPos = reader.getPosition();
        reader.seek(fileOffset);

        byte[] chunkData = reader.readBytes(info.compressedSize());

        // Restore position
        reader.seek(savedPos);

        // Decompress if needed
        if (info.compressedSize() != info.uncompressedSize() && info.isZlibCompressed()) {
            chunkData = reader.decompressZlib(chunkData);
        }

        // Cache for future use
        cachedChunkData.put(resourceId, chunkData);
        return chunkData;
    }

    /**
     * Get chunk data by FourCC type.
     * Returns the first matching chunk.
     */
    public byte[] getChunkDataByType(String fourCC) throws IOException {
        for (ChunkInfo info : chunkInfoMap.values()) {
            if (info.fourCC().equals(fourCC)) {
                return getChunkData(info.resourceId());
            }
        }
        throw new IOException("Chunk type not found: " + fourCC);
    }

    /**
     * Get all chunk infos.
     */
    public Collection<ChunkInfo> getChunkInfos() {
        return Collections.unmodifiableCollection(chunkInfoMap.values());
    }

    /**
     * Get chunk info by resource ID.
     */
    public ChunkInfo getChunkInfo(int resourceId) {
        return chunkInfoMap.get(resourceId);
    }

    /**
     * Get all chunks of a specific type.
     */
    public List<ChunkInfo> getChunksByType(String fourCC) {
        List<ChunkInfo> result = new ArrayList<>();
        for (ChunkInfo info : chunkInfoMap.values()) {
            if (info.fourCC().equals(fourCC)) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Read a FourCC string respecting the byte order.
     * For little-endian files, the FourCC is stored reversed.
     */
    private String readFourCCOrdered() {
        return readFourCCOrdered(reader);
    }

    /**
     * Read a FourCC string from a specific reader, respecting the byte order.
     */
    private String readFourCCOrdered(BinaryReader r) {
        byte[] bytes = r.readBytes(4);
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            // Reverse the bytes for little-endian
            byte temp = bytes[0];
            bytes[0] = bytes[3];
            bytes[3] = temp;
            temp = bytes[1];
            bytes[1] = bytes[2];
            bytes[2] = temp;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Read a variable-length integer (VarInt).
     * Used extensively in Afterburner format.
     */
    private int readVarInt() throws IOException {
        return readVarInt(reader);
    }

    /**
     * Read a variable-length integer using MSB-first encoding.
     * High bit set means more bytes follow.
     */
    private int readVarInt(BinaryReader r) throws IOException {
        int value = 0;
        int b;
        do {
            b = r.readUnsignedByte();
            value = (value << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0);
        return value;
    }

    /**
     * Calculate size of a VarInt value.
     */
    private int getVarIntSize(int value) {
        int size = 1;
        while (value >= 0x80) {
            size++;
            value >>= 7;
        }
        return size;
    }

    // Getters

    public int getDirectorVersion() {
        return directorVersion;
    }

    public int getImapVersion() {
        return imapVersion;
    }

    public String getVersionString() {
        return versionString;
    }

    public List<MoaID> getCompressionTypes() {
        return Collections.unmodifiableList(compressionTypes);
    }

    public int getChunkCount() {
        return chunkInfoMap.size();
    }
}
