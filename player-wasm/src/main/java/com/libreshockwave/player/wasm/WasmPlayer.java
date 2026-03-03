package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;

/**
 * Thin wrapper around Player for WASM execution.
 * Manages movie loading, deferred play (waiting for external casts),
 * and fetch result delivery. No @Import dependencies.
 */
public class WasmPlayer {

    private Player player;
    private QueuedNetProvider netProvider;
    private SpriteDataExporter spriteExporter;
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

        netProvider = new QueuedNetProvider(basePath);
        player = new Player(file, netProvider);
        spriteExporter = new SpriteDataExporter(player);

        // Preload external casts during load (gives fetch requests a head start)
        expectedCasts = player.preloadAllCasts();

        return true;
    }

    /**
     * Called when a fetch completes (success or error).
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
     * Catches exceptions to prevent JS animation loop death.
     */
    public boolean tick() {
        if (player == null) return false;
        PlayerState state = player.getState();
        if (state == PlayerState.STOPPED) {
            // Keep alive while waiting for casts to load
            return playRequested && !moviePrepared;
        }
        if (state == PlayerState.PAUSED) return true;

        try {
            return player.tick();
        } catch (Throwable e) {
            return true;
        }
    }

    public void play() {
        if (player == null) return;

        if (completedCasts >= expectedCasts) {
            doPlay();
        } else {
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
        if (player != null) player.stop();
    }

    public void goToFrame(int frame) {
        if (player != null) player.goToFrame(frame);
    }

    public void stepFrame() {
        if (player != null) player.stepFrame();
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

    public Player getPlayer() {
        return player;
    }

    public QueuedNetProvider getNetProvider() {
        return netProvider;
    }

    public SpriteDataExporter getSpriteExporter() {
        return spriteExporter;
    }

    public DirectorFile getFile() {
        return player != null ? player.getFile() : null;
    }

    public void shutdown() {
        if (player != null) {
            player.shutdown();
        }
    }
}
