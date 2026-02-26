package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.debug.DebugControllerApi;
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

    public WasmPlayer() {
    }

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

        // Render the initial frame
        renderer.render();
        return true;
    }

    /**
     * Advance one frame. Only ticks if the player is in PLAYING state.
     * @return true if still playing
     */
    public boolean tick() {
        if (player == null || player.getState() != PlayerState.PLAYING) return false;
        return player.tick();
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
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public void stop() {
        if (player != null) {
            player.stop();
            if (renderer != null) renderer.render();
        }
    }

    public void goToFrame(int frame) {
        if (player != null) {
            player.goToFrame(frame);
            if (renderer != null) renderer.render();
        }
    }

    /**
     * Step forward one frame (manual advance for frame-level stepping).
     */
    public void stepFrame() {
        if (player != null) {
            player.stepFrame();
            if (renderer != null) renderer.render();
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
