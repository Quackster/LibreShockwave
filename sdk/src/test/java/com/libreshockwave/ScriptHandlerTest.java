package com.libreshockwave;

import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Test for script() handler - debugging why script("Object Manager Class") isn't found.
 */
public class ScriptHandlerTest {

    public static void main(String[] args) throws Exception {
        // Load the fuse_client.cct file
        Path castPath = Path.of("runtime/src/main/resources/player/assets/fuse_client.cct");

        if (!Files.exists(castPath)) {
            System.out.println("ERROR: fuse_client.cct not found at: " + castPath.toAbsolutePath());
            return;
        }

        System.out.println("=== Script Handler Test ===\n");
        System.out.println("Loading: " + castPath.toAbsolutePath());

        byte[] data = Files.readAllBytes(castPath);
        System.out.println("File size: " + data.length + " bytes");

        DirectorFile file = DirectorFile.load(data);
        System.out.println("Parsed DirectorFile successfully");

        // Debug: Print all chunks
        System.out.println("\n--- Chunks in File ---");
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            System.out.println("  " + info.type() + " (id=" + info.id() + ", fourcc=" + info.fourcc() + ", len=" + info.length() + ")");
        }

        System.out.println("\n--- Direct from DirectorFile ---");
        System.out.println("getCasts().size() = " + file.getCasts().size());
        System.out.println("getCastList() = " + file.getCastList());
        System.out.println("getCastMembers().size() = " + file.getCastMembers().size());
        System.out.println("getScripts().size() = " + file.getScripts().size());

        // Debug CAS* chunk
        if (!file.getCasts().isEmpty()) {
            CastChunk castDef = file.getCasts().get(0);
            System.out.println("\n--- CAS* Chunk Debug ---");
            System.out.println("CAS* id=" + castDef.id() + " memberIds.size()=" + castDef.memberIds().size());
            System.out.println("First 10 memberIds: " + castDef.memberIds().subList(0, Math.min(10, castDef.memberIds().size())));
        }
        for (CastMemberChunk member : file.getCastMembers()) {
            System.out.println("  CastMember: id=" + member.id() + " name='" + member.name() + "' type=" + member.memberType());
        }

        // Create cast manager from the file
        CastManager castManager = file.createCastManager();
        System.out.println("\n--- CastManager Info ---");
        System.out.println("getCastCount() = " + castManager.getCastCount());
        for (int i = 1; i <= castManager.getCastCount(); i++) {
            CastLib c = castManager.getCast(i);
            System.out.println("  Cast #" + i + ": name='" + c.getName() + "' members=" + c.getMemberCount() + " scripts=" + c.getAllScripts().size() + " state=" + c.getState());
        }

        CastLib cast = castManager.getCast(1);  // Get the first cast
        if (cast == null) {
            System.out.println("ERROR: Cast #1 not found!");
            return;
        }

        System.out.println("\n--- Cast Members (via CastManager) ---");
        System.out.println("Total members: " + cast.getMemberCount());
        System.out.println("Total scripts: " + cast.getAllScripts().size());

        // List first 10 script members
        System.out.println("\n--- Script Members (first 10) ---");
        int scriptMemberCount = 0;
        for (Map.Entry<Integer, CastMemberChunk> entry : cast.getMemberEntries()) {
            CastMemberChunk member = entry.getValue();
            int slot = entry.getKey();
            if (member.isScript()) {
                scriptMemberCount++;
                if (scriptMemberCount <= 10 || member.name().contains("Object Manager")) {
                    System.out.println("  Slot " + slot + ": [SCRIPT] name='" + member.name() + "' scriptId=" + member.scriptId());
                }
            }
        }
        System.out.println("Script members found: " + scriptMemberCount);

        // Test findMemberRefByName
        System.out.println("\n--- Testing findMemberRefByName ---");
        String[] testNames = {"Object Manager Class", "Client Initialization Script", "NotExist"};
        for (String name : testNames) {
            Datum.CastMemberRef ref = castManager.findMemberRefByName(name);
            System.out.println("  findMemberRefByName(\"" + name + "\"): " + (ref != null ? "FOUND cast=" + ref.castLib() + " member=" + ref.memberNum() : "NOT FOUND"));
        }

        // List all ScriptChunk entries
        System.out.println("\n--- ScriptChunks (first 5) ---");
        int count = 0;
        for (ScriptChunk script : cast.getAllScripts()) {
            System.out.println("  Script ID " + script.id() + ": type=" + script.scriptType() + " handlers=" + script.handlers().size());
            if (++count >= 5) break;
        }

        System.out.println("\n=== Test Complete ===");
    }
}
