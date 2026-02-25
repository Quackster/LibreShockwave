package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
import com.libreshockwave.player.wasm.net.WasmNetManager;
import com.libreshockwave.player.wasm.render.SoftwareRenderer;

/**
 * Thin wrapper around Player for WASM execution.
 * No browser/DOM dependencies - all rendering is done via SoftwareRenderer
 * and the animation loop is managed by JavaScript.
 */
public class WasmPlayer {

    private Player player;
    private SoftwareRenderer renderer;
    private WasmNetManager netManager;

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

    public void shutdown() {
        if (player != null) {
            player.shutdown();
        }
    }
}
