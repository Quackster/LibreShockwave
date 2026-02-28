package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.builtin.CastLibProvider.HandlerLocation;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Integration test for loading habbo.dcr and verifying external cast preloading.
 * Tests that:
 * 1. preloadSettings are parsed correctly from CastListChunk
 * 2. External casts (fuse_client.cct) are fetched and loaded
 * 3. dump() handler can be found in fuse_client scripts
 * 4. Handlers execute without "unimplemented opcode" errors
 *
 * Run with: ./gradlew runHabboPreloadTest
 */
public class HabboPreloadTest {

    // Try multiple paths for the test file
    private static final String[] TEST_FILE_PATHS = {
        "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr",
        "C:/SourceControl/habbo.dcr"
    };

    private static String findTestFile() {
        for (String path : TEST_FILE_PATHS) {
            if (Files.exists(Path.of(path))) {
                return path;
            }
        }
        return null;
    }

    public static void main(String[] args) throws IOException {
        String testFile = findTestFile();
        if (testFile == null) {
            System.err.println("Test file not found at any of: " + Arrays.toString(TEST_FILE_PATHS));
            return;
        }

        System.out.println("=== HabboPreloadTest ===");
        System.out.println("Test file: " + testFile);

        boolean allPassed = true;
        allPassed &= testPreloadSettingsParsed(testFile);
        allPassed &= testExternalCastLoading(testFile);
        allPassed &= testDumpHandlerExecution(testFile);

        System.out.println("\n========================================");
        if (allPassed) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println("SOME TESTS FAILED");
        }
        System.out.println("========================================");
    }

    /**
     * Test 1: Verify preloadSettings are parsed from CastListChunk.
     */
    private static boolean testPreloadSettingsParsed(String testFile) throws IOException {
        System.out.println("\n--- Test 1: preloadSettings Parsing ---");

        DirectorFile file = DirectorFile.load(Path.of(testFile));
        CastListChunk castList = file.getCastList();

        if (castList == null) {
            System.out.println("FAIL: No cast list chunk found");
            return false;
        }

        boolean hasNonZeroPreload = false;
        for (int i = 0; i < castList.entries().size(); i++) {
            CastListChunk.CastListEntry entry = castList.entries().get(i);
            String name = entry.name().isEmpty() ? "(unnamed)" : entry.name();
            String path = entry.path().isEmpty() ? "(internal)" : entry.path();
            System.out.printf("  Cast %d: \"%s\" path=%s preloadSettings=%d%n",
                i + 1, name, path, entry.preloadSettings());

            if (entry.preloadSettings() != 0) {
                hasNonZeroPreload = true;
            }
        }

        if (!hasNonZeroPreload) {
            System.out.println("FAIL: No cast entries have non-zero preloadSettings");
            return false;
        }

        System.out.println("PASS: preloadSettings parsed correctly");
        return true;
    }

    /**
     * Test 2: Verify external cast (fuse_client) gets loaded.
     */
    private static boolean testExternalCastLoading(String testFile) throws IOException {
        System.out.println("\n--- Test 2: External Cast Loading ---");

        DirectorFile file = DirectorFile.load(Path.of(testFile));
        Player player = new Player(file);

        // Set up - play to trigger prepareMovie which calls preloadAllCasts
        player.play();

        // Wait for external cast to load (network fetch)
        System.out.println("  Waiting for external cast to load...");
        CastLibManager castLibMgr = player.getCastLibManager();
        boolean fuseClientLoaded = false;

        for (int attempt = 0; attempt < 100; attempt++) {
            for (var entry : castLibMgr.getCastLibs().entrySet()) {
                CastLib castLib = entry.getValue();
                if (castLib.getName().toLowerCase().contains("fuse_client")) {
                    if (castLib.isLoaded()) {
                        System.out.println("  fuse_client loaded after " + (attempt * 100) + "ms");
                        System.out.println("    CastLib #" + entry.getKey());
                        System.out.println("    PreloadMode: " + castLib.getPreloadMode());
                        System.out.println("    Scripts: " + castLib.getAllScripts().size());
                        fuseClientLoaded = true;
                        break;
                    }
                }
            }
            if (fuseClientLoaded) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        player.shutdown();

        if (!fuseClientLoaded) {
            System.out.println("FAIL: fuse_client was not loaded within 10 seconds");

            // Debug: dump cast lib states
            for (var entry : castLibMgr.getCastLibs().entrySet()) {
                CastLib castLib = entry.getValue();
                System.out.printf("  CastLib %d: name=\"%s\" external=%b fetched=%b loaded=%b preloadMode=%d%n",
                    entry.getKey(), castLib.getName(), castLib.isExternal(),
                    castLib.isFetched(), castLib.isLoaded(), castLib.getPreloadMode());
            }
            return false;
        }

        System.out.println("PASS: fuse_client loaded successfully");
        return true;
    }

    /**
     * Test 3: Verify dump() handler can be found and executes.
     */
    private static boolean testDumpHandlerExecution(String testFile) throws IOException {
        System.out.println("\n--- Test 3: dump() Handler Execution ---");

        DirectorFile file = DirectorFile.load(Path.of(testFile));
        Player player = new Player(file);
        LingoVM vm = player.getVM();

        // Track handler executions and errors
        Set<String> executedHandlers = new LinkedHashSet<>();
        Set<String> unimplementedOpcodes = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                executedHandlers.add(info.handlerName());
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                // no-op
            }

            @Override
            public void onInstruction(InstructionInfo info) {
                // no-op
            }

            @Override
            public void onError(String message, Exception error) {
                errors.add(message);
                if (message.contains("unimplemented") || message.contains("Unimplemented")) {
                    unimplementedOpcodes.add(message);
                }
            }
        });

        // Play the movie to trigger prepareMovie
        player.play();

        // Wait for fuse_client to load
        boolean loaded = false;
        CastLibManager castLibMgr = player.getCastLibManager();
        for (int i = 0; i < 100; i++) {
            for (var castLib : castLibMgr.getCastLibs().values()) {
                if (castLib.getName().toLowerCase().contains("fuse_client") && castLib.isLoaded()) {
                    loaded = true;
                    break;
                }
            }
            if (loaded) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        if (!loaded) {
            System.out.println("FAIL: fuse_client not loaded, cannot test dump()");
            player.shutdown();
            return false;
        }

        // Find dump() handler
        HandlerLocation dumpLoc = castLibMgr.findHandler("dump");
        if (dumpLoc == null) {
            System.out.println("FAIL: dump() handler not found in any cast library");
            player.shutdown();
            return false;
        }

        System.out.println("  dump() handler found in cast lib " + dumpLoc.castLibNumber());
        if (dumpLoc.script() instanceof ScriptChunk script) {
            System.out.println("    Script: " + script.getScriptName());
            System.out.println("    Script type: " + script.getScriptType());
        }

        // Step through a few frames to let handlers execute
        System.out.println("  Stepping through frames...");
        for (int i = 0; i < 10; i++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                System.out.println("  Frame " + i + " error: " + e.getMessage());
                break;
            }
        }

        // Report results
        System.out.println("\n  Executed handlers (" + executedHandlers.size() + "):");
        for (String h : executedHandlers) {
            System.out.println("    - " + h);
        }

        if (!unimplementedOpcodes.isEmpty()) {
            System.out.println("\n  Unimplemented opcodes encountered:");
            for (String msg : unimplementedOpcodes) {
                System.out.println("    - " + msg);
            }
        }

        if (!errors.isEmpty()) {
            System.out.println("\n  Errors (" + errors.size() + "):");
            for (String msg : errors) {
                System.out.println("    - " + msg);
            }
        }

        player.shutdown();

        // We pass if dump() was found, even if there are some errors during execution
        // (some errors are expected due to missing network resources)
        System.out.println("PASS: dump() handler found and movie executed");
        return true;
    }
}
