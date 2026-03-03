package com.libreshockwave.player.wasm;

import org.teavm.interop.Address;
import org.teavm.interop.Export;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single entry point for the WASM player.
 * All @Export static methods are callable from JavaScript.
 * Zero @Import annotations — WASM is a pure computation engine.
 *
 * Data exchange uses shared byte[] buffers with raw memory addresses.
 * JS writes data into buffers, calls exports; WASM reads from buffers.
 * WASM writes results into buffers; JS reads via memory addresses.
 */
public class WasmEntry {

    private static WasmPlayer wasmPlayer;
    private static String lastError = null;

    // Shared buffers for JS <-> WASM data transfer
    private static byte[] movieBuffer;
    private static byte[] stringBuffer = new byte[4096];
    private static byte[] netBuffer;
    private static byte[] largeBuffer;

    public static void main(String[] args) {
    }

    // === Buffer management ===

    @Export(name = "allocateBuffer")
    public static int allocateBuffer(int size) {
        movieBuffer = new byte[size];
        return Address.ofData(movieBuffer).toInt();
    }

    @Export(name = "getStringBufferAddress")
    public static int getStringBufferAddress() {
        return Address.ofData(stringBuffer).toInt();
    }

    @Export(name = "getLargeBufferAddress")
    public static int getLargeBufferAddress() {
        return largeBuffer != null ? Address.ofData(largeBuffer).toInt() : 0;
    }

    // === Movie loading ===

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
     * Override the VM step limit for subsequent ticks.
     * Default is 100,000 (set in WasmPlayer.play()). Use a higher value in testing
     * environments where the animation loop timing doesn't matter.
     */
    @Export(name = "setVmStepLimit")
    public static void setVmStepLimit(int limit) {
        if (wasmPlayer != null && wasmPlayer.getPlayer() != null) {
            wasmPlayer.getPlayer().getVM().setStepLimit(limit);
        }
    }

    /**
     * Preload all external casts (queue fetch requests before play).
     * @return number of casts queued for loading
     */
    @Export(name = "preloadCasts")
    public static int preloadCasts() {
        if (wasmPlayer == null) return 0;
        try {
            return wasmPlayer.preloadCasts();
        } catch (Throwable e) {
            captureError("preloadCasts", e);
            return 0;
        }
    }

    @Export(name = "play")
    public static void play() {
        if (wasmPlayer == null) return;
        try {
            lastError = null;
            wasmPlayer.play();
        } catch (Throwable e) {
            captureError("play", e);
        }
    }

    /**
     * Advance one frame.
     * @return 1 if still playing/paused, 0 if stopped
     */
    @Export(name = "tick")
    public static int tick() {
        if (wasmPlayer == null) return 0;
        try {
            lastError = null;
            return wasmPlayer.tick() ? 1 : 0;
        } catch (Throwable e) {
            captureError("tick", e);
            return 1; // Keep animation loop alive
        }
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

    @Export(name = "stepForward")
    public static void stepForward() {
        if (wasmPlayer != null) wasmPlayer.stepFrame();
    }

    @Export(name = "stepBackward")
    public static void stepBackward() {
        if (wasmPlayer != null) {
            int frame = wasmPlayer.getCurrentFrame();
            if (frame > 1) {
                wasmPlayer.goToFrame(frame - 1);
            }
        }
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

    // === Sprite data export ===

    /**
     * Export current frame sprite data as JSON.
     * @return JSON byte length in largeBuffer
     */
    @Export(name = "getFrameDataJson")
    public static int getFrameDataJson() {
        SpriteDataExporter exporter = spriteExporter();
        if (exporter == null) return 0;
        try {
            return writeJsonToLargeBuffer(exporter.exportFrameData());
        } catch (Throwable e) {
            captureError("getFrameDataJson", e);
            return 0;
        }
    }

    /**
     * Get bitmap RGBA data for a cast member.
     * @return memory address of RGBA data, or 0 if not found
     */
    @Export(name = "getBitmapData")
    public static int getBitmapData(int memberId) {
        SpriteDataExporter exporter = spriteExporter();
        if (exporter == null) return 0;
        byte[] rgba = exporter.getBitmapRGBA(memberId);
        return rgba != null ? Address.ofData(rgba).toInt() : 0;
    }

    @Export(name = "getBitmapWidth")
    public static int getBitmapWidth(int memberId) {
        SpriteDataExporter exporter = spriteExporter();
        return exporter != null ? exporter.getBitmapWidth(memberId) : 0;
    }

    @Export(name = "getBitmapHeight")
    public static int getBitmapHeight(int memberId) {
        SpriteDataExporter exporter = spriteExporter();
        return exporter != null ? exporter.getBitmapHeight(memberId) : 0;
    }

    // === Network polling (JS reads pending requests from WASM) ===

    /**
     * Get number of pending fetch requests.
     */
    @Export(name = "getPendingFetchCount")
    public static int getPendingFetchCount() {
        QueuedNetProvider net = netProvider();
        return net != null ? net.getPendingRequests().size() : 0;
    }

    /**
     * Serialize all pending fetch requests as JSON.
     * @return JSON byte length in largeBuffer
     */
    @Export(name = "getPendingFetchJson")
    public static int getPendingFetchJson() {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        return writeJsonToLargeBuffer(net.serializePendingRequests());
    }

    /**
     * Clear pending requests after JS has read them.
     */
    @Export(name = "drainPendingFetches")
    public static void drainPendingFetches() {
        QueuedNetProvider net = netProvider();
        if (net != null) net.drainPendingRequests();
    }

    // === Network delivery (JS delivers fetch results to WASM) ===

    @Export(name = "allocateNetBuffer")
    public static int allocateNetBuffer(int size) {
        netBuffer = new byte[size];
        return Address.ofData(netBuffer).toInt();
    }

    /**
     * Deliver a successful fetch result.
     * Data must already be written to netBuffer.
     */
    @Export(name = "deliverFetchResult")
    public static void deliverFetchResult(int taskId, int dataSize) {
        try {
            lastError = null;
            QueuedNetProvider net = netProvider();
            if (net == null || netBuffer == null) return;

            byte[] data = new byte[dataSize];
            System.arraycopy(netBuffer, 0, data, 0, dataSize);
            String url = net.getTaskUrl(taskId);
            net.onFetchComplete(taskId, data);

            // Try to load as external cast
            if (url != null) {
                tryLoadExternalCast(url, data);
            }
        } catch (Throwable e) {
            captureError("deliverFetchResult", e);
        }
    }

    /**
     * Deliver a fetch error.
     */
    @Export(name = "deliverFetchError")
    public static void deliverFetchError(int taskId, int status) {
        try {
            lastError = null;
            QueuedNetProvider net = netProvider();
            if (net != null) {
                net.onFetchError(taskId, status);
            }
        } catch (Throwable e) {
            captureError("deliverFetchError", e);
        }
    }

    // === External parameters ===

    /**
     * Set an external parameter (Shockwave PARAM tag).
     * Key is at stringBuffer[0..keyLen), value at stringBuffer[keyLen..keyLen+valueLen).
     */
    @Export(name = "setExternalParam")
    public static void setExternalParam(int keyLen, int valueLen) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        String key = new String(stringBuffer, 0, keyLen);
        String value = new String(stringBuffer, keyLen, valueLen);
        Map<String, String> current = new LinkedHashMap<>(wasmPlayer.getPlayer().getExternalParams());
        current.put(key, value);
        wasmPlayer.getPlayer().setExternalParams(current);
    }

    @Export(name = "clearExternalParams")
    public static void clearExternalParams() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        wasmPlayer.getPlayer().setExternalParams(null);
    }

    // === Error tracking ===

    /**
     * Get the last error message.
     * @return byte length written to stringBuffer, or 0 if no error
     */
    @Export(name = "getLastError")
    public static int getLastError() {
        if (lastError == null) return 0;
        byte[] bytes = lastError.getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        return len;
    }

    // === Internal helpers ===

    private static void captureError(String context, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(context).append("] ").append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            sb.append(" <- ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        lastError = sb.toString();
    }

    private static SpriteDataExporter spriteExporter() {
        return wasmPlayer != null ? wasmPlayer.getSpriteExporter() : null;
    }

    private static QueuedNetProvider netProvider() {
        return wasmPlayer != null ? wasmPlayer.getNetProvider() : null;
    }

    private static int writeJsonToLargeBuffer(String json) {
        byte[] bytes = json.getBytes();
        if (largeBuffer == null || largeBuffer.length < bytes.length) {
            largeBuffer = new byte[Math.max(bytes.length, 8192)];
        }
        System.arraycopy(bytes, 0, largeBuffer, 0, bytes.length);
        return bytes.length;
    }

    private static void tryLoadExternalCast(String url, byte[] data) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        try {
            boolean loaded = wasmPlayer.getPlayer().getCastLibManager()
                    .setExternalCastDataByUrl(url, data);
            if (loaded) {
                wasmPlayer.getPlayer().getBitmapCache().clear();
                SpriteDataExporter exporter = wasmPlayer.getSpriteExporter();
                if (exporter != null) exporter.clearBitmapCache();
            }
        } catch (Exception e) {
            // Silent — matches Swing's behavior
        }
    }
}
