package com.libreshockwave.player;

import com.libreshockwave.player.bitmap.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a film loop cast member.
 * A film loop is a sequence of frames that can be played as a single sprite.
 */
public class FilmLoop {

    private final int castLib;
    private final int memberNum;
    private final String name;
    private final List<Frame> frames;
    private final int width;
    private final int height;
    private boolean looping;
    private int currentFrame;

    public FilmLoop(int castLib, int memberNum, String name, int width, int height) {
        this.castLib = castLib;
        this.memberNum = memberNum;
        this.name = name;
        this.width = width;
        this.height = height;
        this.frames = new ArrayList<>();
        this.looping = true;
        this.currentFrame = 0;
    }

    /**
     * A single frame in the film loop, containing sprite data.
     */
    public static class Frame {
        private final List<SpriteState> sprites;
        private int tempo;
        private int palette;

        public Frame() {
            this.sprites = new ArrayList<>();
            this.tempo = 15;
            this.palette = -1;
        }

        public void addSprite(SpriteState sprite) {
            sprites.add(sprite);
        }

        public List<SpriteState> getSprites() {
            return sprites;
        }

        public int getTempo() { return tempo; }
        public void setTempo(int tempo) { this.tempo = tempo; }

        public int getPalette() { return palette; }
        public void setPalette(int palette) { this.palette = palette; }
    }

    /**
     * State of a sprite within a film loop frame.
     */
    public static class SpriteState {
        private final int channel;
        private int memberNum;
        private int castLib;
        private int locH;
        private int locV;
        private int width;
        private int height;
        private int ink;
        private int blend;
        private boolean visible;
        private int foreColor;
        private int backColor;

        public SpriteState(int channel) {
            this.channel = channel;
            this.visible = true;
            this.ink = 0; // Copy
            this.blend = 100;
        }

        // Getters and setters
        public int getChannel() { return channel; }
        public int getMemberNum() { return memberNum; }
        public void setMemberNum(int memberNum) { this.memberNum = memberNum; }
        public int getCastLib() { return castLib; }
        public void setCastLib(int castLib) { this.castLib = castLib; }
        public int getLocH() { return locH; }
        public void setLocH(int locH) { this.locH = locH; }
        public int getLocV() { return locV; }
        public void setLocV(int locV) { this.locV = locV; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
        public int getInk() { return ink; }
        public void setInk(int ink) { this.ink = ink; }
        public int getBlend() { return blend; }
        public void setBlend(int blend) { this.blend = blend; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
        public int getForeColor() { return foreColor; }
        public void setForeColor(int foreColor) { this.foreColor = foreColor; }
        public int getBackColor() { return backColor; }
        public void setBackColor(int backColor) { this.backColor = backColor; }
    }

    // Frame management

    public void addFrame(Frame frame) {
        frames.add(frame);
    }

    public Frame getFrame(int index) {
        if (index >= 0 && index < frames.size()) {
            return frames.get(index);
        }
        return null;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public Frame getCurrentFrameData() {
        return getFrame(currentFrame);
    }

    // Playback control

    public void advanceFrame() {
        if (frames.isEmpty()) return;

        currentFrame++;
        if (currentFrame >= frames.size()) {
            if (looping) {
                currentFrame = 0;
            } else {
                currentFrame = frames.size() - 1;
            }
        }
    }

    public void setCurrentFrame(int frame) {
        if (frame >= 0 && frame < frames.size()) {
            this.currentFrame = frame;
        }
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public void reset() {
        currentFrame = 0;
    }

    // Properties

    public int getCastLib() { return castLib; }
    public int getMemberNum() { return memberNum; }
    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isLooping() { return looping; }
    public void setLooping(boolean looping) { this.looping = looping; }
}
