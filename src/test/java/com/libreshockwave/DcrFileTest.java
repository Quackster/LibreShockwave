package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Test for running .dcr files with external cast support.
 * Tests Afterburner format parsing and external cast loading.
 */
public class DcrFileTest {

    public static void main(String[] args) {
        System.out.println("=== DCR File Test ===\n");

        // Default test file - can be overridden by command line argument
        String testFile = "C:/SourceControl/habbo.dcr";
        if (args.length > 0) {
            testFile = args[0];
        }

        testDcrLoading(testFile);
        testCastManager(testFile);
        testExternalCastDetection(testFile);

        System.out.println("\n=== DCR File Test Complete ===");
    }

    private static void testDcrLoading(String filePath) {
        System.out.println("--- Testing DCR Loading: " + filePath + " ---");

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found: " + filePath);
                return;
            }

            DirectorFile file = DirectorFile.load(path);
            assert file != null : "File should not be null";
            assert file.isAfterburner() : "DCR files should be Afterburner format";

            System.out.println("  File loaded successfully");
            System.out.println("  Endian: " + (file.getEndian() == java.nio.ByteOrder.BIG_ENDIAN ? "Big" : "Little"));
            System.out.println("  Afterburner: " + file.isAfterburner());
            System.out.println("  Base path: " + file.getBasePath());

            if (file.getConfig() != null) {
                System.out.println("  Stage: " + file.getStageWidth() + "x" + file.getStageHeight());
                System.out.println("  Tempo: " + file.getTempo() + " fps");
                System.out.println("  Director Version: " + file.getConfig().directorVersion());
            }

            System.out.println("  Total chunks: " + file.getAllChunkInfo().size());
            System.out.println("  Cast members: " + file.getCastMembers().size());
            System.out.println("  Scripts: " + file.getScripts().size());

            System.out.println("  DCR Loading: PASS\n");

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testCastManager(String filePath) {
        System.out.println("--- Testing Cast Manager ---");

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found");
                return;
            }

            DirectorFile file = DirectorFile.load(path);
            CastManager castManager = file.createCastManager();

            assert castManager != null : "CastManager should not be null";
            System.out.println("  Cast manager created successfully");
            System.out.println("  Total casts: " + castManager.getCastCount());
            System.out.println("  Base path: " + castManager.getBasePath());

            // Print details about each cast
            for (CastLib cast : castManager.getCasts()) {
                System.out.println("  Cast #" + cast.getNumber() + ": " + cast.getName());
                System.out.println("    External: " + cast.isExternal());
                System.out.println("    State: " + cast.getState());
                System.out.println("    File: " + cast.getFileName());
                System.out.println("    Members: " + cast.getMemberCount());
                System.out.println("    Scripts: " + cast.getAllScripts().size());
                System.out.println("    Range: " + cast.getMinMember() + "-" + cast.getMaxMember());
            }

            // Test cast access
            CastLib firstCast = castManager.getCast(1);
            if (firstCast != null) {
                System.out.println("  First cast name: " + firstCast.getName());
            }

            System.out.println("  Cast Manager: PASS\n");

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testExternalCastDetection(String filePath) {
        System.out.println("--- Testing External Cast Detection ---");

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found");
                return;
            }

            DirectorFile file = DirectorFile.load(path);

            System.out.println("  Has external casts: " + file.hasExternalCasts());

            List<String> externalPaths = file.getExternalCastPaths();
            System.out.println("  External cast paths: " + externalPaths.size());
            for (String extPath : externalPaths) {
                System.out.println("    - " + extPath);
            }

            // Test CastList chunk directly
            CastListChunk castList = file.getCastList();
            if (castList != null) {
                System.out.println("  MCsL entries: " + castList.entries().size());
                for (CastListChunk.CastListEntry entry : castList.entries()) {
                    System.out.println("    - Name: " + entry.name() +
                        ", Path: " + entry.path() +
                        ", ID: " + entry.id() +
                        ", Range: " + entry.minMember() + "-" + entry.maxMember());
                }
            }

            // Debug KEY* table
            KeyTableChunk keyTable = file.getKeyTable();
            if (keyTable != null) {
                System.out.println("\n  KEY* table entries (first 20):");
                int count = 0;
                for (KeyTableChunk.KeyTableEntry entry : keyTable.entries()) {
                    if (count++ >= 20) break;
                    System.out.println("    - castId: " + entry.castId() +
                        ", sectionId: " + entry.sectionId() +
                        ", fourcc: " + entry.fourccString());
                }
                System.out.println("  Total KEY* entries: " + keyTable.entries().size());
            }

            // Debug CAS* chunks
            System.out.println("\n  CAS* chunks:");
            for (CastChunk cast : file.getCasts()) {
                System.out.println("    - id: " + cast.id() + ", members: " + cast.memberCount());
            }

            // Test preloading external casts (if any exist and are accessible)
            if (file.hasExternalCasts()) {
                System.out.println("\n  Testing external cast loading...");
                CastManager castManager = file.createCastManager();

                // Try to preload external casts
                castManager.preloadCasts(CastManager.PreloadReason.MOVIE_LOADED);

                // Check which casts are now loaded
                for (CastLib cast : castManager.getCasts()) {
                    if (cast.isExternal()) {
                        System.out.println("    External cast '" + cast.getName() +
                            "' state: " + cast.getState());
                    }
                }
            }

            System.out.println("  External Cast Detection: PASS\n");

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
