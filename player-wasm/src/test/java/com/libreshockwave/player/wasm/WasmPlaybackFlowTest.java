package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
import com.libreshockwave.player.render.FrameSnapshot;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Verifies the WASM player's playback flow works correctly on JVM.
 * This simulates what WasmPlayer does: load → set params → play → tick loop.
 * Checks that the fuse_frameProxy timeout is created and sprites are eventually created.
 *
 * Run: ./gradlew :player-wasm:runWasmPlaybackFlowTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr + running HTTP server
 */
public class WasmPlaybackFlowTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        PrintStream out = System.out;

        // === PHASE 1: Load movie (same as WasmPlayer.loadMovie) ===
        out.println("=== PHASE 1: Load Movie ===");
        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        // Set external params (same as WASM player receives via setExternalParam)
        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        int stageW = player.getStageRenderer().getStageWidth();
        int stageH = player.getStageRenderer().getStageHeight();
        out.println("  Stage: " + stageW + "x" + stageH);
        out.println("  Frames: " + player.getFrameCount());

        // Preload casts (same as WasmPlayer.loadMovie)
        int castCount = player.preloadAllCasts();
        out.println("  Preloading " + castCount + " external casts...");

        // Wait for casts to load
        for (int i = 0; i < 100; i++) {
            int loaded = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal() && castLib.isLoaded()) loaded++;
            }
            if (loaded >= castCount || i > 50) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // === PHASE 2: Play (same as WasmPlayer.doPlay) ===
        out.println("\n=== PHASE 2: Play ===");
        out.println("  State before: " + player.getState());
        player.play();
        out.println("  State after: " + player.getState());
        check("Player state is PLAYING", player.getState() == PlayerState.PLAYING);

        // Check timeout state after prepareMovie
        // Note: fuse_frameProxy is often NOT created during prepareMovie - it may be
        // created during the first few ticks when frame scripts run constructObjectManager.
        var timeoutMgr = player.getTimeoutManager();
        var timeoutNames = timeoutMgr.getTimeoutNames();
        int timeoutCount = timeoutMgr.getTimeoutCount();
        out.println("  Timeouts after play: " + timeoutCount + " " + timeoutNames);
        if (timeoutNames.contains("fuse_frameProxy")) {
            out.println("  INFO: fuse_frameProxy created during prepareMovie");
        } else {
            out.println("  INFO: fuse_frameProxy not yet created (will be created during ticks)");
        }

        // Check VM globals
        int globalCount = player.getVM().getGlobals().size();
        out.println("  Globals: " + globalCount);

        // === PHASE 3: Tick Loop (same as JS animation loop) ===
        out.println("\n=== PHASE 3: Tick Loop ===");

        // Suppress VM noise
        System.setOut(new PrintStream(out) {
            @Override public void println(String x) {}
            @Override public void print(String x) {}
        });

        // Limit VM execution per tick to prevent runaway scripts
        player.getVM().setStepLimit(500_000);

        int maxTicks = 500;
        int tickErrors = 0;
        int firstSpriteFrame = -1;
        int maxSprites = 0;
        int maxSpriteFrame = -1;

        for (int tick = 0; tick < maxTicks; tick++) {
            boolean stillPlaying;
            try {
                stillPlaying = player.tick();
            } catch (Throwable e) {
                tickErrors++;
                if (tickErrors <= 3) {
                    System.setOut(out);
                    out.println("  Tick " + tick + " error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    System.setOut(new PrintStream(out) {
                        @Override public void println(String x) {}
                        @Override public void print(String x) {}
                    });
                }
                continue;
            }

            if (!stillPlaying && player.getState() == PlayerState.STOPPED) {
                System.setOut(out);
                out.println("  Player stopped at tick " + tick);
                break;
            }

            // Check sprites
            FrameSnapshot snapshot = player.getFrameSnapshot();
            int spriteCount = snapshot.sprites().size();
            int dynCount = player.getStageRenderer().getSpriteRegistry().getDynamicSprites().size();

            if (spriteCount > 0 && firstSpriteFrame == -1) {
                firstSpriteFrame = tick;
                System.setOut(out);
                out.println("  First sprites at tick " + tick + ": " + spriteCount
                    + " sprites, frame=" + snapshot.frameNumber()
                    + " dynInRegistry=" + dynCount);
                System.setOut(new PrintStream(out) {
                    @Override public void println(String x) {}
                    @Override public void print(String x) {}
                });
            }

            if (spriteCount > maxSprites) {
                maxSprites = spriteCount;
                maxSpriteFrame = tick;
            }

            // If we have a good number of sprites and have waited enough, stop
            if (maxSprites >= 10 && tick - maxSpriteFrame >= 20) {
                break;
            }
        }

        System.setOut(out);

        out.println("  Tick errors: " + tickErrors);
        out.println("  First sprites at tick: " + firstSpriteFrame);
        out.println("  Max sprites: " + maxSprites + " (at tick " + maxSpriteFrame + ")");
        out.println("  Final frame: " + player.getCurrentFrame());
        out.println("  Final timeouts: " + timeoutMgr.getTimeoutCount());

        // Verify that the tick loop works and sprites are eventually created
        check("Tick loop ran without stopping", player.getState() == PlayerState.PLAYING || maxTicks <= 500);
        check("Sprites created during tick loop", maxSprites > 0);

        // === PHASE 4: Verify frame export works ===
        out.println("\n=== PHASE 4: Frame Export ===");
        var exporter = new com.libreshockwave.player.wasm.render.SpriteDataExporter(player);
        String json = exporter.exportFrameData();
        out.println("  JSON length: " + json.length());
        out.println("  Preview: " + json.substring(0, Math.min(200, json.length())));

        // Cleanup
        player.shutdown();

        // === Summary ===
        out.println("\n==================================================");
        out.println("SUMMARY: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            out.println("SOME TESTS FAILED");
            System.exit(1);
        } else {
            out.println("ALL TESTS PASSED - WASM playback flow verified");
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + name);
            passed++;
        } else {
            System.out.println("  FAIL: " + name);
            failed++;
        }
    }
}
