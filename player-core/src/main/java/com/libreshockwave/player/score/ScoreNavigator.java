package com.libreshockwave.player.score;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.FrameLabelsChunk;
import com.libreshockwave.chunks.ScoreChunk;

import java.util.*;

/**
 * Navigates the score and manages sprite spans.
 * Provides methods to find behaviors for frames and sprites.
 */
public class ScoreNavigator {

    private final DirectorFile file;
    private final List<SpriteSpan> spriteSpans;
    private final Map<String, Integer> frameLabels;

    public ScoreNavigator(DirectorFile file) {
        this.file = file;
        this.spriteSpans = new ArrayList<>();
        this.frameLabels = new HashMap<>();

        buildSpriteSpans();
        buildFrameLabels();
    }

    /**
     * Build sprite spans from the score's frame interval data.
     */
    private void buildSpriteSpans() {
        if (file == null) return;
        ScoreChunk score = file.getScoreChunk();
        if (score == null) return;

        for (ScoreChunk.FrameInterval interval : score.frameIntervals()) {
            ScoreChunk.FrameIntervalPrimary primary = interval.primary();
            int channel = primary.channelIndex();
            int startFrame = primary.startFrame();
            int endFrame = primary.endFrame();

            SpriteSpan span = new SpriteSpan(channel, startFrame, endFrame);

            // Add behavior from secondary if present
            if (interval.secondary() != null) {
                ScoreChunk.FrameIntervalSecondary secondary = interval.secondary();
                ScoreBehaviorRef behavior = new ScoreBehaviorRef(
                    secondary.castLib(),
                    secondary.castMember()
                );
                span.addBehavior(behavior);
            }

            spriteSpans.add(span);
        }
    }

    /**
     * Build frame labels map.
     */
    private void buildFrameLabels() {
        if (file == null) return;
        FrameLabelsChunk labels = file.getFrameLabelsChunk();
        if (labels == null) return;

        for (FrameLabelsChunk.FrameLabel label : labels.labels()) {
            frameLabels.put(label.label().toLowerCase(), label.frameNum());
        }
    }

    /**
     * Get the frame script (channel 0 behavior) for the given frame.
     * @param frame 1-indexed frame number
     * @return The frame behavior reference, or null if none
     */
    public ScoreBehaviorRef getFrameScript(int frame) {
        for (SpriteSpan span : spriteSpans) {
            if (span.isFrameBehavior() && span.containsFrame(frame)) {
                return span.getFirstBehavior();
            }
        }
        return null;
    }

    /**
     * Get all sprite behaviors for a specific channel at the given frame.
     * @param frame 1-indexed frame number
     * @param channel Sprite channel (1+)
     * @return List of behavior references
     */
    public List<ScoreBehaviorRef> getSpriteBehaviors(int frame, int channel) {
        List<ScoreBehaviorRef> behaviors = new ArrayList<>();
        for (SpriteSpan span : spriteSpans) {
            if (span.getChannel() == channel && span.containsFrame(frame)) {
                behaviors.addAll(span.getBehaviors());
            }
        }
        return behaviors;
    }

    /**
     * Get all sprite spans that are active in the given frame.
     * @param frame 1-indexed frame number
     * @return List of active sprite spans (excluding frame behaviors)
     */
    public List<SpriteSpan> getActiveSprites(int frame) {
        List<SpriteSpan> active = new ArrayList<>();
        for (SpriteSpan span : spriteSpans) {
            if (!span.isFrameBehavior() && span.containsFrame(frame)) {
                active.add(span);
            }
        }
        return active;
    }

    /**
     * Get all channels that have sprites in the given frame.
     */
    public Set<Integer> getActiveChannels(int frame) {
        Set<Integer> channels = new HashSet<>();
        for (SpriteSpan span : spriteSpans) {
            if (!span.isFrameBehavior() && span.containsFrame(frame)) {
                channels.add(span.getChannel());
            }
        }
        return channels;
    }

    /**
     * Get the frame number for a label.
     * @return Frame number (1-indexed) or -1 if not found
     */
    public int getFrameForLabel(String label) {
        Integer frame = frameLabels.get(label.toLowerCase());
        return frame != null ? frame : -1;
    }

    /**
     * Get all frame labels.
     */
    public Set<String> getFrameLabels() {
        return Collections.unmodifiableSet(frameLabels.keySet());
    }

    /**
     * Get the total number of frames in the score.
     */
    public int getFrameCount() {
        if (file == null) return 0;
        ScoreChunk score = file.getScoreChunk();
        return score != null ? score.getFrameCount() : 0;
    }

    /**
     * Get all sprite spans.
     */
    public List<SpriteSpan> getAllSpans() {
        return Collections.unmodifiableList(spriteSpans);
    }
}
