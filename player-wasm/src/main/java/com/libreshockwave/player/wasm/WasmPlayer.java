package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.wasm.debug.WasmDebugController;
import com.libreshockwave.player.wasm.net.WasmNetManager;
import com.libreshockwave.player.wasm.render.SoftwareRenderer;
import com.libreshockwave.player.wasm.render.SpriteDataExporter;

import java.util.List;

/**
 * Thin wrapper around Player for WASM execution.
 * No browser/DOM dependencies - all rendering is done via SoftwareRenderer
 * and the animation loop is managed by JavaScript.
 */
public class WasmPlayer {

    private Player player;
    private SoftwareRenderer renderer;
    private SpriteDataExporter spriteExporter;
    private WasmNetManager netManager;
    private WasmDebugController debugController;
    private boolean playRequested = false;
    private boolean moviePrepared = false;
    private int expectedCasts = 0;
    private int completedCasts = 0;

    /**
     * Load a Director movie from raw bytes.
     * @return true if loaded successfully
     */
    public boolean loadMovie(byte[] data, String basePath) {
        DirectorFile file;
        try {
            file = DirectorFile.load(data);
        } catch (Exception e) {
            System.err.println("[WasmPlayer] Failed to parse Director file: " + e.getMessage());
            return false;
        }

        netManager = new WasmNetManager(basePath);
        player = new Player(file, netManager);

        int stageWidth = player.getStageRenderer().getStageWidth();
        int stageHeight = player.getStageRenderer().getStageHeight();
        renderer = new SoftwareRenderer(player, stageWidth, stageHeight);
        spriteExporter = new SpriteDataExporter(player);

        System.out.println("[WasmPlayer] Movie loaded: " + stageWidth + "x" + stageHeight
                + ", " + player.getFrameCount() + " frames, tempo=" + player.getTempo());

        // Preload external casts NOW (during load, not during play)
        // This gives fetch requests a head start before the user presses play
        expectedCasts = player.preloadAllCasts();
        System.out.println("[WasmPlayer] Preloading " + expectedCasts + " external casts");

        // Render the initial frame
        renderer.render();
        return true;
    }

    /**
     * Called from WasmPlayerApp when a fetch completes (success or error).
     * Tracks completion count and triggers deferred play when all casts are done.
     */
    public void onCastFetchDone() {
        completedCasts++;
        if (playRequested && !moviePrepared && completedCasts >= expectedCasts) {
            System.out.println("[WasmPlayer] All " + completedCasts + " casts fetched, starting deferred play");
            doPlay();
        }
    }

    /**
     * Advance one frame. Returns false only when STOPPED (keeps JS loop alive for PAUSED).
     * Unlike Swing's timer (which keeps firing even after errors), WASM needs explicit
     * resilience: catch exceptions but keep the animation loop running.
     * @return true if animation loop should continue (PLAYING or PAUSED), false if STOPPED
     */
    private int tickCount = 0;
    private int consecutiveErrors = 0;

    public boolean tick() {
        if (player == null) return false;
        PlayerState stateBefore = player.getState();
        if (stateBefore == PlayerState.STOPPED) {
            // If play was initiated but deferred (waiting for casts to load),
            // return true to keep the JS animation loop alive.
            // Without this, the animation loop stops before doPlay() gets a chance to run.
            if (playRequested && !moviePrepared) {
                if (tickCount == 0 || tickCount % 100 == 0) {
                    System.out.println("[WasmPlayer] tick() deferred - waiting for casts ("
                        + completedCasts + "/" + expectedCasts + ")");
                }
                tickCount++;
                return true;
            }
            // Don't spam - only log the first time
            if (tickCount == 0 || tickCount % 100 == 0) {
                System.out.println("[WasmPlayer] tick() skipped - state is STOPPED (tick=" + tickCount + ")");
            }
            tickCount++;
            return false;
        }
        if (stateBefore == PlayerState.PAUSED) return true;

        // One-shot diagnostic: check if enterFrame handlers exist in external casts
        if (tickCount == 0) {
            dumpMovieScriptHandlers();
        }

        boolean result;
        try {
            result = player.tick();
            consecutiveErrors = 0;
        } catch (Throwable e) {
            consecutiveErrors++;
            // Log first error and then periodically to avoid spamming
            if (consecutiveErrors <= 3 || consecutiveErrors % 50 == 0) {
                System.out.println("[WasmPlayer] tick " + tickCount + " EXCEPTION (#" + consecutiveErrors
                    + "): " + e.getClass().getName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    System.out.println("[WasmPlayer]   caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
            }
            // Don't stop the animation loop - try to continue on the next frame
            // This matches Swing's behavior where the EDT catches errors but the timer keeps firing
            result = true;
        }

        PlayerState stateAfter = player.getState();

        // Periodic diagnostics for first 50 ticks, then every 100th tick
        boolean shouldLog = tickCount < 50 || tickCount % 100 == 0;

        if (stateAfter != stateBefore && shouldLog) {
            System.out.println("[WasmPlayer] tick " + tickCount + ": state changed "
                + stateBefore + " -> " + stateAfter
                + " frame=" + player.getCurrentFrame()
                + " dynSprites=" + player.getStageRenderer().getSpriteRegistry().getDynamicSprites().size());
        }

        // Log timeout and network status periodically
        if (tickCount < 10 || (shouldLog && tickCount % 10 == 0)) {
            int dynSprites = player.getStageRenderer().getSpriteRegistry().getDynamicSprites().size();
            var timeoutMgr = player.getTimeoutManager();
            int timeoutCount = timeoutMgr != null ? timeoutMgr.getTimeoutCount() : -1;
            int pendingNet = netManager != null ? netManager.getPendingTaskCount() : -1;
            System.out.println("[WasmPlayer] tick " + tickCount + ": frame=" + player.getCurrentFrame()
                + " dynSprites=" + dynSprites
                + " timeouts=" + timeoutCount
                + " pendingNet=" + pendingNet);
            // On first tick, also log timeout names
            if (tickCount == 0 && timeoutMgr != null) {
                for (var t : timeoutMgr.getTimeoutNames()) {
                    System.out.println("[WasmPlayer]   timeout: " + t);
                }
            }
        }

        if (!result && shouldLog) {
            System.out.println("[WasmPlayer] tick " + tickCount + ": returned false, state=" + stateAfter);
        }
        tickCount++;
        return result;
    }

    public void render() {
        if (renderer != null) {
            renderer.render();
        }
    }

    public byte[] getFrameBuffer() {
        return renderer != null ? renderer.getFrameBuffer() : null;
    }

    public void play() {
        if (player == null) return;

        if (completedCasts >= expectedCasts) {
            System.out.println("[WasmPlayer] play() - all casts ready (" + completedCasts + "/" + expectedCasts + "), starting immediately");
            doPlay();
        } else {
            // Defer play until all external casts have been fetched
            playRequested = true;
            System.out.println("[WasmPlayer] play() - deferring until casts loaded ("
                + completedCasts + "/" + expectedCasts + " done)");
        }
    }

    private void doPlay() {
        playRequested = false;
        moviePrepared = true;
        tickCount = 0;

        // Enable debug logging during prepareMovie to trace behavior dispatch
        player.setDebugEnabled(true);

        System.out.println("[WasmPlayer] doPlay() - state before: " + player.getState());
        player.play();

        // Check what happened during prepareMovie
        var vm = player.getVM();
        int globalCount = vm != null ? vm.getGlobals().size() : -1;
        var globals = vm != null ? vm.getGlobals().keySet() : java.util.Set.of();
        var timeoutMgr = player.getTimeoutManager();
        int timeoutCount = timeoutMgr != null ? timeoutMgr.getTimeoutCount() : -1;
        var timeoutNames = timeoutMgr != null ? timeoutMgr.getTimeoutNames() : java.util.List.of();
        System.out.println("[WasmPlayer] doPlay() - state after: " + player.getState()
            + " frame=" + player.getCurrentFrame()
            + " dynSprites=" + player.getStageRenderer().getSpriteRegistry().getDynamicSprites().size()
            + " globals=" + globalCount
            + " timeouts=" + timeoutCount);
        // Print global names to see if startClient ran
        int count = 0;
        for (var g : globals) {
            if (count++ >= 20) { System.out.println("[WasmPlayer]   ... and " + (globalCount - 20) + " more"); break; }
            System.out.println("[WasmPlayer]   global: " + g);
        }
        // Print timeout names - the Object Manager uses timeouts for per-frame dispatch
        for (var t : timeoutNames) {
            System.out.println("[WasmPlayer]   timeout: " + t);
        }

        // Check fuse_frameProxy timeout (may be created later during ticks, not during prepareMovie)
        boolean hasFuseProxy = timeoutNames.contains("fuse_frameProxy");
        System.out.println("[WasmPlayer] fuse_frameProxy timeout: "
            + (hasFuseProxy ? "CREATED (during prepareMovie)" : "not yet (will be created during ticks)"));

        // Show pending network tasks
        int pendingNet = netManager != null ? netManager.getPendingTaskCount() : -1;
        System.out.println("[WasmPlayer] doPlay() - pendingNet=" + pendingNet);

        // Disable verbose debug after prepareMovie
        player.setDebugEnabled(false);
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void stop() {
        if (player != null) {
            player.stop();
            render();
        }
    }

    public void goToFrame(int frame) {
        if (player != null) {
            player.goToFrame(frame);
            render();
        }
    }

    /**
     * Step forward one frame (manual advance for frame-level stepping).
     */
    public void stepFrame() {
        if (player != null) {
            player.stepFrame();
            render();
        }
    }

    public int getCurrentFrame() {
        return player != null ? player.getCurrentFrame() : 0;
    }

    public int getFrameCount() {
        return player != null ? player.getFrameCount() : 0;
    }

    public int getTempo() {
        return player != null ? player.getTempo() : 15;
    }

    public int getStageWidth() {
        return player != null ? player.getStageRenderer().getStageWidth() : 640;
    }

    public int getStageHeight() {
        return player != null ? player.getStageRenderer().getStageHeight() : 480;
    }

    // === Debug support ===

    /**
     * Enable debug mode and create a WasmDebugController.
     */
    public void enableDebug() {
        if (player == null) return;
        debugController = new WasmDebugController();
        player.setDebugController(debugController);
        player.setDebugEnabled(true);
    }

    public WasmDebugController getDebugController() {
        return debugController;
    }

    public Player getPlayer() {
        return player;
    }

    public DirectorFile getFile() {
        return player != null ? player.getFile() : null;
    }

    public List<ScriptChunk> getAllScripts() {
        return player != null && player.getFile() != null
            ? player.getFile().getScripts() : List.of();
    }

    public CastLibManager getCastLibManager() {
        return player != null ? player.getCastLibManager() : null;
    }

    public SpriteDataExporter getSpriteExporter() {
        return spriteExporter;
    }

    /**
     * Preload all external cast libraries.
     * @return number of casts queued for loading
     */
    public int preloadAllCasts() {
        return player != null ? player.preloadAllCasts() : 0;
    }

    public void shutdown() {
        if (player != null) {
            player.shutdown();
        }
    }

    /**
     * Diagnostic: trace the exact path EventDispatcher.dispatchToMovieScripts uses.
     */
    private void dumpMovieScriptHandlers() {
        if (player == null) return;
        var clm = player.getCastLibManager();
        if (clm == null) return;

        System.out.println("[WasmPlayer] === Movie script handler dump ===");

        // 1. Main DCR
        var file = player.getFile();
        if (file != null) {
            var names = file.getScriptNames();
            System.out.println("[WasmPlayer] Main DCR: scriptNames=" + (names != null)
                + " scripts=" + file.getScripts().size());
            if (names != null) {
                for (var script : file.getScripts()) {
                    var type = script.getScriptType();
                    if (type == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                        System.out.println("[WasmPlayer]   MOVIE id=" + script.id()
                            + " handlers=" + script.handlers().size()
                            + " hasStartMovie=" + (script.findHandler("startMovie", names) != null)
                            + " hasPrepareMovie=" + (script.findHandler("prepareMovie", names) != null));
                        for (var h : script.handlers()) {
                            System.out.println("[WasmPlayer]     handler: '" + script.getHandlerName(h) + "'");
                        }
                    }
                }
            }
        }

        // 2. External casts - exact same path as EventDispatcher
        int castsChecked = 0;
        int castsSkippedNotExternal = 0;
        int castsSkippedNotLoaded = 0;
        int castsSkippedNoNames = 0;
        int movieScriptsFound = 0;
        int enterFrameHandlersFound = 0;

        for (CastLib castLib : clm.getCastLibs().values()) {
            if (!castLib.isExternal()) { castsSkippedNotExternal++; continue; }
            if (!castLib.isLoaded()) { castsSkippedNotLoaded++; continue; }
            castsChecked++;

            ScriptNamesChunk castNames = castLib.getScriptNames();
            if (castNames == null) { castsSkippedNoNames++; continue; }

            for (var script : castLib.getAllScripts()) {
                if (script.getScriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) continue;
                movieScriptsFound++;

                var handler = script.findHandler("enterFrame", castNames);
                if (handler != null) {
                    enterFrameHandlersFound++;
                    String handlerName = script.getHandlerName(handler);
                    System.out.println("[WasmPlayer]   FOUND enterFrame in cast '"
                        + castLib.getName() + "' script=" + script.id()
                        + " handler='" + handlerName + "'");
                }

                // Also list all handlers in this movie script
                for (var h : script.handlers()) {
                    String hName = script.getHandlerName(h);
                    System.out.println("[WasmPlayer]     handler: '" + hName + "'"
                        + " in cast '" + castLib.getName() + "' script=" + script.id());
                }
            }
        }

        System.out.println("[WasmPlayer] External: checked=" + castsChecked
            + " skippedNotExt=" + castsSkippedNotExternal
            + " skippedNotLoaded=" + castsSkippedNotLoaded
            + " skippedNoNames=" + castsSkippedNoNames
            + " movieScripts=" + movieScriptsFound
            + " enterFrameHandlers=" + enterFrameHandlersFound);
    }
}
