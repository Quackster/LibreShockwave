package com.libreshockwave.runtime;

import com.libreshockwave.execution.DirPlayer;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.util.concurrent.TimeUnit;

/**
 * Test runner for HTTP network loading.
 * Tests loading Director movies and external casts over HTTP.
 */
public class NetworkTest {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:8080/habbo.dcr";

        System.out.println("=== LibreShockwave Network Loading Test ===");
        System.out.println("URL: " + url);
        System.out.println();

        try {
            testMovieLoading(url);
            System.out.println("\n=== All Network Tests Passed ===");
        } catch (Exception e) {
            System.err.println("\n=== Test Failed ===");
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void testMovieLoading(String url) throws Exception {
        System.out.println("--- Test: Load movie from HTTP URL ---");

        DirPlayer player = new DirPlayer();

        // Load movie from URL
        long startTime = System.currentTimeMillis();
        player.loadMovieFromUrl(url).get(60, TimeUnit.SECONDS);
        long loadTime = System.currentTimeMillis() - startTime;

        System.out.println("  Movie loaded in " + loadTime + "ms");
        System.out.println("  Stage: " + player.getStageWidth() + "x" + player.getStageHeight());
        System.out.println("  Tempo: " + player.getTempo() + " fps");
        System.out.println("  Scripts: " + player.getFile().getScripts().size());

        // Check cast manager
        CastManager castManager = player.getCastManager();
        System.out.println("  Casts: " + castManager.getCastCount());

        // List external casts
        int externalCount = 0;
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal()) {
                externalCount++;
                System.out.println("    External: " + cast.getName() + " -> " + cast.getFileName() +
                    " (state: " + cast.getState() + ")");
            }
        }
        System.out.println("  External casts: " + externalCount);

        // Try to preload external casts
        if (externalCount > 0) {
            System.out.println("\n--- Test: Preload external casts ---");
            startTime = System.currentTimeMillis();
            castManager.preloadCastsAsync(CastManager.PreloadReason.MOVIE_LOADED)
                .get(120, TimeUnit.SECONDS);
            loadTime = System.currentTimeMillis() - startTime;
            System.out.println("  Preload completed in " + loadTime + "ms");

            // Check loaded state
            for (CastLib cast : castManager.getCasts()) {
                if (cast.isExternal()) {
                    System.out.println("    " + cast.getName() + ": " + cast.getState() +
                        " (" + cast.getMemberCount() + " members)");
                }
            }
        }

        // Test preloadNetThing via VM
        System.out.println("\n--- Test: preloadNetThing handler ---");
        NetManager netManager = player.getNetManager();

        // Test a simple file request
        String testUrl = "test.txt";
        int taskId = netManager.preloadNetThing(testUrl);
        System.out.println("  preloadNetThing('" + testUrl + "') -> task " + taskId);

        // Don't wait for this one, just check netDone
        boolean done = netManager.isTaskDone(taskId);
        System.out.println("  netDone(" + taskId + ") -> " + done + " (immediate)");

        // Execute movie events
        System.out.println("\n--- Test: Execute movie events ---");

        System.out.println("  Dispatching prepareMovie...");
        player.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);

        System.out.println("  Dispatching startMovie...");
        player.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);

        System.out.println("  Dispatching enterFrame...");
        player.dispatchEvent(DirPlayer.MovieEvent.ENTER_FRAME);

        System.out.println("  Dispatching exitFrame...");
        player.dispatchEvent(DirPlayer.MovieEvent.EXIT_FRAME);

        System.out.println("\n  Movie loading test: PASSED");
    }
}
