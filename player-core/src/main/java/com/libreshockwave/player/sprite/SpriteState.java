package com.libreshockwave.player.sprite;

import com.libreshockwave.chunks.ScoreChunk;

/**
 * Holds runtime state for a sprite on the stage.
 * Tracks position, size, and visibility that can be modified by scripts.
 */
public class SpriteState {
    private final int channel;
    private final ScoreChunk.ChannelData initialData;

    private int locH;
    private int locV;
    private int width;
    private int height;
    private boolean visible = true;

    public SpriteState(int channel, ScoreChunk.ChannelData data) {
        this.channel = channel;
        this.initialData = data;
        this.locH = data.posX();
        this.locV = data.posY();
        this.width = data.width();
        this.height = data.height();
    }

    public int getChannel() { return channel; }
    public int getLocH() { return locH; }
    public int getLocV() { return locV; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isVisible() { return visible; }

    public void setLocH(int locH) { this.locH = locH; }
    public void setLocV(int locV) { this.locV = locV; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public ScoreChunk.ChannelData getInitialData() { return initialData; }
}
