package com.libreshockwave;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Debug test to compare saved files.
 */
public class DebugSaveTest {

    public static void main(String[] args) throws Exception {
        Path inputPath = Path.of("C:/Users/alexm/Desktop/lasm/test/fuse_client.cct");
        Path referencePath = Path.of("C:/SourceControl/LibreShockwave/fuse_client.cst");

        System.out.println("=== Loading original Afterburner file ===");
        DirectorFile file = DirectorFile.load(inputPath);

        System.out.println("Chunks in original: " + file.getAllChunkInfo().size());
        System.out.println("Raw chunk data entries: " + file.getRawChunkData().size());

        // Calculate total raw data size
        long totalRawSize = 0;
        for (byte[] data : file.getRawChunkData().values()) {
            totalRawSize += data.length;
        }
        System.out.println("Total raw chunk data size: " + totalRawSize + " bytes");

        // List all chunks with their sizes
        System.out.println("\nChunk details:");
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            byte[] rawData = file.getRawChunkData().get(info.id());
            int rawSize = rawData != null ? rawData.length : 0;
            String fourcc = com.libreshockwave.io.BinaryReader.fourCCToString(info.fourcc());
            System.out.printf("  ID %3d: %-4s offset=%6d length=%6d uncompressed=%6d raw=%6d%n",
                info.id(), fourcc, info.offset(), info.length(), info.uncompressedLength(), rawSize);
        }

        System.out.println("\n=== Reading reference file header ===");
        byte[] refBytes = Files.readAllBytes(referencePath);
        System.out.println("Reference file size: " + refBytes.length);

        // Reference file is little-endian (XFIR)
        ByteBuffer refBuf = ByteBuffer.wrap(refBytes).order(ByteOrder.LITTLE_ENDIAN);

        // Read RIFX/XFIR header - container tag is always readable as ASCII
        byte[] rifxTag = new byte[4];
        refBuf.get(rifxTag);
        System.out.println("Container: " + new String(rifxTag));

        int rifxLen = refBuf.getInt();
        System.out.println("RIFX length: " + rifxLen);

        byte[] codec = new byte[4];
        refBuf.get(codec);
        System.out.println("Codec: " + new String(codec));

        // Read imap - tag bytes are reversed in LE files
        byte[] imapTag = new byte[4];
        refBuf.get(imapTag);
        System.out.println("imap tag: " + new String(imapTag) + " (reversed: " +
            new String(new byte[]{imapTag[3], imapTag[2], imapTag[1], imapTag[0]}) + ")");

        int imapLen = refBuf.getInt();
        System.out.println("imap length: " + imapLen);

        int imapVersion = refBuf.getInt();
        System.out.println("imap version: " + imapVersion);

        int mmapOffset = refBuf.getInt();
        System.out.println("mmap offset: " + mmapOffset);

        // Read mmap
        refBuf.position(mmapOffset);
        byte[] mmapTag = new byte[4];
        refBuf.get(mmapTag);
        System.out.println("\nmmap tag: " + new String(mmapTag) + " (reversed: " +
            new String(new byte[]{mmapTag[3], mmapTag[2], mmapTag[1], mmapTag[0]}) + ")");

        int mmapLen = refBuf.getInt();
        System.out.println("mmap length: " + mmapLen);

        int headerLen = refBuf.getShort() & 0xFFFF;
        int entryLen = refBuf.getShort() & 0xFFFF;
        int chunkCountMax = refBuf.getInt();
        int chunkCountUsed = refBuf.getInt();

        System.out.println("mmap header length: " + headerLen);
        System.out.println("mmap entry length: " + entryLen);
        System.out.println("mmap chunk count max: " + chunkCountMax);
        System.out.println("mmap chunk count used: " + chunkCountUsed);

        int junkHead = refBuf.getInt();
        int junkHead2 = refBuf.getInt();
        int freeHead = refBuf.getInt();
        System.out.println("junkHead: " + junkHead + ", junkHead2: " + junkHead2 + ", freeHead: " + freeHead);

        System.out.println("\nFirst 30 mmap entries:");
        for (int i = 0; i < Math.min(30, chunkCountUsed); i++) {
            // FourCC bytes are stored reversed in LE files
            byte[] fourccBytes = new byte[4];
            refBuf.get(fourccBytes);
            String fourcc = new String(new byte[]{fourccBytes[3], fourccBytes[2], fourccBytes[1], fourccBytes[0]});

            int length = refBuf.getInt();
            int offset = refBuf.getInt();
            int flags = refBuf.getShort() & 0xFFFF;
            int unk = refBuf.getShort() & 0xFFFF;
            int next = refBuf.getInt();

            System.out.printf("  [%3d] %-4s len=%6d off=%6d flags=%d next=%d%n",
                i, fourcc, length, offset, flags, next);
        }

        // Count total mmap entries that are not free and collect chunk types
        int actualChunks = 0;
        java.util.Map<String, Integer> refChunkTypes = new java.util.HashMap<>();
        java.util.Map<String, Long> refChunkSizes = new java.util.HashMap<>();
        refBuf.position(mmapOffset + 8 + headerLen);
        for (int i = 0; i < chunkCountUsed; i++) {
            byte[] fourccBytes = new byte[4];
            refBuf.get(fourccBytes);
            String fourcc = new String(new byte[]{fourccBytes[3], fourccBytes[2], fourccBytes[1], fourccBytes[0]});
            int length = refBuf.getInt();
            refBuf.getInt(); // offset
            refBuf.getShort(); // flags
            refBuf.getShort(); // unk
            refBuf.getInt(); // next
            if (!fourcc.equals("free") && !fourcc.equals("junk")) {
                actualChunks++;
                refChunkTypes.merge(fourcc.trim(), 1, Integer::sum);
                refChunkSizes.merge(fourcc.trim(), (long)length, Long::sum);
            }
        }
        System.out.println("\nActual (non-free) chunks: " + actualChunks);

        System.out.println("\nReference file chunk type summary:");
        for (var entry : refChunkTypes.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            long totalSize = refChunkSizes.get(entry.getKey());
            System.out.printf("  %-4s: %3d chunks, %8d bytes total%n", entry.getKey(), entry.getValue(), totalSize);
        }

        // Compare with my chunk types
        java.util.Map<String, Integer> myChunkTypes = new java.util.HashMap<>();
        java.util.Map<String, Long> myChunkSizes = new java.util.HashMap<>();
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            String fourcc = com.libreshockwave.io.BinaryReader.fourCCToString(info.fourcc()).trim();
            if (!fourcc.equals("ILS")) {
                byte[] rawData = file.getRawChunkData().get(info.id());
                int size = rawData != null ? rawData.length : 0;
                myChunkTypes.merge(fourcc, 1, Integer::sum);
                myChunkSizes.merge(fourcc, (long)size, Long::sum);
            }
        }

        System.out.println("\nMy chunk type summary:");
        for (var entry : myChunkTypes.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            long totalSize = myChunkSizes.get(entry.getKey());
            System.out.printf("  %-4s: %3d chunks, %8d bytes total%n", entry.getKey(), entry.getValue(), totalSize);
        }

        System.out.println("\nSize differences by type:");
        for (var entry : refChunkTypes.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            String type = entry.getKey();
            int refCount = entry.getValue();
            long refSize = refChunkSizes.get(type);
            int myCount = myChunkTypes.getOrDefault(type, 0);
            long mySize = myChunkSizes.getOrDefault(type, 0L);
            if (refCount != myCount || refSize != mySize) {
                System.out.printf("  %-4s: ref=%3d/%8d  mine=%3d/%8d  diff=%+d/%+d%n",
                    type, refCount, refSize, myCount, mySize, myCount - refCount, mySize - refSize);
            }
        }

        // Test saving and show resulting chunk sizes
        System.out.println("\n=== Testing save with script restoration ===");
        java.nio.file.Path outputPath = java.nio.file.Path.of("test_output/debug_saved.cst");
        java.nio.file.Files.createDirectories(outputPath.getParent());
        file.save(outputPath);

        byte[] savedBytes = java.nio.file.Files.readAllBytes(outputPath);
        System.out.println("Saved file size: " + savedBytes.length + " bytes");

        // Calculate total raw chunk sizes after modifications
        long totalModifiedSize = 0;
        int modifiedCastSize = 0;
        int castCount = 0;
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            byte[] rawData = file.getRawChunkData().get(info.id());
            String fourcc = com.libreshockwave.io.BinaryReader.fourCCToString(info.fourcc()).trim();
            if (!fourcc.equals("ILS")) {
                int size = rawData != null ? rawData.length : 0;
                totalModifiedSize += size;
                if (fourcc.equals("CASt")) {
                    modifiedCastSize += size;
                    castCount++;
                }
            }
        }
        System.out.println("Total raw chunk data (excluding ILS): " + totalModifiedSize + " bytes");
        System.out.println("Total CASt chunk data: " + modifiedCastSize + " bytes (" + castCount + " chunks)");
        System.out.println("Reference CASt total: 348682 bytes (81 chunks)");
    }
}
