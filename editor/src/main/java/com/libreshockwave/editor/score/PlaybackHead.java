package com.libreshockwave.editor.score;

/**
 * Tracks the playback head position in the score timeline.
 */
public class PlaybackHead {

    private int frame = 1;

    public int getFrame() {
        return frame;
    }

    public void setFrame(int frame) {
        this.frame = Math.max(1, frame);
    }
}
