package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.id.ChunkId;

import java.nio.file.Path;

public class CastMemberDump {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "C:/xampp/htdocs/dcr/14.1_b8/hh_entry_br.cct";
        DirectorFile f = DirectorFile.load(Path.of(path));
        System.out.println("Members in: " + path);
        for (CastMemberChunk m : f.getCastMembers()) {
            System.out.printf("  id=%d type=%s name='%s'%n", m.id().value(), m.memberType(), m.name());
        }

        // Scan all chunk IDs for TextChunks
        System.out.println("\n=== Text Chunks ===");
        for (var info : f.getAllChunkInfo()) {
            Chunk chunk = f.getChunk(info.id());
            if (chunk instanceof TextChunk tc) {
                String text = tc.text();
                if (text != null && text.length() > 20) {
                    System.out.println("TextChunk id=" + tc.id().value() + " len=" + text.length());
                    System.out.println(text);
                    System.out.println("=== end ===");
                }
            }
        }
    }
}
