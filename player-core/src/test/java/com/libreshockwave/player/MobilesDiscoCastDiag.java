package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;

import java.nio.file.Path;

/**
 * Quick diagnostic for CASp-to-CastLib mapping and member 113 lookup.
 */
public class MobilesDiscoCastDiag {
    public static void main(String[] args) throws Exception {
        DirectorFile file = DirectorFile.load(Path.of("C:/xampp/htdocs/mobiles/dcr_0519b_e/20000201_mobiles_disco.dcr"));

        System.out.println("=== MCsL entries ===");
        var castList = file.getCastList();
        if (castList != null) {
            for (int i = 0; i < castList.entries().size(); i++) {
                var entry = castList.entries().get(i);
                System.out.printf("  CastLib %d: name='%s' min=%d max=%d memberCount=%d path='%s'%n",
                        i + 1, entry.name(), entry.minMember(), entry.maxMember(),
                        entry.memberCount(), entry.path());
            }
        }

        System.out.println("\n=== CASp chunks ===");
        var casts = file.getCasts();
        for (int i = 0; i < casts.size(); i++) {
            var cast = casts.get(i);
            int nonZero = 0;
            for (int id : cast.memberIds()) if (id > 0) nonZero++;
            System.out.printf("  Cast[%d] chunkId=%d slots=%d nonEmpty=%d%n",
                    i, cast.id().value(), cast.memberIds().size(), nonZero);
        }

        System.out.println("\n=== getCastMemberByNumber tests ===");
        // Test the critical lookups
        int[][] tests = {{1, 113}, {1, 135}, {1, 7}, {1, 1}, {1, 10}};
        for (int[] t : tests) {
            var member = file.getCastMemberByNumber(t[0], t[1]);
            System.out.printf("  getCastMemberByNumber(%d, %d) = %s%n",
                    t[0], t[1],
                    member != null ? "'" + member.name() + "' type=" + member.memberType() + " isScript=" + member.isScript() : "NULL");
        }

        // Check what the mapping produces
        System.out.println("\n=== CastMemberLookup mapping debug ===");
        // Try to reproduce the mapping logic
        if (castList != null && !castList.entries().isEmpty()) {
            boolean[] assigned = new boolean[casts.size()];
            for (int libIdx = 0; libIdx < castList.entries().size(); libIdx++) {
                var entry = castList.entries().get(libIdx);
                int expectedCount = entry.memberCount();
                int castLibNum = libIdx + 1;
                boolean found = false;
                for (int ci = 0; ci < casts.size(); ci++) {
                    if (assigned[ci]) continue;
                    var cast = casts.get(ci);
                    if (cast.memberIds().size() == expectedCount) {
                        System.out.printf("  CastLib %d '%s' (memberCount=%d) -> Cast[%d] (chunkId=%d, slots=%d)%n",
                                castLibNum, entry.name(), expectedCount, ci, cast.id().value(), cast.memberIds().size());
                        assigned[ci] = true;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    System.out.printf("  CastLib %d '%s' (memberCount=%d) -> NO MATCH!%n",
                            castLibNum, entry.name(), expectedCount);
                }
            }
        }

        // Check member 113's slot in the matched CASp
        System.out.println("\n=== Member 113 slot trace ===");
        // CastLib 1 minMember
        int minMember = castList != null && !castList.entries().isEmpty()
                ? castList.entries().get(0).minMember() : 1;
        System.out.printf("  CastLib 1 minMember=%d%n", minMember);
        System.out.printf("  memberNumber=113, arrayIndex=113-%d=%d%n", minMember, 113 - minMember);

        // Dump the CASp slot for CastLib 1 around index 112
        var castLib1CASp = casts.get(1); // Cast[1] is CastLib 1 per mapping
        System.out.printf("  CASp for CastLib 1: chunkId=%d, slots=%d%n",
                castLib1CASp.id().value(), castLib1CASp.memberIds().size());
        int arrayIdx = 113 - minMember;
        if (arrayIdx >= 0 && arrayIdx < castLib1CASp.memberIds().size()) {
            int chunkId = castLib1CASp.memberIds().get(arrayIdx);
            System.out.printf("  memberIds[%d] = %d (chunkId for member 113)%n", arrayIdx, chunkId);
        } else {
            System.out.printf("  arrayIdx=%d out of range (0-%d)%n", arrayIdx, castLib1CASp.memberIds().size() - 1);
        }

        // Also check: what chunk IDs are around index 112?
        System.out.println("  Nearby slots:");
        for (int j = Math.max(0, arrayIdx - 3); j <= Math.min(castLib1CASp.memberIds().size() - 1, arrayIdx + 3); j++) {
            int cid = castLib1CASp.memberIds().get(j);
            // Find the member with this chunk ID
            String memberName = "?";
            for (var cm : file.getCastMembers()) {
                if (cm.id().value() == cid) {
                    memberName = cm.name() != null ? cm.name() : "(unnamed)";
                    break;
                }
            }
            System.out.printf("    slot[%d] (member %d) -> chunkId=%d %s%n",
                    j, j + minMember, cid, cid > 0 ? memberName : "(empty)");
        }

        // What member 113 (chunk id=113) actually maps to:
        System.out.println("\n  Where does chunk id=113 appear in CASp?");
        for (int j = 0; j < castLib1CASp.memberIds().size(); j++) {
            if (castLib1CASp.memberIds().get(j) == 113) {
                System.out.printf("    Found at slot[%d] -> member number %d%n", j, j + minMember);
            }
        }

        // Does chunkId 985 exist?
        System.out.println("\n=== Looking for chunkId 985 ===");
        for (var cm : file.getCastMembers()) {
            if (cm.id().value() == 985) {
                System.out.printf("  Found: name='%s' type=%s isScript=%s id=%s%n",
                        cm.name(), cm.memberType(), cm.isScript(), cm.id());
            }
        }
        // Try the exact same lookup the code does
        var testChunkId = new com.libreshockwave.id.ChunkId(985);
        boolean foundMatch = false;
        for (var cm : file.getCastMembers()) {
            if (cm.id().equals(testChunkId)) {
                System.out.printf("  .equals match: name='%s' type=%s%n", cm.name(), cm.memberType());
                foundMatch = true;
            }
        }
        if (!foundMatch) {
            System.out.println("  NO .equals match for ChunkId(985)!");
            // Dump all cast member IDs near 985
            for (var cm : file.getCastMembers()) {
                int v = cm.id().value();
                if (v >= 980 && v <= 990) {
                    System.out.printf("  castMember id=%d name='%s'%n", v, cm.name());
                }
            }
        }
        // Also manually call getByNumber
        var testMember = file.getCastMemberByNumber(1, 113);
        System.out.printf("  Direct getCastMemberByNumber(1,113): %s%n", testMember);
        // Check in chunkInfo
        var ci985 = file.getChunkInfo(new com.libreshockwave.id.ChunkId(985));
        System.out.printf("  ChunkInfo for 985: %s%n",
                ci985 != null ? "type=" + ci985.type() : "NOT FOUND");

        // Check: do ALL CASp member IDs have corresponding castMember chunks?
        System.out.println("\n=== Missing cast member chunks for CastLib 1 CASp ===");
        int missing = 0;
        for (int j = 0; j < castLib1CASp.memberIds().size(); j++) {
            int cid = castLib1CASp.memberIds().get(j);
            if (cid <= 0) continue;
            boolean found2 = false;
            for (var cm : file.getCastMembers()) {
                if (cm.id().value() == cid) {
                    found2 = true;
                    break;
                }
            }
            if (!found2) {
                if (missing < 20) {
                    System.out.printf("  slot[%d] (member %d) -> chunkId=%d NOT FOUND in castMembers%n",
                            j, j + minMember, cid);
                }
                missing++;
            }
        }
        System.out.printf("  Total missing: %d / %d non-empty slots%n", missing, castLib1CASp.memberIds().stream().mapToInt(Integer::intValue).filter(v -> v > 0).count());

        // Check total cast members in file
        System.out.printf("\n=== Total cast members: %d ===%n", file.getCastMembers().size());
        // Find any member with name containing "exitFrame" or script type
        int scriptCount = 0;
        for (var cm : file.getCastMembers()) {
            if (cm.isScript()) {
                scriptCount++;
                if (cm.name() != null && !cm.name().isEmpty()) {
                    System.out.printf("  Script member: id=%d name='%s' scriptId=%d scriptType=%s%n",
                            cm.id().value(), cm.name(), cm.scriptId(), cm.getScriptType());
                }
            }
        }
        System.out.printf("Total script members: %d%n", scriptCount);

        // Check KEY* table and chunk types
        System.out.println("\n=== KEY* / Chunk Diagnostics ===");
        System.out.printf("  keyTable: %s%n", file.getKeyTable() != null ? "present (" + file.getKeyTable().entries().size() + " entries)" : "NULL");
        System.out.printf("  isAfterburner: %s%n", file.isAfterburner());

        // Count chunk types
        java.util.Map<String, Integer> typeCounts = new java.util.TreeMap<>();
        for (var ci : file.getAllChunkInfo()) {
            String typeName = ci.type() != null ? ci.type().name() : "?:" + com.libreshockwave.io.BinaryReader.fourCCToString(ci.fourcc());
            typeCounts.merge(typeName, 1, Integer::sum);
        }
        System.out.println("  Chunk types:");
        for (var e : typeCounts.entrySet()) {
            System.out.printf("    %s: %d%n", e.getKey(), e.getValue());
        }

        // Check if any BITD chunks exist
        int bitdCount = 0;
        for (var ci : file.getAllChunkInfo()) {
            if (ci.type() != null && ci.type().name().equals("BITD")) {
                bitdCount++;
            }
        }
        System.out.printf("  BITD chunks: %d%n", bitdCount);

        // If keyTable is null but BITD chunks exist, we need to build the mapping
        if (file.getKeyTable() == null && bitdCount > 0) {
            System.out.println("  ** KEY* is NULL but BITD chunks exist — bitmap decode will fail!");
            // Show first few BITD chunk IDs
            int shown = 0;
            for (var ci : file.getAllChunkInfo()) {
                if (ci.type() != null && ci.type().name().equals("BITD") && shown < 10) {
                    System.out.printf("    BITD chunkId=%d%n", ci.id().value());
                    shown++;
                }
            }
        }

        // Try to find the CASt→BITD relationship by resource ID proximity
        // In Afterburner files, BITD is typically CASt resId + 1
        System.out.println("\n=== CASt→BITD Proximity Check ===");
        java.util.Set<Integer> bitdIds = new java.util.HashSet<>();
        java.util.Set<Integer> castIds = new java.util.HashSet<>();
        for (var ci : file.getAllChunkInfo()) {
            if (ci.type() != null) {
                if (ci.type().name().equals("BITD")) bitdIds.add(ci.id().value());
                if (ci.type().name().equals("CASt")) castIds.add(ci.id().value());
            }
        }
        int adjacent = 0;
        for (int castId : castIds) {
            if (bitdIds.contains(castId + 1)) adjacent++;
        }
        System.out.printf("  CASt members with BITD at id+1: %d / %d cast members%n", adjacent, castIds.size());

        // Check a specific bitmap member
        for (var cm : file.getCastMembers()) {
            if (cm.isBitmap() && cm.name() != null && cm.name().equals("background")) {
                int cmId = cm.id().value();
                System.out.printf("  'background' member: castId=%d, BITD at id+1=%s%n",
                        cmId, bitdIds.contains(cmId + 1) ? "YES" : "NO");
                // Try manual decode
                var decoded = file.decodeBitmap(cm);
                System.out.printf("  decodeBitmap: %s%n", decoded.isPresent() ? decoded.get().getWidth() + "x" + decoded.get().getHeight() : "EMPTY");
                break;
            }
        }
    }
}
