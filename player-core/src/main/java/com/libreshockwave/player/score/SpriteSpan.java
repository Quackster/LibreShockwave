package com.libreshockwave.player.score;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a span of frames where a sprite/behavior is active.
 * Channel 0 is reserved for frame behaviors, channels 1+ are for sprites.
 */
public class SpriteSpan {

    private final int channel;
    private final int startFrame;  // Inclusive, 1-indexed
    private final int endFrame;    // Inclusive, 1-indexed
    private final List<ScoreBehaviorRef> behaviors;

    public SpriteSpan(int channel, int startFrame, int endFrame) {
        this.channel = channel;
        this.startFrame = startFrame;
        this.endFrame = endFrame;
        this.behaviors = new ArrayList<>();
    }

    public int getChannel() {
        return channel;
    }

    public int getStartFrame() {
        return startFrame;
    }

    public int getEndFrame() {
        return endFrame;
    }

    public List<ScoreBehaviorRef> getBehaviors() {
        return behaviors;
    }

    public void addBehavior(ScoreBehaviorRef behavior) {
        behaviors.add(behavior);
    }

    /**
     * Check if this span is a frame behavior (channel 0).
     */
    public boolean isFrameBehavior() {
        return channel == 0;
    }

    /**
     * Check if the given frame is within this span's range.
     */
    public boolean containsFrame(int frame) {
        return frame >= startFrame && frame <= endFrame;
    }

    /**
     * Get the first behavior in this span (typically the main behavior).
     */
    public ScoreBehaviorRef getFirstBehavior() {
        return behaviors.isEmpty() ? null : behaviors.get(0);
    }

    @Override
    public String toString() {
        return "SpriteSpan{channel=" + channel +
               ", frames=" + startFrame + "-" + endFrame +
               ", behaviors=" + behaviors.size() + "}";
    }
}
