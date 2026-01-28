package com.libreshockwave.io;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ConfigChunk;
import com.libreshockwave.format.ChunkType;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Writer for Director/Shockwave files.
 * Reconstructs a valid RIFX file from the parsed chunks.
 * Based on ProjectorRays implementation.
 */
public class DirectorFileWriter {

    private static final int RIFX_HEADER_SIZE = 12;
    private static final int CHUNK_HEADER_SIZE = 8;
    private static final int IMAP_SIZE = 24;
    private static final int MMAP_HEADER_SIZE = 24;
    private static final int MMAP_ENTRY_SIZE = 20;

    private final DirectorFile file;
    private final ByteOrder endian;
    private final Map<Integer, byte[]> rawChunkData;
    private final Map<Integer, DirectorFile.ChunkInfo> chunkInfoMap;

    // Memory map entries for writing
    private final List<MemoryMapEntry> memoryMap = new ArrayList<>();

    /**
     * Memory map entry for RIFX files.
     */
    public static class MemoryMapEntry {
        public int fourCC;
        public int length;
        public int offset;
        public short flags;
        public short unknown0;
        public int next;

        public MemoryMapEntry(int fourCC, int length, int offset, short flags) {
            this.fourCC = fourCC;
            this.length = length;
            this.offset = offset;
            this.flags = flags;
            this.unknown0 = 0;
            this.next = 0;
        }
    }

    public DirectorFileWriter(DirectorFile file, Map<Integer, byte[]> rawChunkData) {
        this.file = file;
        this.endian = file.getEndian();
        this.rawChunkData = rawChunkData;
        this.chunkInfoMap = new HashMap<>();
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            this.chunkInfoMap.put(info.id(), info);
        }
    }

    /**
     * Write the file to the specified path.
     */
    public void writeToFile(Path path) throws IOException {
        byte[] data = write();
        Files.write(path, data);
    }

    /**
     * Write the file and return the byte array.
     */
    public byte[] write() throws IOException {
        // Generate the memory map
        generateMemoryMap();

        // Calculate total file size
        int totalSize = calculateTotalSize();

        // Create the output buffer
        BinaryWriter writer = new BinaryWriter(totalSize);
        writer.setOrder(endian);

        // Write RIFX header (chunk 0)
        writeRifxHeader(writer);

        // Write IMAP (chunk 1)
        writeImap(writer);

        // Write MMAP (chunk 2)
        writeMmap(writer);

        // Write all other chunks using the ID remapping
        for (Map.Entry<Integer, Integer> mapping : idRemapping.entrySet()) {
            int originalId = mapping.getKey();
            int newId = mapping.getValue();
            writeChunk(writer, newId, originalId);
        }

        return writer.toByteArray();
    }

    // Map from original chunk ID to new ID in output file
    private final Map<Integer, Integer> idRemapping = new HashMap<>();

    /**
     * Generate the memory map for the output file.
     * For Afterburner files, chunk IDs are remapped to avoid conflicts with RIFX/imap/mmap.
     */
    private void generateMemoryMap() {
        memoryMap.clear();
        idRemapping.clear();

        // Collect all chunks that have data, sorted by original ID
        List<DirectorFile.ChunkInfo> chunksWithData = new ArrayList<>();
        for (DirectorFile.ChunkInfo info : chunkInfoMap.values()) {
            // Skip ILS meta-entry for Afterburner files
            String fourccStr = BinaryReader.fourCCToString(info.fourcc()).trim();
            if (fourccStr.equals("ILS")) {
                continue;
            }
            if (rawChunkData.containsKey(info.id())) {
                chunksWithData.add(info);
            }
        }

        // Sort by original ID for consistent output
        chunksWithData.sort(Comparator.comparingInt(DirectorFile.ChunkInfo::id));

        // Calculate total entries: RIFX(0) + imap(1) + mmap(2) + all chunks
        int totalEntries = 3 + chunksWithData.size();

        // Initialize all entries
        for (int i = 0; i < totalEntries; i++) {
            memoryMap.add(new MemoryMapEntry(fourCC("free"), 0, 0, (short) 12));
        }

        // Calculate offsets
        int nextOffset = 0;

        // Entry 0: RIFX container
        MemoryMapEntry rifxEntry = memoryMap.get(0);
        rifxEntry.fourCC = fourCC("RIFX");
        rifxEntry.offset = nextOffset;
        rifxEntry.flags = 1;
        nextOffset += RIFX_HEADER_SIZE;

        // Entry 1: imap
        MemoryMapEntry imapEntry = memoryMap.get(1);
        imapEntry.fourCC = fourCC("imap");
        imapEntry.length = IMAP_SIZE;
        imapEntry.offset = nextOffset;
        imapEntry.flags = 1;
        nextOffset += IMAP_SIZE + CHUNK_HEADER_SIZE;

        // Entry 2: mmap (size calculated based on totalEntries)
        int mmapSize = MMAP_HEADER_SIZE + (totalEntries * MMAP_ENTRY_SIZE);
        MemoryMapEntry mmapEntry = memoryMap.get(2);
        mmapEntry.fourCC = fourCC("mmap");
        mmapEntry.length = mmapSize;
        mmapEntry.offset = nextOffset;
        mmapEntry.flags = 0;
        nextOffset += mmapSize + CHUNK_HEADER_SIZE;

        // All other chunks - assign new IDs starting from 3
        int newId = 3;
        for (DirectorFile.ChunkInfo info : chunksWithData) {
            idRemapping.put(info.id(), newId);

            MemoryMapEntry entry = memoryMap.get(newId);
            entry.fourCC = info.fourcc();
            entry.length = getChunkSize(info.id());
            entry.offset = nextOffset;
            entry.flags = 0;
            nextOffset += entry.length + CHUNK_HEADER_SIZE;

            newId++;
        }

        // Set RIFX length (total size minus first 8 bytes of RIFX header)
        rifxEntry.length = nextOffset - CHUNK_HEADER_SIZE;
    }

    /**
     * Get the size of a chunk's data.
     */
    private int getChunkSize(int id) {
        // Use the raw chunk data size
        byte[] data = rawChunkData.get(id);
        if (data != null) {
            return data.length;
        }

        // Fall back to ChunkInfo
        DirectorFile.ChunkInfo info = chunkInfoMap.get(id);
        if (info != null) {
            return info.uncompressedLength();
        }

        return 0;
    }

    /**
     * Calculate the total file size.
     */
    private int calculateTotalSize() {
        if (memoryMap.isEmpty()) {
            generateMemoryMap();
        }
        return memoryMap.get(0).length + CHUNK_HEADER_SIZE;
    }

    /**
     * Write the RIFX header.
     */
    private void writeRifxHeader(BinaryWriter writer) {
        MemoryMapEntry entry = memoryMap.get(0);
        writer.seek(entry.offset);

        // Write container FourCC - RIFX for big-endian, XFIR for little-endian
        if (endian == ByteOrder.BIG_ENDIAN) {
            writer.writeFourCC(fourCC("RIFX"));
        } else {
            writer.writeFourCC(fourCC("XFIR"));
        }

        // Write file size (total size - 8 bytes for RIFX header)
        writer.writeI32(entry.length);

        // Write codec - MV93 for movies, MC95 for casts
        ChunkType movieType = file.getMovieType();
        int codec;
        if (movieType == ChunkType.MC95 || movieType == ChunkType.FGDC) {
            codec = fourCC("MC95");
        } else {
            codec = fourCC("MV93");
        }
        writer.writeI32(codec);
    }

    /**
     * Write the imap (Initial Map) chunk.
     */
    private void writeImap(BinaryWriter writer) {
        MemoryMapEntry entry = memoryMap.get(1);
        writer.seek(entry.offset);

        // Chunk header
        writer.writeFourCC(fourCC("imap"));
        writer.writeI32(entry.length);

        // imap data
        writer.writeI32(1);  // version
        writer.writeI32(memoryMap.get(2).offset);  // mmap offset

        // Director version
        ConfigChunk config = file.getConfig();
        int directorVersion = config != null ? config.directorVersion() : 0;
        // For D4 and earlier, directorVersion in imap is 0
        int imapDirVersion = (humanVersion(directorVersion) < 500) ? 0 : directorVersion;
        writer.writeI32(imapDirVersion);

        writer.writeI32(0);  // unused1
        writer.writeI32(0);  // unused2
        writer.writeI32(0);  // unused3
    }

    /**
     * Write the mmap (Memory Map) chunk.
     */
    private void writeMmap(BinaryWriter writer) {
        MemoryMapEntry entry = memoryMap.get(2);
        writer.seek(entry.offset);

        // Chunk header
        writer.writeFourCC(fourCC("mmap"));
        writer.writeI32(entry.length);

        // mmap header
        writer.writeI16((short) MMAP_HEADER_SIZE);  // headerLength
        writer.writeI16((short) MMAP_ENTRY_SIZE);   // entryLength
        writer.writeI32(memoryMap.size());          // chunkCountMax
        writer.writeI32(memoryMap.size());          // chunkCountUsed

        // Find free head
        int freeHead = -1;
        for (int i = memoryMap.size() - 1; i >= 0; i--) {
            if (memoryMap.get(i).fourCC == fourCC("free")) {
                freeHead = i;
                break;
            }
        }

        writer.writeI32(-1);       // junkHead
        writer.writeI32(-1);       // junkHead2
        writer.writeI32(freeHead); // freeHead

        // Write entries
        for (MemoryMapEntry mapEntry : memoryMap) {
            writer.writeFourCC(mapEntry.fourCC);
            writer.writeI32(mapEntry.length);
            writer.writeI32(mapEntry.offset);
            writer.writeI16(mapEntry.flags);
            writer.writeI16(mapEntry.unknown0);
            writer.writeI32(mapEntry.next);
        }
    }

    /**
     * Write a chunk.
     * @param newId The ID in the output memory map
     * @param originalId The original chunk ID (for looking up raw data)
     */
    private void writeChunk(BinaryWriter writer, int newId, int originalId) {
        MemoryMapEntry entry = memoryMap.get(newId);
        writer.seek(entry.offset);

        // Chunk header
        writer.writeFourCC(entry.fourCC);
        writer.writeI32(entry.length);

        // Chunk data - use original ID to look up raw data
        byte[] data = rawChunkData.get(originalId);
        if (data != null) {
            writer.writeBytes(data);
        }
    }

    /**
     * Convert a FourCC string to integer.
     */
    private static int fourCC(String s) {
        return BinaryReader.fourCC(s);
    }

    /**
     * Convert internal Director version to human-readable version.
     */
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
}
