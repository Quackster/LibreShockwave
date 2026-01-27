package com.libreshockwave;

import com.libreshockwave.chunks.*;

import java.nio.file.*;

/**
 * Debug tool to inspect chunk loading.
 */
public class DebugChunksTool {

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_game_bb.cct";

        Path file = Path.of(inputFile);
        System.out.println("Loading: " + file);
        DirectorFile df = DirectorFile.load(file);

        // Look at chunk info for Sdns type chunks
        System.out.println("\nAll chunk info:");
        for (DirectorFile.ChunkInfo info : df.getAllChunkInfo()) {
            String typeStr = info.type().toString();
            if (typeStr.contains("Sdns") || typeStr.contains("snd") || typeStr.contains("dns") ||
                info.id() >= 359 && info.id() <= 440) {
                System.out.println("  ID=" + info.id() +
                    " fourcc=0x" + Integer.toHexString(info.fourcc()) +
                    " type=" + typeStr +
                    " len=" + info.length());

                Chunk chunk = df.getChunk(info.id());
                if (chunk != null) {
                    System.out.println("    -> Loaded as: " + chunk.getClass().getSimpleName());
                    if (chunk instanceof RawChunk raw) {
                        System.out.println("    -> Data length: " + raw.data().length);
                    } else if (chunk instanceof SoundChunk sc) {
                        System.out.println("    -> Rate=" + sc.sampleRate() + " bits=" + sc.bitsPerSample() +
                            " ch=" + sc.channelCount() + " audioLen=" + sc.audioData().length);
                        if (sc.audioData().length > 0) {
                            System.out.print("    -> First bytes: ");
                            for (int i = 0; i < Math.min(16, sc.audioData().length); i++) {
                                System.out.printf("%02X ", sc.audioData()[i] & 0xFF);
                            }
                            System.out.println();
                        }
                    }
                } else {
                    System.out.println("    -> NOT LOADED");
                }
            }
        }
    }
}
