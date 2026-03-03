package com.libreshockwave.player.wasm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.debug.BreakpointManager;
import com.libreshockwave.player.wasm.debug.WasmDebugController;
import com.libreshockwave.player.wasm.debug.WasmDebugSerializer;
import com.libreshockwave.player.wasm.net.WasmNetManager;
import com.libreshockwave.player.wasm.render.SpriteDataExporter;
import org.teavm.interop.Address;
import org.teavm.interop.Export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point and exported API for the standard WASM player.
 * All public methods with @Export are callable from JavaScript via the WASM module exports.
 * Data is exchanged through shared byte[] buffers using raw memory addresses.
 */
public class WasmPlayerApp {

    private static WasmPlayer wasmPlayer;
    private static boolean debugRequested = false;
    private static String lastError = null;

    // Shared buffers for JS <-> WASM data transfer
    static byte[] movieBuffer;
    public static byte[] stringBuffer = new byte[4096];
    static byte[] netBuffer;
    static byte[] largeBuffer;

    public static void main(String[] args) {
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

        if (debugRequested) {
            wasmPlayer.enableDebug();
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
        try {
            lastError = null;
            return wasmPlayer.tick() ? 1 : 0;
        } catch (Throwable e) {
            captureError("tick", e);
            return 0;
        }
    }

    /**
     * Render the current frame into the RGBA pixel buffer.
     * @return raw memory address of the RGBA buffer, or 0 on failure
     */
    @Export(name = "render")
    public static int render() {
        if (wasmPlayer == null) return 0;
        try {
            wasmPlayer.render();
            byte[] buf = wasmPlayer.getFrameBuffer();
            return buf != null ? Address.ofData(buf).toInt() : 0;
        } catch (Throwable e) {
            captureError("render", e);
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

    @Export(name = "preloadAllCasts")
    public static int preloadAllCasts() {
        return wasmPlayer != null ? wasmPlayer.preloadAllCasts() : 0;
    }

    // === Large buffer for JSON exchange ===

    @Export(name = "allocateLargeBuffer")
    public static void allocateLargeBuffer(int size) {
        largeBuffer = new byte[size];
    }

    @Export(name = "getLargeBufferAddress")
    public static int getLargeBufferAddress() {
        return largeBuffer != null ? Address.ofData(largeBuffer).toInt() : 0;
    }

    /**
     * Write raw bytes to the large buffer, allocating if needed.
     * @return the byte length written
     */
    private static int writeBytesToLargeBuffer(byte[] bytes) {
        if (largeBuffer == null || largeBuffer.length < bytes.length) {
            largeBuffer = new byte[Math.max(bytes.length, 8192)];
        }
        System.arraycopy(bytes, 0, largeBuffer, 0, bytes.length);
        return bytes.length;
    }

    private static int writeJsonToLargeBuffer(String json) {
        return writeBytesToLargeBuffer(json.getBytes());
    }

    // === Sprite data export (for Canvas 2D rendering) ===

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

    // === Debug controller ===

    @Export(name = "enableDebug")
    public static void enableDebug() {
        debugRequested = true;
        if (wasmPlayer != null) wasmPlayer.enableDebug();
    }

    @Export(name = "getDebugState")
    public static int getDebugState() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null) return 0;
        return switch (ctrl.getState()) {
            case RUNNING -> 0;
            case PAUSED -> 1;
            case STEPPING -> 2;
        };
    }

    @Export(name = "getDebugSnapshot")
    public static int getDebugSnapshot() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null || ctrl.getCurrentSnapshot() == null) return 0;
        String json = WasmDebugSerializer.serializeSnapshot(ctrl.getCurrentSnapshot());
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "getDebugPausedJson")
    public static int getDebugPausedJson() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null || ctrl.notifyPausedBytes == null) return 0;
        return writeBytesToLargeBuffer(ctrl.notifyPausedBytes);
    }

    @Export(name = "getScriptList")
    public static int getScriptList() {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return 0;
        List<ScriptChunk> scripts = wasmPlayer.getAllScripts();
        String json = WasmDebugSerializer.serializeScriptList(scripts, wasmPlayer.getFile());
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "getHandlerBytecode")
    public static int getHandlerBytecode(int scriptId, int handlerIndex) {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return 0;
        ScriptChunk script = findScript(scriptId);
        if (!isValidHandler(script, handlerIndex)) return 0;

        ScriptChunk.Handler handler = script.handlers().get(handlerIndex);
        WasmDebugController dbg = debugCtrl();
        BreakpointManager bpMgr = dbg != null ? dbg.getBreakpointManager() : null;
        String json = WasmDebugSerializer.serializeHandlerBytecode(script, handler, bpMgr);
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "getHandlerDetails")
    public static int getHandlerDetails(int scriptId, int handlerIndex) {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return 0;
        ScriptChunk script = findScript(scriptId);
        if (!isValidHandler(script, handlerIndex)) return 0;

        ScriptChunk.Handler handler = script.handlers().get(handlerIndex);
        String json = WasmDebugSerializer.serializeHandlerDetails(script, handler);
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "toggleBreakpoint")
    public static int toggleBreakpoint(int scriptId, int handlerIndex, int offset) {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null) return 0;
        String handlerName = resolveHandlerName(scriptId, handlerIndex);
        return ctrl.toggleBreakpoint(scriptId, handlerName, offset) ? 1 : 0;
    }

    private static String resolveHandlerName(int scriptId, int handlerIndex) {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return "";
        ScriptChunk script = findScript(scriptId);
        if (!isValidHandler(script, handlerIndex)) return "";
        return script.getHandlerName(script.handlers().get(handlerIndex));
    }

    @Export(name = "clearBreakpoints")
    public static void clearBreakpoints() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.clearAllBreakpoints();
    }

    @Export(name = "serializeBreakpoints")
    public static int serializeBreakpoints() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null) return 0;
        String json = ctrl.serializeBreakpoints();
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "deserializeBreakpoints")
    public static void deserializeBreakpoints(int dataLen) {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null || dataLen <= 0) return;
        String data = new String(stringBuffer, 0, dataLen);
        ctrl.deserializeBreakpoints(data);
    }

    @Export(name = "debugStepInto")
    public static void debugStepInto() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.stepInto();
    }

    @Export(name = "debugStepOver")
    public static void debugStepOver() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.stepOver();
    }

    @Export(name = "debugStepOut")
    public static void debugStepOut() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.stepOut();
    }

    @Export(name = "debugContinue")
    public static void debugContinue() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.continueExecution();
    }

    @Export(name = "debugPause")
    public static void debugPause() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.pause();
    }

    @Export(name = "addWatch")
    public static void addWatch(int expressionLen) {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null || expressionLen <= 0) return;
        String expression = new String(stringBuffer, 0, expressionLen);
        ctrl.addWatchExpression(expression);
    }

    @Export(name = "removeWatch")
    public static void removeWatch(int idLen) {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null || idLen <= 0) return;
        String id = new String(stringBuffer, 0, idLen);
        ctrl.removeWatchExpression(id);
    }

    @Export(name = "clearWatches")
    public static void clearWatches() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl != null) ctrl.clearWatchExpressions();
    }

    @Export(name = "getWatches")
    public static int getWatches() {
        WasmDebugController ctrl = debugCtrl();
        if (ctrl == null) return 0;
        String json = WasmDebugSerializer.serializeWatchesStandalone(ctrl.evaluateWatchExpressions());
        return writeJsonToLargeBuffer(json);
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
        if (mgr == null || netBuffer == null) return;

        byte[] data = new byte[dataSize];
        System.arraycopy(netBuffer, 0, data, 0, dataSize);
        String url = mgr.getTaskUrl(taskId);
        mgr.onFetchComplete(taskId, data);

        if (url != null) {
            tryLoadExternalCast(url, data);
        }

        // Every completed fetch may unblock deferred play
        if (wasmPlayer != null) {
            wasmPlayer.onCastFetchDone();
        }
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
            // Error captured but not logged — matches Swing's silent cast loading
        }
    }

    @Export(name = "onFetchError")
    public static void onFetchError(int taskId, int status) {
        WasmNetManager mgr = WasmNetManager.getInstance();
        boolean taskDone = true;
        if (mgr != null) {
            taskDone = mgr.onFetchError(taskId, status);
        }
        // Only count as done if all fallbacks exhausted (no retry started)
        if (taskDone && wasmPlayer != null) {
            wasmPlayer.onCastFetchDone();
        }
    }

    // === External parameters ===

    /**
     * Set an external parameter (Shockwave PARAM tag).
     * Key is written to stringBuffer[0..keyLen), value follows at stringBuffer[keyLen..keyLen+valueLen).
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

    /**
     * Clear all external parameters.
     */
    @Export(name = "clearExternalParams")
    public static void clearExternalParams() {
        if (wasmPlayer == null || wasmPlayer.getPlayer() == null) return;
        wasmPlayer.getPlayer().setExternalParams(null);
    }

    // === Error tracking ===

    /**
     * Get the last error message. Returns byte length written to stringBuffer, or 0 if no error.
     */
    @Export(name = "getLastError")
    public static int getLastError() {
        if (lastError == null) return 0;
        byte[] bytes = lastError.getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
        return len;
    }

    private static void captureError(String context, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(context).append("] ").append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        // Capture cause chain
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

    // === Internal helpers ===

    private static WasmDebugController debugCtrl() {
        return wasmPlayer != null ? wasmPlayer.getDebugController() : null;
    }

    private static SpriteDataExporter spriteExporter() {
        return wasmPlayer != null ? wasmPlayer.getSpriteExporter() : null;
    }

    /**
     * Write a string to the shared string buffer (for URL passing to JS).
     */
    public static void writeStringToBuffer(String s) {
        byte[] bytes = s.getBytes();
        int len = Math.min(bytes.length, stringBuffer.length);
        System.arraycopy(bytes, 0, stringBuffer, 0, len);
    }

    private static boolean isValidHandler(ScriptChunk script, int handlerIndex) {
        return script != null && script.handlers() != null &&
               handlerIndex >= 0 && handlerIndex < script.handlers().size();
    }

    private static ScriptChunk findScript(int scriptId) {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return null;
        for (ScriptChunk script : wasmPlayer.getFile().getScripts()) {
            if (script.id().value() == scriptId) return script;
        }
        return null;
    }
}
