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

    // Debug log: accumulates messages; read via getDebugLog() export
    static final StringBuilder debugLog = new StringBuilder(1024);

    /** Append a timestamped debug message (accessible from player-wasm package). */
    static void log(String msg) {
        debugLog.append(msg).append('\n');
    }

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
     * Force a full garbage collection cycle.
     * Call from JS after heavy allocation phases to compact the heap
     * and reduce the chance of GC corruption during subsequent ticks.
     */
    @Export(name = "forceGC")
    public static void forceGC() {
        com.libreshockwave.vm.util.StringChunkUtils.clearCaches();
        com.libreshockwave.vm.opcode.dispatch.StringMethodDispatcher.clearCaches();
        System.gc();
    }

    /**
     * Set the per-handler instruction step limit. 0 = unlimited (the default).
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
            boolean result = wasmPlayer.tick();
            return result ? 1 : 0;
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

    /**
     * Get the number of active sprites in the current frame, without baking bitmaps.
     * @return sprite count, or 0 if not playing
     */
    @Export(name = "getSpriteCount")
    public static int getSpriteCount() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        try {
            return wasmPlayer.getPlayer().getStageRenderer()
                    .getSpritesForFrame(wasmPlayer.getPlayer().getCurrentFrame()).size();
        } catch (Throwable e) {
            captureError("getSpriteCount", e);
            return 0;
        }
    }

    @Export(name = "getStageWidth")
    public static int getStageWidth() {
        return wasmPlayer != null ? wasmPlayer.getStageWidth() : 640;
    }

    @Export(name = "getStageHeight")
    public static int getStageHeight() {
        return wasmPlayer != null ? wasmPlayer.getStageHeight() : 480;
    }

    // === Full-frame rendering ===

    /** RGBA buffer holding the last rendered frame. */
    private static byte[] renderBuffer;

    /**
     * Render the current frame into an RGBA buffer via SoftwareRenderer.
     * JS reads the pixel data from getRenderBufferAddress().
     * @return buffer byte length (width * height * 4), or 0 on failure
     */
    @Export(name = "render")
    public static int render() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        try {
            SoftwareRenderer renderer = wasmPlayer.getSoftwareRenderer();
            if (renderer == null) return 0;

            var snapshot = wasmPlayer.getPlayer().getFrameSnapshot();
            renderBuffer = renderer.render(snapshot, wasmPlayer.getCastRevision());
            return renderBuffer.length;
        } catch (Throwable e) {
            captureError("render", e);
            return 0;
        }
    }

    /**
     * Get the memory address of the last rendered RGBA buffer.
     * @return address, or 0 if no frame has been rendered
     */
    @Export(name = "getRenderBufferAddress")
    public static int getRenderBufferAddress() {
        return renderBuffer != null ? Address.ofData(renderBuffer).toInt() : 0;
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

    @Export(name = "getPendingFetchTaskId")
    public static int getPendingFetchTaskId(int index) {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        QueuedNetProvider.PendingRequest req = net.getRequest(index);
        return req != null ? req.taskId : 0;
    }

    @Export(name = "getPendingFetchUrl")
    public static int getPendingFetchUrl(int index) {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        QueuedNetProvider.PendingRequest req = net.getRequest(index);
        return req != null ? writeToStringBuffer(req.url) : 0;
    }

    /** @return 0=GET, 1=POST */
    @Export(name = "getPendingFetchMethod")
    public static int getPendingFetchMethod(int index) {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        QueuedNetProvider.PendingRequest req = net.getRequest(index);
        return req != null && "POST".equals(req.method) ? 1 : 0;
    }

    @Export(name = "getPendingFetchPostData")
    public static int getPendingFetchPostData(int index) {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        QueuedNetProvider.PendingRequest req = net.getRequest(index);
        return req != null ? writeToStringBuffer(req.postData) : 0;
    }

    @Export(name = "getPendingFetchFallbackCount")
    public static int getPendingFetchFallbackCount(int index) {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        QueuedNetProvider.PendingRequest req = net.getRequest(index);
        if (req == null || req.fallbacks == null || req.fallbacks.length <= 1) return 0;
        return req.fallbacks.length - 1; // first entry is the primary URL
    }

    @Export(name = "getPendingFetchFallbackUrl")
    public static int getPendingFetchFallbackUrl(int index, int fallbackIndex) {
        QueuedNetProvider net = netProvider();
        if (net == null) return 0;
        QueuedNetProvider.PendingRequest req = net.getRequest(index);
        if (req == null || req.fallbacks == null) return 0;
        int actualIndex = fallbackIndex + 1; // skip primary URL at [0]
        if (actualIndex >= req.fallbacks.length) return 0;
        return writeToStringBuffer(req.fallbacks[actualIndex]);
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

    /**
     * Read accumulated debug log messages.
     * Clears the log after reading.
     * @return byte length written to stringBuffer, or 0 if log is empty
     */
    @Export(name = "getDebugLog")
    public static int getDebugLog() {
        if (debugLog.length() == 0) return 0;
        byte[] bytes = debugLog.toString().getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        debugLog.setLength(0);
        return len;
    }

    // === Diagnostic exports ===

    /**
     * Get the number of active timeouts (for test diagnostics).
     */
    @Export(name = "getTimeoutCount")
    public static int getTimeoutCount() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return -1;
        return wasmPlayer.getPlayer().getTimeoutManager().getTimeoutCount();
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

    private static QueuedNetProvider netProvider() {
        return wasmPlayer != null ? wasmPlayer.getNetProvider() : null;
    }

    private static int writeToStringBuffer(String s) {
        if (s == null || s.isEmpty()) return 0;
        byte[] bytes = s.getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        return len;
    }

    private static void tryLoadExternalCast(String url, byte[] data) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        try {
            // Cache file data first — mirrors Swing's NetManager completion callback.
            // The CastLoad Manager sets castLib.fileName *after* the fetch completes,
            // triggering CastLibManager.tryLoadCastFromCache which reads from this cache.
            // Without this call, dynamically-assigned casts (like hh_entry_au.cct) are
            // never loaded because no matching CastLib exists at delivery time.
            wasmPlayer.getPlayer().getCastLibManager().cacheFileData(url, data);

            boolean loaded = wasmPlayer.getPlayer().getCastLibManager()
                    .setExternalCastDataByUrl(url, data);
            if (loaded) {
                wasmPlayer.getPlayer().getBitmapCache().clear();
                wasmPlayer.bumpCastRevision();
            }
        } catch (Exception e) {
            // Silent — external cast load failure is non-fatal
        }
    }
}
