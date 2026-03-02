package com.libreshockwave.player.sprite;

import com.libreshockwave.chunks.ScoreChunk;

/**
 * Holds runtime state for a sprite on the stage.
 * Tracks position, size, and visibility that can be modified by scripts.
 * Supports both Score-based sprites and dynamically puppeted sprites.
 */
public class SpriteState {
    private final int channel;
    private final ScoreChunk.ChannelData initialData;  // null for dynamic sprites

    private int locH;
    private int locV;
    private int locZ;
    private int width;
    private int height;
    private boolean visible = true;
    private boolean puppet = false;
    private int ink = 0;
    private int blend = 100;
    private int stretch = 0;
    private int foreColor = 0;
    private int backColor = 0xFFFFFF;
    private boolean hasForeColor = false;
    private boolean hasBackColor = false;
    private boolean hasSizeChanged = false;

    // Dynamic member assignment (overrides Score data when set)
    private int dynamicCastLib = -1;
    private int dynamicCastMember = -1;
    private boolean hasDynamicMember = false;

    /**
     * Create from Score data (traditional Score-based sprite).
     */
    public SpriteState(int channel, ScoreChunk.ChannelData data) {
        this.channel = channel;
        this.initialData = data;
        this.locH = data.posX();
        this.locV = data.posY();
        this.width = data.width();
        this.height = data.height();
        this.ink = data.ink();
        this.foreColor = data.resolvedForeColor();
        this.backColor = data.backColor();
    }

    /**
     * Create a dynamic/puppeted sprite (no Score data).
     */
    public SpriteState(int channel) {
        this.channel = channel;
        this.initialData = null;
        this.puppet = true;
    }

    public int getChannel() { return channel; }
    public int getLocH() { return locH; }
    public int getLocV() { return locV; }
    public int getLocZ() { return locZ; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isVisible() { return visible; }
    public boolean isPuppet() { return puppet; }
    public int getInk() { return ink; }
    public int getBlend() { return blend; }
    public int getStretch() { return stretch; }
    public int getForeColor() { return foreColor; }
    public int getBackColor() { return backColor; }
    public boolean hasForeColor() { return hasForeColor; }
    public boolean hasBackColor() { return hasBackColor; }

    public synchronized void setLocH(int locH) { this.locH = locH; }
    public synchronized void setLocV(int locV) { this.locV = locV; }
    public synchronized void setLocZ(int locZ) { this.locZ = locZ; }
    public synchronized void setWidth(int width) { this.width = width; this.hasSizeChanged = true; }
    public synchronized void setHeight(int height) { this.height = height; this.hasSizeChanged = true; }
    public synchronized void setVisible(boolean visible) { this.visible = visible; }
    public synchronized void setPuppet(boolean puppet) { this.puppet = puppet; }
    public synchronized void setInk(int ink) { this.ink = ink; }
    public synchronized void setBlend(int blend) { this.blend = blend; }
    public synchronized void setStretch(int stretch) { this.stretch = stretch; }
    public synchronized void setForeColor(int foreColor) { this.foreColor = foreColor; this.hasForeColor = true; }
    public synchronized void setBackColor(int backColor) { this.backColor = backColor; this.hasBackColor = true; }

    /**
     * Atomically capture all mutable position fields to prevent torn reads
     * when the VM thread updates position mid-render.
     * @return array of [locH, locV, locZ, width, height]
     */
    public synchronized int[] snapshotPosition() {
        return new int[]{ locH, locV, locZ, width, height };
    }

    /**
     * Set a dynamic cast member (overrides Score data).
     */
    public synchronized void setDynamicMember(int castLib, int member) {
        this.dynamicCastLib = castLib;
        this.dynamicCastMember = member;
        this.hasDynamicMember = true;
    }

    /**
     * Get the effective cast library number (dynamic or from Score).
     */
    public int getEffectiveCastLib() {
        if (hasDynamicMember) {
            return dynamicCastLib;
        }
        return initialData != null ? initialData.castLib() : 0;
    }

    /**
     * Get the effective cast member number (dynamic or from Score).
     */
    public int getEffectiveCastMember() {
        if (hasDynamicMember) {
            return dynamicCastMember;
        }
        return initialData != null ? initialData.castMember() : 0;
    }

    public boolean hasDynamicMember() { return hasDynamicMember; }
    public boolean isDynamic() { return initialData == null; }
    public boolean hasSizeChanged() { return hasSizeChanged; }

    /**
     * Apply intrinsic dimensions from a member (e.g., bitmap width/height).
     * Only applies if the script hasn't explicitly set width/height.
     */
    public synchronized void applyIntrinsicSize(int w, int h) {
        if (!hasSizeChanged && w > 0 && h > 0) {
            this.width = w;
            this.height = h;
        }
    }

    public ScoreChunk.ChannelData getInitialData() { return initialData; }
}
