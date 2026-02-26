package com.libreshockwave.player.wasm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.debug.BreakpointManager;
import com.libreshockwave.player.wasm.debug.WasmDebugController;
import com.libreshockwave.player.wasm.debug.WasmDebugSerializer;
import com.libreshockwave.player.wasm.net.WasmNetManager;
import com.libreshockwave.player.wasm.render.SpriteDataExporter;
import org.teavm.interop.Address;
import org.teavm.interop.Export;

import java.util.List;

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
    static byte[] largeBuffer;

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
     * Write a JSON string to the large buffer, allocating if needed.
     * @return the byte length written
     */
    private static int writeJsonToLargeBuffer(String json) {
        byte[] bytes = json.getBytes();
        if (largeBuffer == null || largeBuffer.length < bytes.length) {
            largeBuffer = new byte[Math.max(bytes.length, 8192)];
        }
        System.arraycopy(bytes, 0, largeBuffer, 0, bytes.length);
        return bytes.length;
    }

    // === Sprite data export (for Canvas 2D rendering) ===

    /**
     * Export current frame sprite data as JSON.
     * @return JSON byte length in largeBuffer
     */
    @Export(name = "getFrameDataJson")
    public static int getFrameDataJson() {
        if (wasmPlayer == null || wasmPlayer.getSpriteExporter() == null) return 0;
        String json = wasmPlayer.getSpriteExporter().exportFrameData();
        return writeJsonToLargeBuffer(json);
    }

    /**
     * Get bitmap RGBA data for a cast member.
     * @return memory address of RGBA data, or 0 if not found
     */
    @Export(name = "getBitmapData")
    public static int getBitmapData(int memberId) {
        if (wasmPlayer == null || wasmPlayer.getSpriteExporter() == null) return 0;
        byte[] rgba = wasmPlayer.getSpriteExporter().getBitmapRGBA(memberId);
        return rgba != null ? Address.ofData(rgba).toInt() : 0;
    }

    @Export(name = "getBitmapWidth")
    public static int getBitmapWidth(int memberId) {
        if (wasmPlayer == null || wasmPlayer.getSpriteExporter() == null) return 0;
        return wasmPlayer.getSpriteExporter().getBitmapWidth(memberId);
    }

    @Export(name = "getBitmapHeight")
    public static int getBitmapHeight(int memberId) {
        if (wasmPlayer == null || wasmPlayer.getSpriteExporter() == null) return 0;
        return wasmPlayer.getSpriteExporter().getBitmapHeight(memberId);
    }

    // === Debug controller ===

    @Export(name = "enableDebug")
    public static void enableDebug() {
        if (wasmPlayer != null) wasmPlayer.enableDebug();
    }

    @Export(name = "getDebugState")
    public static int getDebugState() {
        if (wasmPlayer == null || wasmPlayer.getDebugController() == null) return 0;
        return switch (wasmPlayer.getDebugController().getState()) {
            case RUNNING -> 0;
            case PAUSED -> 1;
            case STEPPING -> 2;
        };
    }

    @Export(name = "getDebugSnapshot")
    public static int getDebugSnapshot() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null || ctrl.getCurrentSnapshot() == null) return 0;
        String json = WasmDebugSerializer.serializeSnapshot(ctrl.getCurrentSnapshot());
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "getDebugPausedJson")
    public static int getDebugPausedJson() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null || ctrl.notifyPausedBytes == null) return 0;
        byte[] bytes = ctrl.notifyPausedBytes;
        if (largeBuffer == null || largeBuffer.length < bytes.length) {
            largeBuffer = new byte[Math.max(bytes.length, 8192)];
        }
        System.arraycopy(bytes, 0, largeBuffer, 0, bytes.length);
        return bytes.length;
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
        if (script == null || script.handlers() == null ||
            handlerIndex < 0 || handlerIndex >= script.handlers().size()) return 0;

        ScriptChunk.Handler handler = script.handlers().get(handlerIndex);
        BreakpointManager bpMgr = wasmPlayer.getDebugController() != null
            ? wasmPlayer.getDebugController().getBreakpointManager() : null;
        String json = WasmDebugSerializer.serializeHandlerBytecode(script, handler, bpMgr);
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "getHandlerDetails")
    public static int getHandlerDetails(int scriptId, int handlerIndex) {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return 0;
        ScriptChunk script = findScript(scriptId);
        if (script == null || script.handlers() == null ||
            handlerIndex < 0 || handlerIndex >= script.handlers().size()) return 0;

        ScriptChunk.Handler handler = script.handlers().get(handlerIndex);
        String json = WasmDebugSerializer.serializeHandlerDetails(script, handler);
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "toggleBreakpoint")
    public static int toggleBreakpoint(int scriptId, int offset) {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null) return 0;
        return ctrl.toggleBreakpoint(scriptId, offset) ? 1 : 0;
    }

    @Export(name = "clearBreakpoints")
    public static void clearBreakpoints() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.clearAllBreakpoints();
    }

    @Export(name = "serializeBreakpoints")
    public static int serializeBreakpoints() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null) return 0;
        String json = ctrl.serializeBreakpoints();
        return writeJsonToLargeBuffer(json);
    }

    @Export(name = "deserializeBreakpoints")
    public static void deserializeBreakpoints(int dataLen) {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null || dataLen <= 0) return;
        String data = new String(stringBuffer, 0, dataLen);
        ctrl.deserializeBreakpoints(data);
    }

    @Export(name = "debugStepInto")
    public static void debugStepInto() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.stepInto();
    }

    @Export(name = "debugStepOver")
    public static void debugStepOver() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.stepOver();
    }

    @Export(name = "debugStepOut")
    public static void debugStepOut() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.stepOut();
    }

    @Export(name = "debugContinue")
    public static void debugContinue() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.continueExecution();
    }

    @Export(name = "debugPause")
    public static void debugPause() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.pause();
    }

    @Export(name = "addWatch")
    public static void addWatch(int expressionLen) {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null || expressionLen <= 0) return;
        String expression = new String(stringBuffer, 0, expressionLen);
        ctrl.addWatchExpression(expression);
    }

    @Export(name = "removeWatch")
    public static void removeWatch(int idLen) {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl == null || idLen <= 0) return;
        String id = new String(stringBuffer, 0, idLen);
        ctrl.removeWatchExpression(id);
    }

    @Export(name = "clearWatches")
    public static void clearWatches() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
        if (ctrl != null) ctrl.clearWatchExpressions();
    }

    @Export(name = "getWatches")
    public static int getWatches() {
        WasmDebugController ctrl = wasmPlayer != null ? wasmPlayer.getDebugController() : null;
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

    private static ScriptChunk findScript(int scriptId) {
        if (wasmPlayer == null || wasmPlayer.getFile() == null) return null;
        for (ScriptChunk script : wasmPlayer.getFile().getScripts()) {
            if (script.id() == scriptId) return script;
        }
        return null;
    }
}
