package com.libreshockwave.wasm;

import org.teavm.interop.Export;
import org.teavm.interop.Import;

/**
 * TeaVM WebAssembly entry point for LibreShockwave.
 *
 * These functions are exported to JavaScript via TeaVM.
 * Unlike GraalVM, TeaVM handles memory management automatically.
 */
public class TeaVMEntry {

    // Singleton player instance (uses WasmPlayer - no network deps)
    private static WasmPlayer player;

    // Movie data buffer (set from JS)
    private static byte[] movieData;

    // Sprite data for JS access
    private static int[] spriteData;

    /**
     * Main entry point (required by TeaVM).
     */
    public static void main(String[] args) {
        // Don't call init() here - let JS call it explicitly
        System.out.println("[WASM] LibreShockwave TeaVM Runtime loaded");
    }

    // =====================================================
    // Exported functions - callable from JavaScript
    // =====================================================

    /**
     * Initialize the runtime.
     */
    @Export(name = "lsw_init")
    public static void lswInit() {
        player = new WasmPlayer();
        log("LibreShockwave initialized");
    }

    /**
     * Allocate buffer for movie data.
     * Call this before setMovieDataByte.
     */
    @Export(name = "allocateMovieBuffer")
    public static void allocateMovieBuffer(int size) {
        movieData = new byte[size];
    }

    /**
     * Set a byte in the movie data buffer.
     * Used to transfer data from JS to Java.
     */
    @Export(name = "setMovieDataByte")
    public static void setMovieDataByte(int index, int value) {
        if (movieData != null && index >= 0 && index < movieData.length) {
            movieData[index] = (byte) value;
        }
    }

    /**
     * Load movie from the previously set buffer.
     * @return 1 on success, 0 on failure
     */
    @Export(name = "loadMovieFromBuffer")
    public static int loadMovieFromBuffer() {
        try {
            if (player == null) {
                player = new WasmPlayer();
            }
            if (movieData == null || movieData.length == 0) {
                logError("No movie data set");
                return 0;
            }

            player.loadMovie(movieData);

            log("Movie loaded: " + player.getLastFrame() + " frames");
            return 1;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getName();
            }
            logError("Load error: " + msg);
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Start playback.
     */
    @Export(name = "play")
    public static void play() {
        if (player != null) {
            player.play();
        }
    }

    /**
     * Stop playback.
     */
    @Export(name = "stop")
    public static void stop() {
        if (player != null) {
            player.stop();
        }
    }

    /**
     * Pause playback.
     */
    @Export(name = "pause")
    public static void pause() {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * Go to next frame.
     */
    @Export(name = "nextFrame")
    public static void nextFrame() {
        if (player != null) {
            player.nextFrame();
        }
    }

    /**
     * Go to previous frame.
     */
    @Export(name = "prevFrame")
    public static void prevFrame() {
        if (player != null) {
            player.prevFrame();
        }
    }

    /**
     * Go to a specific frame.
     */
    @Export(name = "goToFrame")
    public static void goToFrame(int frame) {
        if (player != null) {
            player.goToFrame(frame);
        }
    }

    /**
     * Execute one tick.
     */
    @Export(name = "tick")
    public static void tick() {
        if (player != null) {
            player.tick();
        }
    }

    /**
     * Get current frame number.
     */
    @Export(name = "getCurrentFrame")
    public static int getCurrentFrame() {
        return player != null ? player.getCurrentFrame() : 0;
    }

    /**
     * Get last frame number.
     */
    @Export(name = "getLastFrame")
    public static int getLastFrame() {
        return player != null ? player.getLastFrame() : 0;
    }

    /**
     * Get tempo (frames per second).
     */
    @Export(name = "getTempo")
    public static int getTempo() {
        return player != null ? player.getTempo() : 15;
    }

    /**
     * Get stage width.
     */
    @Export(name = "getStageWidth")
    public static int getStageWidth() {
        return player != null ? player.getStageWidth() : 640;
    }

    /**
     * Get stage height.
     */
    @Export(name = "getStageHeight")
    public static int getStageHeight() {
        return player != null ? player.getStageHeight() : 480;
    }

    /**
     * Check if playing.
     */
    @Export(name = "isPlaying")
    public static int isPlaying() {
        return (player != null && player.getState() == WasmPlayer.PlayState.PLAYING) ? 1 : 0;
    }

    /**
     * Check if paused.
     */
    @Export(name = "isPaused")
    public static int isPaused() {
        return (player != null && player.getState() == WasmPlayer.PlayState.PAUSED) ? 1 : 0;
    }

    /**
     * Get number of sprites in current frame.
     */
    @Export(name = "getSpriteCount")
    public static int getSpriteCount() {
        if (player == null || !player.isLoaded()) {
            return 0;
        }
        var sprites = player.getSprites();
        return sprites != null ? sprites.size() : 0;
    }

    /**
     * Prepare sprite data array for reading.
     * Must be called before getSpriteDataValue.
     * @return number of sprites prepared
     */
    @Export(name = "prepareSpriteData")
    public static int prepareSpriteData() {
        if (player == null || !player.isLoaded()) {
            spriteData = new int[0];
            return 0;
        }

        // Make a defensive copy to avoid ConcurrentModificationException
        var spriteMap = player.getSprites();
        if (spriteMap == null || spriteMap.isEmpty()) {
            spriteData = new int[0];
            return 0;
        }

        // Copy values to ArrayList first to avoid concurrent modification
        var spriteList = new java.util.ArrayList<>(spriteMap.values());
        spriteList.sort((a, b) -> Integer.compare(a.channel, b.channel));

        // 10 ints per sprite: channel, locH, locV, width, height, castLib, castMember, ink, blend, visible
        spriteData = new int[spriteList.size() * 10];

        int i = 0;
        for (WasmPlayer.SpriteState s : spriteList) {
            spriteData[i++] = s.channel;
            spriteData[i++] = s.locH;
            spriteData[i++] = s.locV;
            spriteData[i++] = s.width;
            spriteData[i++] = s.height;
            spriteData[i++] = s.castLib;
            spriteData[i++] = s.castMember;
            spriteData[i++] = s.ink;
            spriteData[i++] = s.blend;
            spriteData[i++] = s.visible ? 1 : 0;
        }

        return spriteList.size();
    }

    /**
     * Get a value from the sprite data array.
     * @param index Index in the sprite data array
     * @return Value at index, or 0 if out of bounds
     */
    @Export(name = "getSpriteDataValue")
    public static int getSpriteDataValue(int index) {
        if (spriteData != null && index >= 0 && index < spriteData.length) {
            return spriteData[index];
        }
        return 0;
    }

    // =====================================================
    // Imported functions - callable from Java to JavaScript
    // =====================================================

    @Import(name = "consoleLog", module = "env")
    private static native void jsConsoleLog(String message);

    @Import(name = "consoleError", module = "env")
    private static native void jsConsoleError(String message);

    private static void log(String message) {
        try {
            jsConsoleLog("[WASM] " + message);
        } catch (Exception e) {
            // Fallback if import not available
            System.out.println("[WASM] " + message);
        }
    }

    private static void logError(String message) {
        try {
            jsConsoleError("[WASM] " + message);
        } catch (Exception e) {
            // Fallback if import not available
            System.err.println("[WASM] " + message);
        }
    }
}
