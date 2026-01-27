package com.libreshockwave;

import com.libreshockwave.chunks.*;

import java.nio.file.*;
import java.util.*;

/**
 * Tool to inspect a Director file's contents.
 */
public class InspectFileTool {

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_game_bb_ui.cct";

        Path file = Path.of(inputFile);
        System.out.println("Loading: " + file);
        DirectorFile df = DirectorFile.load(file);

        System.out.println("\n=== File Info ===");
        System.out.println("Afterburner: " + df.isAfterburner());
        System.out.println("Endian: " + df.getEndian());

        System.out.println("\n=== Cast Members by Type ===");
        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, List<String>> typeMembers = new HashMap<>();

        for (CastMemberChunk member : df.getCastMembers()) {
            String type = member.type().toString();
            typeCounts.merge(type, 1, Integer::sum);
            typeMembers.computeIfAbsent(type, k -> new ArrayList<>())
                .add(member.name() + " (id=" + member.id() + ")");
        }

        typeCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> {
                System.out.println("\n" + e.getKey() + ": " + e.getValue());
                List<String> members = typeMembers.get(e.getKey());
                int show = Math.min(10, members.size());
                for (int i = 0; i < show; i++) {
                    System.out.println("  - " + members.get(i));
                }
                if (members.size() > show) {
                    System.out.println("  ... and " + (members.size() - show) + " more");
                }
            });

        System.out.println("\n=== Chunk Types ===");
        Map<String, Integer> chunkCounts = new HashMap<>();
        for (DirectorFile.ChunkInfo info : df.getAllChunkInfo()) {
            chunkCounts.merge(info.type().toString(), 1, Integer::sum);
        }

        chunkCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));

        // Look for snd chunks directly
        System.out.println("\n=== Looking for 'snd ' chunks directly ===");
        int sndCount = 0;
        for (DirectorFile.ChunkInfo info : df.getAllChunkInfo()) {
            if (info.type().toString().equals("snd ")) {
                sndCount++;
                Chunk chunk = df.getChunk(info.id());
                if (chunk instanceof SoundChunk sc) {
                    System.out.println("  snd  chunk id=" + info.id() +
                        ", rate=" + sc.sampleRate() +
                        ", bits=" + sc.bitsPerSample() +
                        ", data=" + sc.audioData().length + " bytes");
                } else if (chunk != null) {
                    System.out.println("  snd  chunk id=" + info.id() + " -> " + chunk.getClass().getSimpleName());
                }
            }
        }
        System.out.println("Total snd  chunks: " + sndCount);
    }
}
