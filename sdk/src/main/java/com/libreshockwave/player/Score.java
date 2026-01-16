package com.libreshockwave.player;

import com.libreshockwave.chunks.FrameLabelsChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.chunks.ScoreChunk.*;

import java.util.*;

/**
 * Manages the score (timeline) with frames and sprite channels.
 * Matches dirplayer-rs vm-rust/src/player/score.rs
 */
public class Score {

    // Reserved channel indices (0-based)
    public static final int CHANNEL_SCRIPT = 0;
    public static final int CHANNEL_PALETTE = 1;
    public static final int CHANNEL_TRANSITION = 2;
    public static final int CHANNEL_SOUND1 = 3;
    public static final int CHANNEL_SOUND2 = 4;
    public static final int CHANNEL_TEMPO = 5;
    public static final int FIRST_SPRITE_CHANNEL = 6;

    private final int frameCount;
    private final int channelCount;
    private final int spriteRecordSize;

    private final List<Frame> frames;
    private final Map<String, Integer> frameLabels;
    private final List<FrameInterval> frameIntervals;

    /**
     * Create a Score from a parsed ScoreChunk.
     */
    public Score(ScoreChunk chunk) {
        this(chunk, null);
    }

    /**
     * Create a Score from a parsed ScoreChunk and optional FrameLabelsChunk.
     */
    public Score(ScoreChunk chunk, FrameLabelsChunk labelsChunk) {
        ScoreFrameData frameData = chunk.frameData();
        FrameDataHeader header = frameData.header();

        this.frameCount = header.frameCount();
        this.channelCount = header.numChannels();
        this.spriteRecordSize = header.spriteRecordSize();
        this.frameIntervals = new ArrayList<>(chunk.frameIntervals());

        this.frames = new ArrayList<>();
        this.frameLabels = new HashMap<>();

        // Initialize empty frames
        for (int i = 0; i < frameCount; i++) {
            frames.add(new Frame(i + 1, channelCount));
        }

        // Populate frames from parsed channel data
        for (FrameChannelEntry entry : frameData.frameChannelData()) {
            int frameIndex = entry.frameIndex();
            int channelIndex = entry.channelIndex();
            ChannelData cd = entry.data();

            if (frameIndex >= 0 && frameIndex < frames.size()) {
                Frame frame = frames.get(frameIndex);
                populateFrameFromChannelData(frame, channelIndex, cd);
            }
        }

        // Load frame labels if provided
        if (labelsChunk != null) {
            for (FrameLabelsChunk.FrameLabel label : labelsChunk.labels()) {
                frameLabels.put(label.label(), label.frameNum());
            }
        }
    }

    private void populateFrameFromChannelData(Frame frame, int channelIndex, ChannelData cd) {
        if (channelIndex == CHANNEL_SCRIPT) {
            // Frame script - castMember is the script ID
            if (cd.castMember() > 0) {
                frame.setScriptCastLib(cd.castLib());
                frame.setScriptCastMember(cd.castMember());
            }
        } else if (channelIndex == CHANNEL_PALETTE) {
            if (cd.castMember() > 0) {
                frame.setPaletteId(cd.castMember());
            }
        } else if (channelIndex == CHANNEL_TRANSITION) {
            if (cd.castMember() > 0) {
                frame.setTransitionId(cd.castMember());
            }
        } else if (channelIndex == CHANNEL_SOUND1) {
            if (cd.castMember() > 0) {
                frame.setSound1Id(cd.castMember());
            }
        } else if (channelIndex == CHANNEL_SOUND2) {
            if (cd.castMember() > 0) {
                frame.setSound2Id(cd.castMember());
            }
        } else if (channelIndex == CHANNEL_TEMPO) {
            // Tempo channel
            if (cd.castMember() > 0) {
                frame.setTempo(cd.castMember());
            }
        } else if (channelIndex >= FIRST_SPRITE_CHANNEL) {
            // Sprite channel (1-based sprite number)
            int spriteChannel = channelIndex - FIRST_SPRITE_CHANNEL + 1;

            if (cd.castMember() > 0) {
                Sprite sprite = new Sprite(spriteChannel);
                sprite.setCastLib(cd.castLib());
                sprite.setCastMember(cd.castMember());
                sprite.setLocH(cd.posX());
                sprite.setLocV(cd.posY());
                sprite.setWidth(cd.width());
                sprite.setHeight(cd.height());
                sprite.setInk(cd.ink());
                sprite.setForeColor(cd.foreColor());
                sprite.setBackColor(cd.backColor());
                sprite.setSpriteType(cd.spriteType());

                frame.setSprite(spriteChannel, sprite);
            }
        }
    }

    // Accessors

    public int getFrameCount() {
        return frameCount;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public Frame getFrame(int frameNum) {
        if (frameNum >= 1 && frameNum <= frames.size()) {
            return frames.get(frameNum - 1);
        }
        return null;
    }

    public void setFrameLabel(String label, int frameNum) {
        frameLabels.put(label, frameNum);
    }

    public int getFrameByLabel(String label) {
        return frameLabels.getOrDefault(label, -1);
    }

    public Map<String, Integer> getFrameLabels() {
        return Collections.unmodifiableMap(frameLabels);
    }

    /**
     * Get behavior intervals that are active for a given frame and channel.
     */
    public List<FrameInterval> getBehaviorsForFrameChannel(int frameNum, int channelIndex) {
        List<FrameInterval> result = new ArrayList<>();
        for (FrameInterval interval : frameIntervals) {
            FrameIntervalPrimary primary = interval.primary();
            if (primary.channelIndex() == channelIndex
                && frameNum >= primary.startFrame()
                && frameNum <= primary.endFrame()) {
                result.add(interval);
            }
        }
        return result;
    }

    /**
     * Get all frame intervals (behavior attachments).
     */
    public List<FrameInterval> getFrameIntervals() {
        return Collections.unmodifiableList(frameIntervals);
    }

    /**
     * Represents a single frame in the score.
     */
    public static class Frame {
        private final int frameNum;
        private final Map<Integer, Sprite> sprites;
        private int scriptCastLib;
        private int scriptCastMember;
        private int paletteId;
        private int transitionId;
        private int sound1Id;
        private int sound2Id;
        private int tempo;

        public Frame(int frameNum, int channelCount) {
            this.frameNum = frameNum;
            this.sprites = new HashMap<>();
        }

        public int getFrameNum() { return frameNum; }

        public Sprite getSprite(int channel) {
            return sprites.get(channel);
        }

        public void setSprite(int channel, Sprite sprite) {
            sprites.put(channel, sprite);
        }

        public Collection<Sprite> getSprites() {
            return sprites.values();
        }

        public List<Sprite> getSpritesSorted() {
            List<Sprite> sorted = new ArrayList<>(sprites.values());
            sorted.sort(Comparator.comparingInt(Sprite::getChannel));
            return sorted;
        }

        public int getScriptCastLib() { return scriptCastLib; }
        public void setScriptCastLib(int scriptCastLib) { this.scriptCastLib = scriptCastLib; }

        public int getScriptCastMember() { return scriptCastMember; }
        public void setScriptCastMember(int scriptCastMember) { this.scriptCastMember = scriptCastMember; }

        public boolean hasFrameScript() { return scriptCastMember > 0; }

        public int getPaletteId() { return paletteId; }
        public void setPaletteId(int paletteId) { this.paletteId = paletteId; }

        public int getTransitionId() { return transitionId; }
        public void setTransitionId(int transitionId) { this.transitionId = transitionId; }

        public int getSound1Id() { return sound1Id; }
        public void setSound1Id(int sound1Id) { this.sound1Id = sound1Id; }

        public int getSound2Id() { return sound2Id; }
        public void setSound2Id(int sound2Id) { this.sound2Id = sound2Id; }

        public int getTempo() { return tempo; }
        public void setTempo(int tempo) { this.tempo = tempo; }
    }
}
