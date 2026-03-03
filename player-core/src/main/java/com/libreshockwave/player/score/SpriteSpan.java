package com.libreshockwave.player.score;

import com.libreshockwave.id.ChannelId;
import com.libreshockwave.id.FrameId;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a span of frames where a sprite/behavior is active.
 * Channel 0 is reserved for frame behaviors, channels 1+ are for sprites.
 */
public class SpriteSpan {

    private final ChannelId channel;
    private final FrameId startFrame;  // Inclusive
    private final FrameId endFrame;    // Inclusive
    private final List<ScoreBehaviorRef> behaviors;

    public SpriteSpan(int channel, int startFrame, int endFrame) {
        this.channel = new ChannelId(channel);
        // Score data can have startFrame/endFrame of 0; clamp to 1 for FrameId validity
        this.startFrame = new FrameId(Math.max(1, startFrame));
        this.endFrame = new FrameId(Math.max(1, endFrame));
        this.behaviors = new ArrayList<>();
    }

    public int getChannel() {
        return channel.value();
    }

    public ChannelId getChannelId() {
        return channel;
    }

    public int getStartFrame() {
        return startFrame.value();
    }

    public FrameId getStartFrameId() {
        return startFrame;
    }

    public int getEndFrame() {
        return endFrame.value();
    }

    public FrameId getEndFrameId() {
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
        return channel.value() == 0;
    }

    /**
     * Check if the given frame is within this span's range.
     */
    public boolean containsFrame(int frame) {
        return frame >= startFrame.value() && frame <= endFrame.value();
    }

    /**
     * Get the first behavior in this span (typically the main behavior).
     */
    public ScoreBehaviorRef getFirstBehavior() {
        return behaviors.isEmpty() ? null : behaviors.get(0);
    }

    @Override
    public String toString() {
        return "SpriteSpan{channel=" + channel.value() +
               ", frames=" + startFrame.value() + "-" + endFrame.value() +
               ", behaviors=" + behaviors.size() + "}";
    }
}
