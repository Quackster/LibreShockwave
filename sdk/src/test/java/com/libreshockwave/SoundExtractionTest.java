package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.format.ChunkType;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test to investigate why LS-C64-loose-1 sound can't be extracted from hh_game_snowwar.cct
 */
public class SoundExtractionTest {

    private static final String TEST_FILE = "C:/SourceControl/Havana/tools/www/dcr/v31/hh_game_snowwar.cct";
    private static final String TARGET_SOUND = "LS-C64-loose-1";

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.out.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("Loading file: " + TEST_FILE);
        DirectorFile file = DirectorFile.load(path);

        System.out.println("\n=== File Info ===");
        System.out.println("Endian: " + file.getEndian());
        System.out.println("Cast members: " + file.getCastMembers().size());

        KeyTableChunk keyTable = file.getKeyTable();
        if (keyTable == null) {
            System.out.println("ERROR: No key table found!");
            return;
        }
        System.out.println("Key table entries: " + keyTable.entries().size());

        // Find the target sound member
        System.out.println("\n=== Looking for sound: " + TARGET_SOUND + " ===");
        CastMemberChunk targetMember = null;

        for (CastMemberChunk member : file.getCastMembers()) {
            if (member.isSound()) {
                System.out.println("Sound member: id=" + member.id() + ", name='" + member.name() + "'");
                if (TARGET_SOUND.equals(member.name())) {
                    targetMember = member;
                }
            }
        }

        if (targetMember == null) {
            System.out.println("\nERROR: Target sound '" + TARGET_SOUND + "' not found!");
            return;
        }

        System.out.println("\n=== Target member found ===");
        System.out.println("Member ID: " + targetMember.id());
        System.out.println("Member name: " + targetMember.name());

        // Check key table entries for this member
        System.out.println("\n=== Key table entries for member " + targetMember.id() + " ===");
        var entries = keyTable.getEntriesForOwner(targetMember.id());
        System.out.println("Found " + entries.size() + " entries");

        for (var entry : entries) {
            System.out.println("\n  Entry:");
            System.out.println("    sectionId: " + entry.sectionId());
            System.out.println("    castId: " + entry.castId());
            System.out.println("    fourcc: '" + entry.fourccString() + "' (0x" + Integer.toHexString(entry.fourcc()) + ")");

            // Get the chunk at this section ID
            Chunk chunk = file.getChunk(entry.sectionId());
            if (chunk == null) {
                System.out.println("    chunk: NULL - chunk not found!");

                // Check if we have chunk info for this section ID
                var chunkInfo = file.getChunkInfo(entry.sectionId());
                if (chunkInfo != null) {
                    System.out.println("    chunkInfo exists: type=" + chunkInfo.type() + ", offset=" + chunkInfo.offset() + ", length=" + chunkInfo.length());
                } else {
                    System.out.println("    chunkInfo: NULL");
                }
            } else {
                System.out.println("    chunk class: " + chunk.getClass().getSimpleName());
                System.out.println("    chunk type: " + chunk.type());

                if (chunk instanceof SoundChunk sc) {
                    System.out.println("    === SoundChunk data ===");
                    System.out.println("    sampleRate: " + sc.sampleRate());
                    System.out.println("    bitsPerSample: " + sc.bitsPerSample());
                    System.out.println("    channelCount: " + sc.channelCount());
                    System.out.println("    codec: " + sc.codec());
                    System.out.println("    audioData length: " + (sc.audioData() != null ? sc.audioData().length : "null"));
                } else if (chunk instanceof RawChunk rc) {
                    System.out.println("    === RawChunk data ===");
                    System.out.println("    data length: " + rc.data().length);
                    if (rc.data().length >= 16) {
                        System.out.print("    first 16 bytes: ");
                        for (int i = 0; i < 16; i++) {
                            System.out.printf("%02X ", rc.data()[i] & 0xFF);
                        }
                        System.out.println();
                    }
                }
            }
        }

        // Also check all chunks with ediM type
        System.out.println("\n=== All ediM chunks in file ===");
        for (var chunkInfo : file.getAllChunkInfo()) {
            if (chunkInfo.type() == ChunkType.ediM) {
                System.out.println("  ediM chunk: id=" + chunkInfo.id() + ", offset=" + chunkInfo.offset() + ", length=" + chunkInfo.length());

                // Check who owns this chunk
                int ownerId = keyTable.getOwnerCastId(chunkInfo.id());
                System.out.println("    owner castId: " + ownerId);

                Chunk chunk = file.getChunk(chunkInfo.id());
                if (chunk instanceof RawChunk rc) {
                    System.out.println("    parsed as RawChunk, data length: " + rc.data().length);
                } else if (chunk != null) {
                    System.out.println("    parsed as: " + chunk.getClass().getSimpleName());
                }
            }
        }

        // Check all snd_ chunks
        System.out.println("\n=== All snd_ chunks in file ===");
        for (var chunkInfo : file.getAllChunkInfo()) {
            if (chunkInfo.type() == ChunkType.snd_) {
                System.out.println("  snd_ chunk: id=" + chunkInfo.id() + ", offset=" + chunkInfo.offset() + ", length=" + chunkInfo.length());

                int ownerId = keyTable.getOwnerCastId(chunkInfo.id());
                System.out.println("    owner castId: " + ownerId);

                Chunk chunk = file.getChunk(chunkInfo.id());
                if (chunk instanceof SoundChunk sc) {
                    System.out.println("    parsed as SoundChunk, audioData length: " + (sc.audioData() != null ? sc.audioData().length : "null"));
                } else if (chunk != null) {
                    System.out.println("    parsed as: " + chunk.getClass().getSimpleName());
                }
            }
        }

        // Test the findSoundForMember logic
        System.out.println("\n=== Testing findSoundForMember logic ===");
        SoundChunk foundSound = null;
        for (var entry : keyTable.getEntriesForOwner(targetMember.id())) {
            Chunk chunk = file.getChunk(entry.sectionId());
            if (chunk instanceof SoundChunk sc) {
                foundSound = sc;
                System.out.println("Found via SoundChunk!");
                break;
            }
            if (chunk instanceof MediaChunk mc) {
                foundSound = mc.toSoundChunk();
                System.out.println("Found via MediaChunk -> SoundChunk conversion!");
                break;
            }
        }

        if (foundSound != null) {
            System.out.println("\n=== Extracted Sound Info ===");
            System.out.println("Sample rate: " + foundSound.sampleRate() + " Hz");
            System.out.println("Bits per sample: " + foundSound.bitsPerSample());
            System.out.println("Channels: " + foundSound.channelCount());
            System.out.println("Codec: " + foundSound.codec());
            System.out.println("Audio data length: " + foundSound.audioData().length + " bytes");
            System.out.println("Duration: " + String.format("%.2f", foundSound.durationSeconds()) + " seconds");

            // Check first few bytes of audio data
            if (foundSound.audioData().length >= 16) {
                System.out.print("First 16 bytes: ");
                for (int i = 0; i < 16; i++) {
                    System.out.printf("%02X ", foundSound.audioData()[i] & 0xFF);
                }
                System.out.println();
            }

            System.out.println("\nSUCCESS: Sound '" + TARGET_SOUND + "' can now be extracted!");
        } else {
            System.out.println("\nFAILED: Could not extract sound '" + TARGET_SOUND + "'");
        }

        System.out.println("\n=== Test complete ===");
    }
}
