package com.libreshockwave.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ConfigChunk;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.player.Score;
import com.libreshockwave.player.Sprite;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight player for WASM environment.
 * Does not support network loading or script execution - basic frame playback only.
 */
public class WasmPlayer {

    public enum PlayState {
        STOPPED, PLAYING, PAUSED
    }

    private DirectorFile movieFile;
    private CastManager castManager;
    private Score score;

    private PlayState state = PlayState.STOPPED;
    private int currentFrame = 1;
    private int lastFrame = 1;
    private int tempo = 15;
    private int stageWidth = 640;
    private int stageHeight = 480;

    // Sprite states
    private final Map<Integer, SpriteState> sprites = new HashMap<>();

    public static class SpriteState {
        public int channel;
        public int locH, locV;
        public int width, height;
        public int castLib, castMember;
        public int ink = 0;
        public int blend = 100;
        public boolean visible = true;
    }

    public WasmPlayer() {
        // Initialize with defaults
    }

    /**
     * Load a movie from byte data.
     */
    public void loadMovie(byte[] data) throws Exception {
        movieFile = DirectorFile.load(data);
        castManager = movieFile.createCastManager();

        // Get config
        ConfigChunk config = movieFile.getConfig();
        if (config != null) {
            tempo = config.tempo();
            stageWidth = config.stageRight() - config.stageLeft();
            stageHeight = config.stageBottom() - config.stageTop();
        }

        // Create score
        if (movieFile.hasScore()) {
            score = movieFile.createScore();
            lastFrame = score.getFrameCount();
        } else {
            lastFrame = 1;
        }

        // Reset state
        currentFrame = 1;
        state = PlayState.STOPPED;
        sprites.clear();

        // Load initial frame
        loadSpritesFromScore();
    }

    public void play() {
        state = PlayState.PLAYING;
    }

    public void stop() {
        state = PlayState.STOPPED;
        currentFrame = 1;
        loadSpritesFromScore();
    }

    public void pause() {
        state = PlayState.PAUSED;
    }

    public void nextFrame() {
        if (currentFrame < lastFrame) {
            currentFrame++;
            loadSpritesFromScore();
        }
    }

    public void prevFrame() {
        if (currentFrame > 1) {
            currentFrame--;
            loadSpritesFromScore();
        }
    }

    public void goToFrame(int frame) {
        currentFrame = Math.max(1, Math.min(frame, lastFrame));
        loadSpritesFromScore();
    }

    public void tick() {
        if (state != PlayState.PLAYING) return;

        currentFrame++;
        if (currentFrame > lastFrame) {
            currentFrame = 1; // Loop
        }
        loadSpritesFromScore();
    }

    private void loadSpritesFromScore() {
        sprites.clear();

        if (score == null) return;

        Score.Frame frame = score.getFrame(currentFrame);
        if (frame == null) return;

        for (Sprite sprite : frame.getSprites()) {
            if (sprite.getCastMember() <= 0) continue;

            SpriteState ss = new SpriteState();
            ss.channel = sprite.getChannel();
            ss.locH = sprite.getLocH();
            ss.locV = sprite.getLocV();
            ss.width = sprite.getWidth();
            ss.height = sprite.getHeight();
            ss.castLib = sprite.getCastLib();
            ss.castMember = sprite.getCastMember();
            ss.ink = sprite.getInk();
            ss.blend = sprite.getBlend();
            ss.visible = sprite.isVisible();

            sprites.put(ss.channel, ss);
        }
    }

    // Getters
    public PlayState getState() { return state; }
    public int getCurrentFrame() { return currentFrame; }
    public int getLastFrame() { return lastFrame; }
    public int getTempo() { return tempo; }
    public int getStageWidth() { return stageWidth; }
    public int getStageHeight() { return stageHeight; }
    public Score getScore() { return score; }
    public Map<Integer, SpriteState> getSprites() { return Collections.unmodifiableMap(sprites); }
    public boolean isLoaded() { return movieFile != null; }
}
