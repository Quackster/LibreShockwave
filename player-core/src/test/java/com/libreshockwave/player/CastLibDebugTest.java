package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Debug test to list scripts and handlers in the external cast.
 */
public class CastLibDebugTest {

    private static final String TEST_FILE = "C:/SourceControl/habbo.dcr";

    public static void main(String[] args) throws IOException {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("=== Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);

        Player player = new Player(file);

        // Wait for external cast to load
        // Start playback to trigger preloadNetThing
        player.play();

        System.out.println("\n=== Waiting for external cast ===");
        for (int i = 0; i < 100; i++) {
            var castLib2 = player.getCastLibManager().getCastLibs().get(2);
            if (castLib2 != null && castLib2.isLoaded()) {
                System.out.println("External cast loaded after " + (i * 100) + "ms");
                break;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // List all scripts in cast lib 2
        System.out.println("\n=== Scripts in CastLib 2 (External) ===");
        var castLib2 = player.getCastLibManager().getCastLibs().get(2);
        if (castLib2 != null) {
            var scriptNames = castLib2.getScriptNames();
            for (var script : castLib2.getAllScripts()) {
                String name = script.getScriptName();
                if (name == null || name.isEmpty()) {
                    name = "id_" + script.id();
                }

                // Check if it's related to CastLoad
                if (name.toLowerCase().contains("castload") || name.toLowerCase().contains("manager")) {
                    System.out.println("\n=== Script: " + name + " (member=" + script.id() + ") ===");
                    System.out.println("  Type: " + script.scriptType());

                    // List handlers
                    System.out.println("  Handlers:");
                    for (var handler : script.handlers()) {
                        String handlerName = scriptNames != null ? scriptNames.getName(handler.nameId()) : "id_" + handler.nameId();
                        System.out.println("    - " + handlerName);
                    }
                }
            }
        }

        // Also check for "CastLoad Manager Class" member
        System.out.println("\n=== Looking for 'CastLoad Manager Class' member ===");
        var member = castLib2.findMemberByName("CastLoad Manager Class");
        if (member != null) {
            System.out.println("Found: member #" + castLib2.getMemberNumber(member));
        } else {
            System.out.println("NOT FOUND in cast lib 2");
        }

        player.shutdown();
    }
}
