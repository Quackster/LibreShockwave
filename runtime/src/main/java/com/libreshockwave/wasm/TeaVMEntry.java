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

    // Debug output buffer - stores messages for JS to poll
    private static final int DEBUG_BUFFER_SIZE = 100;
    private static String[] debugBuffer = new String[DEBUG_BUFFER_SIZE];
    private static int debugBufferHead = 0;
    private static int debugBufferTail = 0;
    private static int debugBufferCount = 0;

    private static void addDebugMessage(String message) {
        debugBuffer[debugBufferHead] = message;
        debugBufferHead = (debugBufferHead + 1) % DEBUG_BUFFER_SIZE;
        if (debugBufferCount < DEBUG_BUFFER_SIZE) {
            debugBufferCount++;
        } else {
            // Buffer full, drop oldest
            debugBufferTail = (debugBufferTail + 1) % DEBUG_BUFFER_SIZE;
        }
    }

    /**
     * Enable or disable debug mode.
     * When enabled, verbose logging shows frame/sprite loading details and script execution.
     * @param enabled 1 to enable, 0 to disable
     */
    @Export(name = "setDebugMode")
    public static void setDebugMode(int enabled) {
        if (player != null) {
            player.setDebugMode(enabled != 0);
            // Set up debug output callback
            if (enabled != 0) {
                player.setDebugOutputCallback(TeaVMEntry::addDebugMessage);
            } else {
                player.setDebugOutputCallback(null);
            }
        }
    }

    /**
     * Check if debug mode is enabled.
     * @return 1 if enabled, 0 if disabled
     */
    @Export(name = "isDebugMode")
    public static int isDebugMode() {
        return (player != null && player.isDebugMode()) ? 1 : 0;
    }

    /**
     * Get the number of pending debug messages.
     */
    @Export(name = "getDebugMessageCount")
    public static int getDebugMessageCount() {
        return debugBufferCount;
    }

    /**
     * Get the length of the next debug message.
     * @return length of next message, or 0 if no messages
     */
    @Export(name = "getNextDebugMessageLength")
    public static int getNextDebugMessageLength() {
        if (debugBufferCount == 0) return 0;
        String msg = debugBuffer[debugBufferTail];
        return msg != null ? msg.length() : 0;
    }

    /**
     * Get a character from the next debug message.
     */
    @Export(name = "getNextDebugMessageChar")
    public static int getNextDebugMessageChar(int index) {
        if (debugBufferCount == 0) return 0;
        String msg = debugBuffer[debugBufferTail];
        if (msg == null || index < 0 || index >= msg.length()) return 0;
        return msg.charAt(index);
    }

    /**
     * Pop the next debug message (remove it from the buffer).
     */
    @Export(name = "popDebugMessage")
    public static void popDebugMessage() {
        if (debugBufferCount > 0) {
            debugBuffer[debugBufferTail] = null;
            debugBufferTail = (debugBufferTail + 1) % DEBUG_BUFFER_SIZE;
            debugBufferCount--;
        }
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

        // Get sprites map
        var spriteMap = player.getSprites();
        if (spriteMap == null || spriteMap.isEmpty()) {
            spriteData = new int[0];
            return 0;
        }

        // Copy to array (avoid ArrayList for TeaVM compatibility)
        int size = spriteMap.size();
        WasmPlayer.SpriteState[] sprites = new WasmPlayer.SpriteState[size];
        int idx = 0;
        for (WasmPlayer.SpriteState s : spriteMap.values()) {
            sprites[idx++] = s;
        }

        // Sort by channel using simple bubble sort (avoid Arrays.sort for TeaVM)
        for (int i = 0; i < size - 1; i++) {
            for (int j = 0; j < size - i - 1; j++) {
                if (sprites[j].channel > sprites[j + 1].channel) {
                    WasmPlayer.SpriteState temp = sprites[j];
                    sprites[j] = sprites[j + 1];
                    sprites[j + 1] = temp;
                }
            }
        }

        // 10 ints per sprite: channel, locH, locV, width, height, castLib, castMember, ink, blend, visible
        spriteData = new int[size * 10];

        int i = 0;
        for (WasmPlayer.SpriteState s : sprites) {
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

        return size;
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
    // External cast loading functions
    // =====================================================

    // External cast data buffer
    private static byte[] externalCastData;

    /**
     * Get the number of external casts that need to be loaded.
     */
    @Export(name = "getPendingExternalCastCount")
    public static int getPendingExternalCastCount() {
        if (player == null) return 0;
        int count = player.getPendingExternalCastCount();
        log("getPendingExternalCastCount: " + count);
        return count;
    }

    /**
     * Get the cast number of a pending external cast by index.
     */
    @Export(name = "getPendingExternalCastNumber")
    public static int getPendingExternalCastNumber(int index) {
        if (player == null) return 0;
        String[] info = player.getPendingExternalCastInfo(index);
        if (info == null) return 0;
        return Integer.parseInt(info[0]);
    }

    /**
     * Get the filename length of a pending external cast by index.
     */
    @Export(name = "getPendingExternalCastFileNameLength")
    public static int getPendingExternalCastFileNameLength(int index) {
        if (player == null) return 0;
        String[] info = player.getPendingExternalCastInfo(index);
        if (info == null) return 0;
        return info[1].length();
    }

    /**
     * Get a character from the filename of a pending external cast.
     */
    @Export(name = "getPendingExternalCastFileNameChar")
    public static int getPendingExternalCastFileNameChar(int index, int charIndex) {
        if (player == null) return 0;
        String[] info = player.getPendingExternalCastInfo(index);
        if (info == null || charIndex < 0 || charIndex >= info[1].length()) return 0;
        return info[1].charAt(charIndex);
    }

    /**
     * Allocate buffer for external cast data.
     */
    @Export(name = "allocateExternalCastBuffer")
    public static void allocateExternalCastBuffer(int size) {
        externalCastData = new byte[size];
        log("allocateExternalCastBuffer: " + size + " bytes");
    }

    /**
     * Set a byte in the external cast data buffer.
     */
    @Export(name = "setExternalCastDataByte")
    public static void setExternalCastDataByte(int index, int value) {
        if (externalCastData != null && index >= 0 && index < externalCastData.length) {
            externalCastData[index] = (byte) value;
        }
    }

    /**
     * Load external cast from the previously set buffer.
     * @param castNumber The 1-based cast number
     * @return 1 on success, 0 on failure
     */
    @Export(name = "loadExternalCastFromBuffer")
    public static int loadExternalCastFromBuffer(int castNumber) {
        log("loadExternalCastFromBuffer: cast #" + castNumber);
        if (player == null) {
            logError("loadExternalCastFromBuffer: player is null");
            return 0;
        }
        if (externalCastData == null || externalCastData.length == 0) {
            logError("loadExternalCastFromBuffer: no data set");
            return 0;
        }

        boolean success = player.loadExternalCastFromData(castNumber, externalCastData);
        externalCastData = null; // Free the buffer
        return success ? 1 : 0;
    }

    // =====================================================
    // Bitmap access functions
    // =====================================================

    // Bitmap pixel data buffer (set when prepareBitmap is called)
    private static int[] bitmapPixels;
    private static int bitmapWidth;
    private static int bitmapHeight;

    /**
     * Prepare bitmap data for a cast member.
     * Call getBitmapWidth/Height/Pixel after this.
     * @return 1 if bitmap found and decoded, 0 otherwise
     */
    @Export(name = "prepareBitmap")
    public static int prepareBitmap(int castLib, int memberNum) {
        log("prepareBitmap(" + castLib + ", " + memberNum + ")");
        bitmapPixels = null;
        bitmapWidth = 0;
        bitmapHeight = 0;

        if (player == null || !player.isLoaded()) {
            log("  ERROR: player not loaded");
            return 0;
        }

        int[] dims = player.getBitmapDimensions(castLib, memberNum);
        if (dims == null) {
            log("  ERROR: getBitmapDimensions returned null");
            return 0;
        }

        int[] pixels = player.getBitmapPixels(castLib, memberNum);
        if (pixels == null) {
            log("  ERROR: getBitmapPixels returned null");
            return 0;
        }

        bitmapPixels = pixels;
        bitmapWidth = dims[0];
        bitmapHeight = dims[1];

        log("  SUCCESS: " + bitmapWidth + "x" + bitmapHeight + " (" + pixels.length + " pixels)");
        return 1;
    }

    /**
     * Get the width of the prepared bitmap.
     */
    @Export(name = "getBitmapWidth")
    public static int getBitmapWidth() {
        return bitmapWidth;
    }

    /**
     * Get the height of the prepared bitmap.
     */
    @Export(name = "getBitmapHeight")
    public static int getBitmapHeight() {
        return bitmapHeight;
    }

    /**
     * Get a pixel value from the prepared bitmap (ARGB format).
     * @param index Pixel index (y * width + x)
     * @return ARGB pixel value
     */
    @Export(name = "getBitmapPixel")
    public static int getBitmapPixel(int index) {
        if (bitmapPixels != null && index >= 0 && index < bitmapPixels.length) {
            return bitmapPixels[index];
        }
        return 0;
    }

    /**
     * Get total number of pixels in prepared bitmap.
     */
    @Export(name = "getBitmapPixelCount")
    public static int getBitmapPixelCount() {
        return bitmapPixels != null ? bitmapPixels.length : 0;
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
