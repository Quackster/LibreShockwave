package com.libreshockwave;

import com.libreshockwave.chunks.*;

import java.nio.file.*;

/**
 * Dump raw sound header bytes for debugging.
 */
public class DumpSoundHeaderTool {

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_game_bb.cct";

        Path file = Path.of(inputFile);
        System.out.println("Loading: " + file);
        DirectorFile df = DirectorFile.load(file);

        KeyTableChunk keyTable = df.getKeyTable();
        if (keyTable == null) return;

        int count = 0;
        for (CastMemberChunk member : df.getCastMembers()) {
            if (!member.isSound()) continue;
            if (count++ >= 3) break; // Just first 3

            System.out.println("\n=== " + member.name() + " (id=" + member.id() + ") ===");

            // Find the raw chunk data
            for (DirectorFile.ChunkInfo info : df.getAllChunkInfo()) {
                // Look through key table to find the snd chunk for this member
                for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                    if (entry.sectionId() == info.id() && info.type().toString().contains("snd")) {
                        Chunk chunk = df.getChunk(info.id());
                        if (chunk instanceof RawChunk raw) {
                            dumpHeader(raw.data());
                        } else if (chunk instanceof SoundChunk sc) {
                            // Get raw data before processing
                            System.out.println("Processed as SoundChunk:");
                            System.out.println("  Rate: " + sc.sampleRate() + " Hz");
                            System.out.println("  Bits: " + sc.bitsPerSample());
                            System.out.println("  Channels: " + sc.channelCount());
                            System.out.println("  Audio length: " + sc.audioData().length);

                            // The audioData has the header stripped, so show first audio bytes
                            System.out.println("  First audio bytes:");
                            byte[] data = sc.audioData();
                            for (int row = 0; row < Math.min(4, (data.length + 15) / 16); row++) {
                                System.out.print("    ");
                                for (int col = 0; col < 16 && row * 16 + col < data.length; col++) {
                                    System.out.printf("%02X ", data[row * 16 + col] & 0xFF);
                                }
                                System.out.println();
                            }
                        }
                        break;
                    }
                }
            }
        }
    }

    private static void dumpHeader(byte[] data) {
        System.out.println("Raw chunk data (" + data.length + " bytes):");
        System.out.println("Header (first 64 bytes):");
        for (int row = 0; row < 4; row++) {
            System.out.print("  " + String.format("%02X", row * 16) + ": ");
            for (int col = 0; col < 16 && row * 16 + col < data.length; col++) {
                System.out.printf("%02X ", data[row * 16 + col] & 0xFF);
            }
            System.out.println();
        }

        // Parse key fields
        if (data.length >= 0x2C) {
            // Offset 0x16: sample rate (big-endian u16)
            int rateB = ((data[0x16] & 0xFF) << 8) | (data[0x17] & 0xFF);
            System.out.println("\nOffset 0x16 (Rate B): " + rateB + " Hz");

            // Offset 0x2A: sample rate (big-endian u16)
            int rateC = ((data[0x2A] & 0xFF) << 8) | (data[0x2B] & 0xFF);
            System.out.println("Offset 0x2A (Rate C): " + rateC + " Hz");
        }
    }
}
