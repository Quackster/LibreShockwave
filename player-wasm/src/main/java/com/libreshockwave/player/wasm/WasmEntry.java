package com.libreshockwave.player.wasm;

import org.teavm.interop.Address;
import org.teavm.interop.Export;

import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.DebugConfig;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // Cast data tracking for JS-side storage
    private static final List<String> pendingCastDataRequests = new ArrayList<>();
    private static final List<String> availableCastUrls = new ArrayList<>();

    // Java-side cache of raw cast data bytes (keyed by baseName, e.g. "hh_interface")
    // Used for synchronous re-delivery when Lingo sets castLib.fileName
    private static final Map<String, byte[]> castDataCache = new HashMap<>();
    private static final Set<String> failedCasts = new HashSet<>();

    // Debug log: accumulates messages; read via getDebugLog() export
    static final StringBuilder debugLog = new StringBuilder(1024);

    /** Append a timestamped debug message (accessible from player-wasm package). */
    static void log(String msg) {
        debugLog.append(msg).append('\n');
    }

    public static void main(String[] args) {
        // Replace System.out/err with non-synchronized PrintStream.
        // Java's PrintStream uses synchronized(this) on every println() call,
        // which triggers ClassCastException in TeaVM WASM's monitorEnterSync.
        PrintStream unsync = new PrintStream(new OutputStream() {
            @Override public void write(int b) { }
            @Override public void write(byte[] b, int off, int len) { }
        }) {
            @Override public void println(String x) { log(x); }
            @Override public void print(String x) { debugLog.append(x); }
            @Override public void println(Object x) { log(String.valueOf(x)); }
            @Override public void println() { debugLog.append('\n'); }
        };
        System.setOut(unsync);
        System.setErr(unsync);
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

        pendingCastDataRequests.clear();
        availableCastUrls.clear();

        wasmPlayer = new WasmPlayer();
        if (!wasmPlayer.loadMovie(data, basePath,
                (castLibNumber, fileName) -> {
                    // Try to load directly from Java-side cache (instant, same tick).
                    // This avoids a 1-tick delay that causes "Cast number expected" errors
                    // when objectmanager runs before cast data arrives via JS round-trip.
                    String baseName = FileUtil.getFileNameWithoutExtension(
                            FileUtil.getFileName(fileName));
                    byte[] cached = castDataCache.get(baseName);
                    if (cached != null) {
                        try {
                            if (wasmPlayer.getPlayer().getCastLibManager()
                                    .setExternalCastData(castLibNumber, cached)) {
                                wasmPlayer.getPlayer().getBitmapCache().clear();
                                wasmPlayer.bumpCastRevision();
                                log("castDataRequestCallback: loaded " + baseName + " from cache (cast#" + castLibNumber + ")");
                                return;
                            }
                        } catch (Throwable e) {
                            log("castDataRequestCallback: cache load failed for " + baseName + ": " + e);
                            failedCasts.add(baseName);
                        }
                    }
                    // Fallback: queue for JS-side delivery next tick
                    pendingCastDataRequests.add(fileName);
                })) {
            return 0;
        }

        // Wire up error handler depth tracing
        if (wasmPlayer.getPlayer() != null) {
            wasmPlayer.getPlayer().getVM().setErrorHandlerSkipCallback(msg -> log("[EH] " + msg));
        }

        int w = wasmPlayer.getStageWidth();
        int h = wasmPlayer.getStageHeight();
        return (w << 16) | h;
    }

    // === Playback ===

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
     * Enable or disable debug playback logging (handler calls, error stack traces).
     * @param enabled 1 = enabled, 0 = disabled
     */
    @Export(name = "setDebugPlaybackEnabled")
    public static void setDebugPlaybackEnabled(int enabled) {
        DebugConfig.setDebugPlaybackEnabled(enabled != 0);
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
            // Set step limit to catch infinite loops. The dump handler runs ~292K
            // instructions; 5M gives 17x headroom while catching infinite loops fast.
            if (wasmPlayer.getPlayer() != null) {
                wasmPlayer.getPlayer().getVM().setStepLimit(5_000_000);
            }
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

    /**
     * Get the cursor type for the current mouse position.
     * @return 0 = default, 1 = text (caret)
     */
    @Export(name = "getCursorType")
    public static int getCursorType() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        try {
            return wasmPlayer.getPlayer().getCursorAtMouse();
        } catch (Throwable e) {
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
            int spriteRev = wasmPlayer.getPlayer().getStageRenderer()
                    .getSpriteRegistry().getRevision();
            byte[] frameRgba = renderer.render(snapshot, wasmPlayer.getCastRevision(), spriteRev);

            // Overlay custom bitmap cursor if active
            com.libreshockwave.bitmap.Bitmap cursorBmp = wasmPlayer.getPlayer().getCursorBitmap();
            if (cursorBmp != null) {
                int stageW = renderer.getWidth();
                int stageH = renderer.getHeight();
                // Copy frame to separate buffer so we don't corrupt the cache
                if (cursorRenderBuffer == null || cursorRenderBuffer.length != frameRgba.length) {
                    cursorRenderBuffer = new byte[frameRgba.length];
                }
                System.arraycopy(frameRgba, 0, cursorRenderBuffer, 0, frameRgba.length);
                overlayCursorBitmap(cursorRenderBuffer, stageW, stageH, cursorBmp);
                renderBuffer = cursorRenderBuffer;
            } else {
                renderBuffer = frameRgba;
            }
            return renderBuffer.length;
        } catch (Throwable e) {
            captureError("render", e);
            return 0;
        }
    }

    /** Separate buffer for frames with cursor overlay (avoids corrupting SoftwareRenderer cache). */
    private static byte[] cursorRenderBuffer;

    /**
     * Overlay a cursor bitmap on the RGBA render buffer at the current mouse position.
     * Uses white (0xFFFFFF) as transparency color key for 1-bit/8-bit cursors,
     * or alpha channel for 32-bit cursors.
     */
    private static void overlayCursorBitmap(byte[] rgba, int stageW, int stageH,
                                             com.libreshockwave.bitmap.Bitmap cursor) {
        var player = wasmPlayer.getPlayer();
        var input = player.getInputState();
        int mouseX = input.getMouseH();
        int mouseY = input.getMouseV();

        // Apply registration point as hotspot offset
        int[] regPoint = player.getCursorRegPoint();
        int hotX = regPoint != null ? regPoint[0] : 0;
        int hotY = regPoint != null ? regPoint[1] : 0;

        int drawX = mouseX - hotX;
        int drawY = mouseY - hotY;

        int curW = cursor.getWidth();
        int curH = cursor.getHeight();
        int[] curPixels = cursor.getPixels();
        int bitDepth = cursor.getBitDepth();

        for (int cy = 0; cy < curH; cy++) {
            int dstY = drawY + cy;
            if (dstY < 0 || dstY >= stageH) continue;
            for (int cx = 0; cx < curW; cx++) {
                int dstX = drawX + cx;
                if (dstX < 0 || dstX >= stageW) continue;

                int srcIdx = cy * curW + cx;
                if (srcIdx >= curPixels.length) continue;

                int pixel = curPixels[srcIdx];
                int alpha = (pixel >> 24) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // White = transparent for 1-bit and 8-bit cursor bitmaps
                if (bitDepth <= 8 && r == 255 && g == 255 && b == 255) {
                    continue;
                }
                // Skip fully transparent pixels
                if (alpha == 0 && bitDepth > 8) {
                    continue;
                }

                int dstIdx = (dstY * stageW + dstX) * 4;
                if (alpha == 255 || bitDepth <= 8) {
                    // Opaque pixel
                    rgba[dstIdx]     = (byte) r;
                    rgba[dstIdx + 1] = (byte) g;
                    rgba[dstIdx + 2] = (byte) b;
                    rgba[dstIdx + 3] = (byte) 0xFF;
                } else {
                    // Alpha blend
                    int dstR = rgba[dstIdx] & 0xFF;
                    int dstG = rgba[dstIdx + 1] & 0xFF;
                    int dstB = rgba[dstIdx + 2] & 0xFF;
                    rgba[dstIdx]     = (byte) ((r * alpha + dstR * (255 - alpha)) / 255);
                    rgba[dstIdx + 1] = (byte) ((g * alpha + dstG * (255 - alpha)) / 255);
                    rgba[dstIdx + 2] = (byte) ((b * alpha + dstB * (255 - alpha)) / 255);
                    rgba[dstIdx + 3] = (byte) 0xFF;
                }
            }
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
     * Deliver a successful fetch result (non-cast data only).
     * Cast files are delivered separately via deliverCastData.
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
            net.onFetchComplete(taskId, data);
        } catch (Throwable e) {
            captureError("deliverFetchResult", e);
        }
    }

    /**
     * Mark a fetch task as done without storing data in WASM.
     * Reports the byte count for Lingo's bytesSoFar check.
     * If the URL is a cast file, adds it to availableCastUrls for later delivery.
     * URL must be written to stringBuffer before calling.
     */
    @Export(name = "deliverFetchStatus")
    public static void deliverFetchStatus(int taskId, int urlLen, int byteCount) {
        try {
            lastError = null;
            QueuedNetProvider net = netProvider();
            if (net == null) return;

            String url = urlLen > 0 ? new String(stringBuffer, 0, urlLen) : null;
            log("fetchStatus: taskId=" + taskId + " url=" + url + " bytes=" + byteCount);

            // Mark the net task as done with byte count but no stored data
            net.onFetchStatusComplete(taskId, byteCount);

            // Track cast URLs for later data delivery
            if (url != null && isCastFile(url)) {
                availableCastUrls.add(url);
            }
        } catch (Throwable e) {
            captureError("deliverFetchStatus", e);
        }
    }

    /**
     * Deliver cast file data from JS.
     * URL in stringBuffer[0..urlLen), data in netBuffer[0..dataSize).
     * Parses the cast into chunks and bumps cast revision.
     */
    @Export(name = "deliverCastData")
    public static void deliverCastData(int urlLen, int dataSize) {
        try {
            lastError = null;
            if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
            if (netBuffer == null || urlLen <= 0) return;

            String url = new String(stringBuffer, 0, urlLen);
            byte[] data = new byte[dataSize];
            System.arraycopy(netBuffer, 0, data, 0, dataSize);

            String baseName = FileUtil.getFileNameWithoutExtension(
                    FileUtil.getFileName(url));
            log("deliverCastData: url=" + url + " baseName=" + baseName + " size=" + dataSize);
            boolean loaded = wasmPlayer.getPlayer().getCastLibManager()
                    .setExternalCastDataByUrl(url, data);
            log("  loaded=" + loaded);
            // Always cache raw bytes for synchronous re-delivery when Lingo
            // sets castLib.fileName later (loaded=false just means no cast lib
            // has a matching fileName yet)
            castDataCache.put(baseName, data);
            if (loaded) {
                wasmPlayer.getPlayer().getBitmapCache().clear();
                wasmPlayer.bumpCastRevision();
            }
        } catch (Throwable e) {
            String baseName = "unknown";
            try { baseName = FileUtil.getFileNameWithoutExtension(FileUtil.getFileName(
                    new String(stringBuffer, 0, urlLen))); } catch (Exception ignored) {}
            failedCasts.add(baseName);
            captureError("deliverCastData", e);
        }
    }

    // === Cast data request polling (JS reads pending dynamic cast requests) ===

    @Export(name = "getPendingCastDataCount")
    public static int getPendingCastDataCount() {
        return pendingCastDataRequests.size();
    }

    @Export(name = "getPendingCastDataUrl")
    public static int getPendingCastDataUrl(int index) {
        if (index < 0 || index >= pendingCastDataRequests.size()) return 0;
        return writeToStringBuffer(pendingCastDataRequests.get(index));
    }

    @Export(name = "drainPendingCastDataRequests")
    public static void drainPendingCastDataRequests() {
        pendingCastDataRequests.clear();
    }

    /**
     * Re-queue a cast data request (for throttled delivery across ticks).
     * fileName must be written to stringBuffer[0..nameLen).
     */
    @Export(name = "queueCastDataRequest")
    public static void queueCastDataRequest(int nameLen) {
        if (nameLen <= 0) return;
        String fileName = new String(stringBuffer, 0, nameLen);
        pendingCastDataRequests.add(fileName);
    }

    // === Available cast polling (JS reads static casts ready for data delivery) ===

    @Export(name = "getAvailableCastCount")
    public static int getAvailableCastCount() {
        return availableCastUrls.size();
    }

    @Export(name = "getAvailableCastUrl")
    public static int getAvailableCastUrl(int index) {
        if (index < 0 || index >= availableCastUrls.size()) return 0;
        return writeToStringBuffer(availableCastUrls.get(index));
    }

    @Export(name = "drainAvailableCasts")
    public static void drainAvailableCasts() {
        availableCastUrls.clear();
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

    // === Input events ===

    /**
     * Update mouse position (stage coordinates).
     * Called by JS on mousemove.
     */
    @Export(name = "mouseMove")
    public static void mouseMove(int stageX, int stageY) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        wasmPlayer.getPlayer().onMouseMove(stageX, stageY);
    }

    /**
     * Handle mouse button press.
     * @param button 0=left, 2=right (matching JS MouseEvent.button)
     */
    @Export(name = "mouseDown")
    public static void mouseDown(int stageX, int stageY, int button) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        wasmPlayer.getPlayer().onMouseDown(stageX, stageY, button == 2);
    }

    /**
     * Handle mouse button release.
     * @param button 0=left, 2=right (matching JS MouseEvent.button)
     */
    @Export(name = "mouseUp")
    public static void mouseUp(int stageX, int stageY, int button) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        wasmPlayer.getPlayer().onMouseUp(stageX, stageY, button == 2);
    }

    /**
     * Handle key press.
     * @param browserKeyCode browser KeyboardEvent.keyCode
     * @param keyCharLen length of key character string in stringBuffer
     * @param modifiers bit flags: 1=shift, 2=ctrl, 4=alt
     */
    @Export(name = "keyDown")
    public static void keyDown(int browserKeyCode, int keyCharLen, int modifiers) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        String keyChar = keyCharLen > 0 ? new String(stringBuffer, 0, keyCharLen) : "";
        int directorCode = com.libreshockwave.player.input.DirectorKeyCodes.fromBrowserKeyCode(browserKeyCode);
        wasmPlayer.getPlayer().onKeyDown(directorCode, keyChar,
                (modifiers & 1) != 0, (modifiers & 2) != 0, (modifiers & 4) != 0);
    }

    /**
     * Handle key release.
     * @param browserKeyCode browser KeyboardEvent.keyCode
     * @param keyCharLen length of key character string in stringBuffer
     * @param modifiers bit flags: 1=shift, 2=ctrl, 4=alt
     */
    @Export(name = "keyUp")
    public static void keyUp(int browserKeyCode, int keyCharLen, int modifiers) {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        String keyChar = keyCharLen > 0 ? new String(stringBuffer, 0, keyCharLen) : "";
        int directorCode = com.libreshockwave.player.input.DirectorKeyCodes.fromBrowserKeyCode(browserKeyCode);
        wasmPlayer.getPlayer().onKeyUp(directorCode, keyChar,
                (modifiers & 1) != 0, (modifiers & 2) != 0, (modifiers & 4) != 0);
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

    /**
     * Get timeout names as comma-separated string, written to stringBuffer.
     * @return byte length written, or 0 if none
     */
    @Export(name = "getTimeoutNames")
    public static int getTimeoutNames() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        var names = wasmPlayer.getPlayer().getTimeoutManager().getTimeoutNames();
        if (names.isEmpty()) return 0;
        byte[] bytes = String.join(",", names).getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        return len;
    }

    /**
     * Get player state name (STOPPED/PLAYING/PAUSED), written to stringBuffer.
     * @return byte length written
     */
    @Export(name = "getPlayerState")
    public static int getPlayerState() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        byte[] bytes = wasmPlayer.getPlayer().getState().name().getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        return len;
    }

    /**
     * Get pending network request count (requests queued in QueuedNetProvider).
     */
    @Export(name = "getPendingNetCount")
    public static int getPendingNetCount() {
        if (wasmPlayer == null) return -1;
        QueuedNetProvider np = wasmPlayer.getNetProvider();
        return np != null ? np.getPendingRequests().size() : -1;
    }

    /**
     * Get the current Lingo call stack as a formatted string, written to stringBuffer.
     * Safe to call at any time (returns 0 when no handlers are executing).
     * @return byte length written to stringBuffer, or 0 if call stack is empty
     */
    @Export(name = "getCallStack")
    public static int getCallStack() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        String stack = wasmPlayer.getPlayer().formatLingoCallStack();
        if (stack == null || stack.isEmpty()) return 0;
        byte[] bytes = stack.getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        return len;
    }

    // === Test/debug exports ===

    /**
     * Trigger a test Lingo error to exercise the movie's alertHook error dialog.
     * Fires the VM's alertHook with a test error message.
     * @return 1 if alertHook was found and invoked, 0 otherwise
     */
    @Export(name = "triggerTestError")
    public static int triggerTestError() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return 0;
        try {
            boolean handled = wasmPlayer.getPlayer().fireTestError(
                    "Script error: Test error triggered for dialog appearance check");
            log("[triggerTestError] alertHook fired, handled=" + handled);
            return handled ? 1 : 0;
        } catch (Throwable e) {
            captureError("triggerTestError", e);
            return 0;
        }
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

    private static boolean isCastFile(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // Strip query string for extension check
        int qi = lower.indexOf('?');
        if (qi > 0) lower = lower.substring(0, qi);
        return lower.endsWith(".cct") || lower.endsWith(".cst");
    }
}
