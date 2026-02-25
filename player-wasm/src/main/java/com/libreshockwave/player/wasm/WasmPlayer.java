package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerState;
import com.libreshockwave.player.wasm.canvas.CanvasStageRenderer;
import com.libreshockwave.player.wasm.net.FetchNetManager;

import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;

/**
 * Single-threaded wrapper around Player for browser execution.
 * Uses requestAnimationFrame for the game loop instead of Swing Timer.
 * All VM execution is synchronous on the main thread.
 */
public class WasmPlayer {

    private final String canvasId;
    private Player player;
    private CanvasStageRenderer renderer;
    private FetchNetManager fetchNetManager;
    private boolean playing = false;
    private double lastFrameTime = 0;

    public WasmPlayer(String canvasId) {
        this.canvasId = canvasId;
    }

    /**
     * Load a Director movie from raw bytes.
     */
    public void loadMovie(byte[] data, String basePath) {
        DirectorFile file = DirectorFile.fromBytes(data);
        if (file == null) {
            System.err.println("[WasmPlayer] Failed to parse Director file");
            return;
        }

        player = new Player(file);

        // Set up browser fetch as the network provider
        fetchNetManager = new FetchNetManager(basePath);
        player.setNetProvider(fetchNetManager);

        // Set up the canvas renderer
        HTMLDocument doc = Window.current().getDocument();
        HTMLCanvasElement canvas = (HTMLCanvasElement) doc.getElementById(canvasId);
        if (canvas == null) {
            System.err.println("[WasmPlayer] Canvas element not found: " + canvasId);
            return;
        }

        int stageWidth = player.getStageRenderer().getStageWidth();
        int stageHeight = player.getStageRenderer().getStageHeight();
        canvas.setWidth(stageWidth);
        canvas.setHeight(stageHeight);

        renderer = new CanvasStageRenderer(canvas, player);

        System.out.println("[WasmPlayer] Movie loaded: " + stageWidth + "x" + stageHeight
                + ", " + player.getFrameCount() + " frames, tempo=" + player.getTempo());

        // Render the initial state
        renderer.render();
    }

    /**
     * Start or resume playback.
     */
    public void play() {
        if (player == null) return;

        player.play();
        if (!playing) {
            playing = true;
            lastFrameTime = 0;
            requestFrame();
        }
    }

    /**
     * Pause playback.
     */
    public void pause() {
        if (player == null) return;
        player.pause();
        playing = false;
    }

    /**
     * Stop playback and reset.
     */
    public void stop() {
        if (player == null) return;
        player.stop();
        playing = false;
        if (renderer != null) {
            renderer.render();
        }
    }

    /**
     * Jump to a specific frame.
     */
    public void goToFrame(int frame) {
        if (player != null) {
            player.goToFrame(frame);
            if (renderer != null) {
                renderer.render();
            }
        }
    }

    public int getCurrentFrame() {
        return player != null ? player.getCurrentFrame() : 0;
    }

    public int getFrameCount() {
        return player != null ? player.getFrameCount() : 0;
    }

    /**
     * Shut down the player and release resources.
     */
    public void shutdown() {
        playing = false;
        if (player != null) {
            player.shutdown();
        }
    }

    /**
     * Request the next animation frame from the browser.
     */
    private void requestFrame() {
        Window.requestAnimationFrame(this::onAnimationFrame);
    }

    /**
     * Animation frame callback. Enforces tempo-based frame rate.
     */
    private void onAnimationFrame(double timestamp) {
        if (!playing || player == null || player.getState() != PlayerState.PLAYING) {
            playing = false;
            return;
        }

        // Enforce tempo-based frame rate
        double msPerFrame = 1000.0 / player.getTempo();
        if (lastFrameTime == 0) {
            lastFrameTime = timestamp;
        }

        double elapsed = timestamp - lastFrameTime;
        if (elapsed >= msPerFrame) {
            lastFrameTime = timestamp - (elapsed % msPerFrame);

            // Tick the player (synchronous)
            boolean stillPlaying = player.tick();
            if (!stillPlaying) {
                playing = false;
                return;
            }

            // Render the frame
            if (renderer != null) {
                renderer.render();
            }
        }

        // Continue the loop
        requestFrame();
    }
}
