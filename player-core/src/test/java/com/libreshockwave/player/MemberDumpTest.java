package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastMemberChunk;
import java.nio.file.Path;
import java.nio.file.Files;

public class MemberDumpTest {
    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    
    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }
        
        System.out.println("=== Member Dump for IDs 13, 247, 250, 251 ===\n");
        DirectorFile file = DirectorFile.load(path);
        
        int[] targetIds = {13, 247, 250, 251};
        boolean[] found = new boolean[targetIds.length];
        
        for (CastMemberChunk member : file.getCastMembers()) {
            for (int i = 0; i < targetIds.length; i++) {
                if (member.id() == targetIds[i]) {
                    System.out.println("Member ID " + member.id() + ":");
                    System.out.println("  name: '" + member.name() + "'");
                    System.out.println("  memberType: " + member.memberType());
                    System.out.println("  isBitmap: " + member.isBitmap());
                    System.out.println();
                    found[i] = true;
                }
            }
        }
        
        System.out.println("\nSummary of target IDs found:");
        for (int i = 0; i < targetIds.length; i++) {
            System.out.println("  ID " + targetIds[i] + ": " + (found[i] ? "FOUND" : "NOT FOUND"));
        }
    }
}
