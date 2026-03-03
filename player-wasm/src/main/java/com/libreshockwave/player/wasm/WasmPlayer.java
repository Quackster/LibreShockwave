package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
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
            return false;
        }

        netManager = new WasmNetManager(basePath);
        player = new Player(file, netManager);

        int stageWidth = player.getStageRenderer().getStageWidth();
        int stageHeight = player.getStageRenderer().getStageHeight();
        renderer = new SoftwareRenderer(player, stageWidth, stageHeight);
        spriteExporter = new SpriteDataExporter(player);

        // Preload external casts NOW (during load, not during play)
        // This gives fetch requests a head start before the user presses play
        expectedCasts = player.preloadAllCasts();

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
            doPlay();
        }
    }

    /**
     * Advance one frame. Returns false only when STOPPED (keeps JS loop alive for PAUSED).
     * Unlike Swing's timer (which keeps firing even after errors), WASM needs explicit
     * resilience: catch exceptions but keep the animation loop running.
     * @return true if animation loop should continue (PLAYING or PAUSED), false if STOPPED
     */
    public boolean tick() {
        if (player == null) return false;
        PlayerState state = player.getState();
        if (state == PlayerState.STOPPED) {
            // If play was initiated but deferred (waiting for casts to load),
            // return true to keep the JS animation loop alive.
            if (playRequested && !moviePrepared) {
                return true;
            }
            return false;
        }
        if (state == PlayerState.PAUSED) return true;

        try {
            return player.tick();
        } catch (Throwable e) {
            // Don't stop the animation loop - try to continue on the next frame
            // This matches Swing's behavior where the EDT catches errors but the timer keeps firing
            return true;
        }
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
            doPlay();
        } else {
            // Defer play until all external casts have been fetched
            playRequested = true;
        }
    }

    private void doPlay() {
        playRequested = false;
        moviePrepared = true;
        player.play();
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
}
