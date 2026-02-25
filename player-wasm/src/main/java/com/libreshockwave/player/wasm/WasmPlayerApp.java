package com.libreshockwave.player.wasm;

import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Entry point and JS-facing API for the WebAssembly player.
 * All public static methods annotated with @JSExport are callable from JavaScript.
 */
public class WasmPlayerApp {

    private static WasmPlayer wasmPlayer;
    private static String canvasId = "shockwave-stage";

    public static void main(String[] args) {
        System.out.println("[LibreShockwave] WASM player initialized");
    }

    /**
     * Set the target canvas element ID.
     */
    @JSExport
    public static void setCanvasId(String id) {
        canvasId = id;
    }

    /**
     * Load a movie from raw bytes (fetched by JS).
     * @param data The DCR/DIR file bytes as an Int8Array
     * @param basePath The base URL for resolving relative resource paths
     */
    @JSExport
    public static void loadMovieFromBytes(Int8Array data, String basePath) {
        byte[] bytes = new byte[data.getLength()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = data.get(i);
        }

        // Shut down any previous player
        if (wasmPlayer != null) {
            wasmPlayer.shutdown();
        }

        wasmPlayer = new WasmPlayer(canvasId);
        wasmPlayer.loadMovie(bytes, basePath);
    }

    /**
     * Start or resume playback.
     */
    @JSExport
    public static void play() {
        if (wasmPlayer != null) {
            wasmPlayer.play();
        }
    }

    /**
     * Pause playback.
     */
    @JSExport
    public static void pause() {
        if (wasmPlayer != null) {
            wasmPlayer.pause();
        }
    }

    /**
     * Stop playback and reset to frame 1.
     */
    @JSExport
    public static void stop() {
        if (wasmPlayer != null) {
            wasmPlayer.stop();
        }
    }

    /**
     * Jump to a specific frame.
     */
    @JSExport
    public static void goToFrame(int frame) {
        if (wasmPlayer != null) {
            wasmPlayer.goToFrame(frame);
        }
    }

    /**
     * Get the current frame number.
     */
    @JSExport
    public static int getCurrentFrame() {
        return wasmPlayer != null ? wasmPlayer.getCurrentFrame() : 0;
    }

    /**
     * Get the total number of frames.
     */
    @JSExport
    public static int getFrameCount() {
        return wasmPlayer != null ? wasmPlayer.getFrameCount() : 0;
    }
}
