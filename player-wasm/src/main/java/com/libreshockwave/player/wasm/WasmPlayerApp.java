package com.libreshockwave.player.wasm;

import com.libreshockwave.player.wasm.net.WasmNetManager;
import org.teavm.interop.Address;
import org.teavm.interop.Export;

/**
 * Entry point and exported API for the standard WASM player.
 * All public methods with @Export are callable from JavaScript via the WASM module exports.
 * Data is exchanged through shared byte[] buffers using raw memory addresses.
 */
public class WasmPlayerApp {

    private static WasmPlayer wasmPlayer;

    // Shared buffers for JS <-> WASM data transfer
    static byte[] movieBuffer;
    public static byte[] stringBuffer = new byte[4096];
    static byte[] netBuffer;

    public static void main(String[] args) {
        System.out.println("[LibreShockwave] WASM player initialized");
    }

    // === Movie loading ===

    @Export(name = "allocateMovieBuffer")
    public static void allocateMovieBuffer(int size) {
        movieBuffer = new byte[size];
    }

    @Export(name = "getMovieBufferAddress")
    public static int getMovieBufferAddress() {
        return movieBuffer != null ? Address.ofData(movieBuffer).toInt() : 0;
    }

    @Export(name = "getStringBufferAddress")
    public static int getStringBufferAddress() {
        return Address.ofData(stringBuffer).toInt();
    }

    /**
     * Load a movie from the movie buffer.
     * basePath must already be written to stringBuffer.
     * @return (width << 16) | height, or 0 on failure
     */
    @Export(name = "loadMovie")
    public static int loadMovie(int movieSize, int basePathLen) {
        String basePath = "";
        if (basePathLen > 0) {
            basePath = new String(stringBuffer, 0, basePathLen);
        }

        byte[] data = new byte[movieSize];
        System.arraycopy(movieBuffer, 0, data, 0, movieSize);

        if (wasmPlayer != null) {
            wasmPlayer.shutdown();
        }

        wasmPlayer = new WasmPlayer();
        if (!wasmPlayer.loadMovie(data, basePath)) {
            return 0;
        }

        int w = wasmPlayer.getStageWidth();
        int h = wasmPlayer.getStageHeight();
        return (w << 16) | h;
    }

    // === Playback ===

    /**
     * Advance one frame.
     * @return 1 if still playing, 0 if stopped
     */
    @Export(name = "tick")
    public static int tick() {
        if (wasmPlayer == null) return 0;
        return wasmPlayer.tick() ? 1 : 0;
    }

    /**
     * Render the current frame into the RGBA pixel buffer.
     * @return raw memory address of the RGBA buffer, or 0 on failure
     */
    @Export(name = "render")
    public static int render() {
        if (wasmPlayer == null) return 0;
        wasmPlayer.render();
        byte[] buf = wasmPlayer.getFrameBuffer();
        return buf != null ? Address.ofData(buf).toInt() : 0;
    }

    @Export(name = "play")
    public static void play() {
        if (wasmPlayer != null) wasmPlayer.play();
    }

    @Export(name = "pause")
    public static void pause() {
        if (wasmPlayer != null) wasmPlayer.pause();
    }

    @Export(name = "stop")
    public static void stop() {
        if (wasmPlayer != null) wasmPlayer.stop();
    }

    @Export(name = "goToFrame")
    public static void goToFrame(int frame) {
        if (wasmPlayer != null) wasmPlayer.goToFrame(frame);
    }

    // === State queries ===

    @Export(name = "getCurrentFrame")
    public static int getCurrentFrame() {
        return wasmPlayer != null ? wasmPlayer.getCurrentFrame() : 0;
    }

    @Export(name = "getFrameCount")
    public static int getFrameCount() {
        return wasmPlayer != null ? wasmPlayer.getFrameCount() : 0;
    }

    @Export(name = "getTempo")
    public static int getTempo() {
        return wasmPlayer != null ? wasmPlayer.getTempo() : 15;
    }

    @Export(name = "getStageWidth")
    public static int getStageWidth() {
        return wasmPlayer != null ? wasmPlayer.getStageWidth() : 640;
    }

    @Export(name = "getStageHeight")
    public static int getStageHeight() {
        return wasmPlayer != null ? wasmPlayer.getStageHeight() : 480;
    }

    // === Network fetch callbacks (called by JS when fetch completes) ===

    @Export(name = "allocateNetBuffer")
    public static void allocateNetBuffer(int size) {
        netBuffer = new byte[size];
    }

    @Export(name = "getNetBufferAddress")
    public static int getNetBufferAddress() {
        return netBuffer != null ? Address.ofData(netBuffer).toInt() : 0;
    }

    @Export(name = "onFetchComplete")
    public static void onFetchComplete(int taskId, int dataSize) {
        WasmNetManager mgr = WasmNetManager.getInstance();
        if (mgr != null && netBuffer != null) {
            byte[] data = new byte[dataSize];
            System.arraycopy(netBuffer, 0, data, 0, dataSize);
            mgr.onFetchComplete(taskId, data);
        }
    }

    @Export(name = "onFetchError")
    public static void onFetchError(int taskId, int status) {
        WasmNetManager mgr = WasmNetManager.getInstance();
        if (mgr != null) {
            mgr.onFetchError(taskId, status);
        }
    }

    // === Internal helpers ===

    /**
     * Write a string to the shared string buffer (for URL passing to JS).
     */
    public static void writeStringToBuffer(String s) {
        byte[] bytes = s.getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
    }
}
