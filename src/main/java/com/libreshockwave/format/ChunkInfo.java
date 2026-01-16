package com.libreshockwave.format;

/**
 * Information about a chunk in an Afterburner file.
 * Maps resource IDs to their location and compression info.
 */
public record ChunkInfo(
    int resourceId,           // Unique resource identifier
    String fourCC,            // Chunk type (e.g., "CAS*", "KEY*")
    int offset,               // Byte offset in ILS segment
    int compressedSize,       // Size in bytes (compressed)
    int uncompressedSize,     // Size after decompression
    MoaID compressionType     // Compression algorithm used
) {

    /**
     * Check if this chunk is compressed.
     */
    public boolean isCompressed() {
        return compressedSize != uncompressedSize && !compressionType.isNull();
    }

    /**
     * Check if this chunk uses zlib compression.
     */
    public boolean isZlibCompressed() {
        return compressionType.isZlib();
    }

    @Override
    public String toString() {
        return String.format("ChunkInfo[id=%d, type=%s, offset=%d, size=%d->%d, compression=%s]",
            resourceId, fourCC, offset, compressedSize, uncompressedSize,
            compressionType.isZlib() ? "zlib" :
            compressionType.isSound() ? "sound" :
            compressionType.isNull() ? "none" : "unknown");
    }
}
