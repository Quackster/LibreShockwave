package com.libreshockwave;

import com.libreshockwave.chunks.*;

import java.nio.file.*;
import java.util.*;

/**
 * Scan all Director files in a directory for sound chunks.
 */
public class ScanForSoundsTool {

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8";

        System.out.println("Scanning directory: " + dir);
        System.out.println("Looking for files with 'snd ' or 'ediM' chunks...\n");

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(Path.of(dir))) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".cct") || name.endsWith(".dcr") || name.endsWith(".dxr");
            }).forEach(files::add);
        }

        System.out.println("Found " + files.size() + " Director files\n");

        int filesWithSound = 0;
        int totalSndChunks = 0;
        int totalEdiMChunks = 0;

        for (Path file : files) {
            try {
                DirectorFile df = DirectorFile.load(file);

                int sndCount = 0;
                int ediMCount = 0;
                List<String> soundMembers = new ArrayList<>();

                for (DirectorFile.ChunkInfo info : df.getAllChunkInfo()) {
                    String type = info.type().toString();
                    if (type.equals("snd ")) {
                        sndCount++;
                    } else if (type.equals("ediM")) {
                        ediMCount++;
                    }
                }

                // Also check cast members marked as sound
                for (CastMemberChunk member : df.getCastMembers()) {
                    if (member.isSound()) {
                        soundMembers.add(member.name() + " (id=" + member.id() + ")");
                    }
                }

                if (sndCount > 0 || ediMCount > 0 || !soundMembers.isEmpty()) {
                    filesWithSound++;
                    totalSndChunks += sndCount;
                    totalEdiMChunks += ediMCount;

                    System.out.println(file.getFileName() + ":");
                    if (sndCount > 0) System.out.println("  snd  chunks: " + sndCount);
                    if (ediMCount > 0) System.out.println("  ediM chunks: " + ediMCount);
                    if (!soundMembers.isEmpty()) {
                        System.out.println("  Sound members: " + soundMembers.size());
                        for (String m : soundMembers.subList(0, Math.min(5, soundMembers.size()))) {
                            System.out.println("    - " + m);
                        }
                        if (soundMembers.size() > 5) {
                            System.out.println("    ... and " + (soundMembers.size() - 5) + " more");
                        }
                    }
                    System.out.println();
                }

            } catch (Exception e) {
                // Skip files that fail to load
            }
        }

        System.out.println("===========================================");
        System.out.println("Summary:");
        System.out.println("  Files with sound data: " + filesWithSound);
        System.out.println("  Total snd  chunks: " + totalSndChunks);
        System.out.println("  Total ediM chunks: " + totalEdiMChunks);
    }
}
