package com.libreshockwave;

import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.chunks.*;

import java.nio.file.*;
import java.util.*;

/**
 * Tool to extract sounds from a Director file.
 * Supports both traditional snd chunks and Shockwave linked sounds (Sdns).
 */
public class ExtractSoundTool {

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_game_bb.cct";

        Path file = Path.of(inputFile);
        System.out.println("Loading: " + file);
        DirectorFile df = DirectorFile.load(file);

        String baseName = file.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path outDir = Path.of("test_output/" + baseName + "_sounds");
        Files.createDirectories(outDir);

        KeyTableChunk keyTable = df.getKeyTable();
        if (keyTable == null) {
            System.out.println("No key table found!");
            return;
        }

        int count = 0;

        System.out.println("\nSearching for sound members...\n");

        for (CastMemberChunk member : df.getCastMembers()) {
            if (!member.isSound()) continue;

            System.out.println("Found sound: \"" + member.name() + "\" (id=" + member.id() + ")");

            String name = sanitizeName(member.name(), member.id());
            SoundChunk soundChunk = null;

            // Find the sound chunk via key table
            for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                Chunk chunk = df.getChunk(entry.sectionId());
                if (chunk instanceof SoundChunk sc) {
                    soundChunk = sc;
                    break;
                }
            }

            if (soundChunk != null && soundChunk.audioData().length > 0) {
                byte[] audioData = soundChunk.audioData();
                System.out.println("    Data: " + audioData.length + " bytes");

                // Check for MP3 sync bytes (may be after some padding)
                int mp3Start = SoundConverter.findMp3Start(audioData);
                if (mp3Start >= 0) {
                    // It's MP3 - extract the MP3 data
                    byte[] mp3Data = Arrays.copyOfRange(audioData, mp3Start, audioData.length);
                    Path outPath = outDir.resolve(name + ".mp3");
                    Files.write(outPath, mp3Data);
                    System.out.println("    -> MP3 detected, saved: " + outPath.getFileName());
                    count++;
                } else {
                    // Raw PCM - convert to WAV
                    System.out.println("    Rate: " + soundChunk.sampleRate() + "Hz, " +
                        soundChunk.bitsPerSample() + "-bit, " + soundChunk.channelCount() + "ch");
                    byte[] wav = SoundConverter.toWav(soundChunk);
                    Path outPath = outDir.resolve(name + ".wav");
                    Files.write(outPath, wav);
                    System.out.println("    -> Saved: " + outPath.getFileName());
                    count++;
                }
            } else {
                System.out.println("    -> No audio data found");
            }
            System.out.println();
        }

        System.out.println("=========================================");
        System.out.println("Extracted " + count + " sound(s) to: " + outDir);
        System.out.println("=========================================");
    }

    private static String sanitizeName(String name, int id) {
        if (name == null || name.isBlank()) return "sound_" + id;
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
