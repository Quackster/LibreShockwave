package com.libreshockwave.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Score;
import com.libreshockwave.player.Sprite;
import com.libreshockwave.runtime.DirPlayer;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

/**
 * WebAssembly entry point for LibreShockwave.
 *
 * These functions are exported to JavaScript via GraalVM Native Image.
 * Each function takes an IsolateThread as its first parameter.
 */
public class WasmEntry {

    // Singleton player instance
    private static DirPlayer player;

    /**
     * Main entry point for native image compilation.
     * This is not used at runtime - the @CEntryPoint methods are the actual exports.
     */
    public static void main(String[] args) {
        System.out.println("LibreShockwave WASM Runtime");
        System.out.println("This binary should be loaded as a shared library.");
    }

    /**
     * Initialize the runtime.
     * Must be called before any other function.
     */
    @CEntryPoint(name = "init")
    public static void init(IsolateThread thread) {
        player = new DirPlayer();
        System.out.println("[WASM] LibreShockwave initialized");
    }

    /**
     * Load a movie from byte data.
     * @param data Pointer to movie data
     * @param length Length of data in bytes
     * @return 1 on success, 0 on failure
     */
    @CEntryPoint(name = "loadMovieFromData")
    public static int loadMovieFromData(IsolateThread thread, Pointer data, int length) {
        try {
            if (player == null) {
                player = new DirPlayer();
            }

            // Copy data from WASM memory
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = data.readByte(i);
            }

            // Load the movie
            DirectorFile file = DirectorFile.load(bytes);
            player.loadMovie(file);

            System.out.println("[WASM] Movie loaded: " + player.getLastFrame() + " frames");
            return 1;
        } catch (Exception e) {
            System.err.println("[WASM] Load error: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Start playback.
     */
    @CEntryPoint(name = "play")
    public static void play(IsolateThread thread) {
        if (player != null) {
            player.play();
        }
    }

    /**
     * Stop playback.
     */
    @CEntryPoint(name = "stop")
    public static void stop(IsolateThread thread) {
        if (player != null) {
            player.stop();
        }
    }

    /**
     * Pause playback.
     */
    @CEntryPoint(name = "pause")
    public static void pause(IsolateThread thread) {
        if (player != null) {
            player.pause();
        }
    }

    /**
     * Go to next frame.
     */
    @CEntryPoint(name = "nextFrame")
    public static void nextFrame(IsolateThread thread) {
        if (player != null) {
            player.nextFrame();
        }
    }

    /**
     * Go to previous frame.
     */
    @CEntryPoint(name = "prevFrame")
    public static void prevFrame(IsolateThread thread) {
        if (player != null) {
            player.prevFrame();
        }
    }

    /**
     * Go to a specific frame.
     * @param frame Frame number (1-based)
     */
    @CEntryPoint(name = "goToFrame")
    public static void goToFrame(IsolateThread thread, int frame) {
        if (player != null) {
            player.goToFrame(frame);
        }
    }

    /**
     * Execute one tick.
     */
    @CEntryPoint(name = "tick")
    public static void tick(IsolateThread thread) {
        if (player != null) {
            player.tick();
        }
    }

    /**
     * Get current frame number.
     */
    @CEntryPoint(name = "getCurrentFrame")
    public static int getCurrentFrame(IsolateThread thread) {
        return player != null ? player.getCurrentFrame() : 0;
    }

    /**
     * Get last frame number.
     */
    @CEntryPoint(name = "getLastFrame")
    public static int getLastFrame(IsolateThread thread) {
        return player != null ? player.getLastFrame() : 0;
    }

    /**
     * Get tempo (frames per second).
     */
    @CEntryPoint(name = "getTempo")
    public static int getTempo(IsolateThread thread) {
        return player != null ? player.getTempo() : 15;
    }

    /**
     * Get stage width.
     */
    @CEntryPoint(name = "getStageWidth")
    public static int getStageWidth(IsolateThread thread) {
        return player != null ? player.getStageWidth() : 640;
    }

    /**
     * Get stage height.
     */
    @CEntryPoint(name = "getStageHeight")
    public static int getStageHeight(IsolateThread thread) {
        return player != null ? player.getStageHeight() : 480;
    }

    /**
     * Check if playing.
     */
    @CEntryPoint(name = "isPlaying")
    public static int isPlaying(IsolateThread thread) {
        return (player != null && player.getState() == DirPlayer.PlayState.PLAYING) ? 1 : 0;
    }

    /**
     * Check if paused.
     */
    @CEntryPoint(name = "isPaused")
    public static int isPaused(IsolateThread thread) {
        return (player != null && player.getState() == DirPlayer.PlayState.PAUSED) ? 1 : 0;
    }

    /**
     * Get number of sprites in current frame.
     */
    @CEntryPoint(name = "getSpriteCount")
    public static int getSpriteCount(IsolateThread thread) {
        if (player == null || player.getScore() == null) {
            return 0;
        }

        Score score = player.getScore();
        Score.Frame frame = score.getFrame(player.getCurrentFrame());
        if (frame == null) {
            return 0;
        }

        return (int) frame.getSprites().stream().filter(s -> s.getCastMember() > 0).count();
    }

    /**
     * Get sprite data for current frame.
     * Returns a packed byte array with sprite information.
     * Format per sprite: channel(4), locH(4), locV(4), width(4), height(4),
     *                    castLib(4), castMember(4), ink(4), blend(4), visible(1)
     * Total: 37 bytes per sprite
     *
     * IMPORTANT: Caller must free the returned pointer using free().
     */
    @CEntryPoint(name = "getSpriteData")
    public static Pointer getSpriteData(IsolateThread thread, Pointer outLength) {
        if (player == null || player.getScore() == null) {
            outLength.writeInt(0, 0);
            return WordFactory.nullPointer();
        }

        Score score = player.getScore();
        Score.Frame frame = score.getFrame(player.getCurrentFrame());
        if (frame == null) {
            outLength.writeInt(0, 0);
            return WordFactory.nullPointer();
        }

        var sprites = frame.getSpritesSorted().stream()
            .filter(s -> s.getCastMember() > 0)
            .toList();

        int bytesPerSprite = 37;
        int totalBytes = sprites.size() * bytesPerSprite;

        if (totalBytes == 0) {
            outLength.writeInt(0, 0);
            return WordFactory.nullPointer();
        }

        // Allocate unmanaged memory for the sprite data
        Pointer ptr = UnmanagedMemory.malloc(WordFactory.unsigned(totalBytes));
        if (ptr.isNull()) {
            outLength.writeInt(0, 0);
            return WordFactory.nullPointer();
        }

        int offset = 0;
        for (Sprite s : sprites) {
            // channel (4 bytes, big-endian)
            ptr.writeByte(offset, (byte) (s.getChannel() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getChannel() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getChannel() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getChannel());
            offset += 4;
            // locH
            ptr.writeByte(offset, (byte) (s.getLocH() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getLocH() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getLocH() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getLocH());
            offset += 4;
            // locV
            ptr.writeByte(offset, (byte) (s.getLocV() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getLocV() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getLocV() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getLocV());
            offset += 4;
            // width
            ptr.writeByte(offset, (byte) (s.getWidth() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getWidth() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getWidth() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getWidth());
            offset += 4;
            // height
            ptr.writeByte(offset, (byte) (s.getHeight() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getHeight() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getHeight() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getHeight());
            offset += 4;
            // castLib
            ptr.writeByte(offset, (byte) (s.getCastLib() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getCastLib() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getCastLib() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getCastLib());
            offset += 4;
            // castMember
            ptr.writeByte(offset, (byte) (s.getCastMember() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getCastMember() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getCastMember() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getCastMember());
            offset += 4;
            // ink
            ptr.writeByte(offset, (byte) (s.getInk() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getInk() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getInk() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getInk());
            offset += 4;
            // blend
            ptr.writeByte(offset, (byte) (s.getBlend() >> 24));
            ptr.writeByte(offset + 1, (byte) (s.getBlend() >> 16));
            ptr.writeByte(offset + 2, (byte) (s.getBlend() >> 8));
            ptr.writeByte(offset + 3, (byte) s.getBlend());
            offset += 4;
            // visible (1 byte)
            ptr.writeByte(offset, (byte) (s.isVisible() ? 1 : 0));
            offset += 1;
        }

        outLength.writeInt(0, totalBytes);
        return ptr;
    }

    /**
     * Allocate memory (for WASM interop).
     * @param size Number of bytes to allocate
     * @return Pointer to allocated memory, or null on failure
     */
    @CEntryPoint(name = "malloc")
    public static Pointer malloc(IsolateThread thread, int size) {
        if (size <= 0) {
            return WordFactory.nullPointer();
        }
        return UnmanagedMemory.malloc(WordFactory.unsigned(size));
    }

    /**
     * Free memory (for WASM interop).
     * @param ptr Pointer to free
     */
    @CEntryPoint(name = "free")
    public static void free(IsolateThread thread, Pointer ptr) {
        if (ptr.isNonNull()) {
            UnmanagedMemory.free(ptr);
        }
    }
}
