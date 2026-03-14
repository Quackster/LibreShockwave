package com.libreshockwave.editor.score;

import java.awt.*;

/**
 * Data model bridging ScoreChunk data to the ScorePanel UI.
 * Provides frame count, channel count, and cell data for rendering.
 */
public class ScoreModel {

    private int frameCount;
    private int channelCount;
    // Will be populated from ScoreChunk data
    private Color[][] cellColors;

    public ScoreModel() {
        this.frameCount = 0;
        this.channelCount = 0;
    }

    public ScoreModel(int frameCount, int channelCount) {
        this.frameCount = frameCount;
        this.channelCount = channelCount;
        this.cellColors = new Color[channelCount][frameCount];
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public Color getCellColor(int channel, int frame) {
        if (cellColors == null || channel >= channelCount || frame >= frameCount) {
            return null;
        }
        return cellColors[channel][frame];
    }

    public void setCellColor(int channel, int frame, Color color) {
        if (cellColors != null && channel < channelCount && frame < frameCount) {
            cellColors[channel][frame] = color;
        }
    }
}
