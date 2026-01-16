package com.libreshockwave.player;

import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.*;

/**
 * Manages the score (timeline) with frames and sprite channels.
 */
public class Score {

    // Reserved channel indices
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

    public Score(ScoreChunk chunk) {
        this.frameCount = chunk.frameCount();
        this.channelCount = chunk.channelCount();
        this.spriteRecordSize = 24; // Default sprite record size

        this.frames = new ArrayList<>();
        this.frameLabels = new HashMap<>();

        // Initialize empty frames
        for (int i = 0; i < frameCount; i++) {
            frames.add(new Frame(i + 1, channelCount));
        }
    }

    /**
     * Parse detailed score data from raw bytes.
     */
    public void parseFrameData(byte[] data) {
        if (data == null || data.length == 0) return;

        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        // Read header
        int actualLength = reader.readI32();
        int unk1 = reader.readI32();
        int parsedFrameCount = reader.readI32();
        int framesVersion = reader.readU16();
        int spriteRecordSize = reader.readU16();
        int numChannels = reader.readU16();

        if (framesVersion > 13) {
            reader.skip(2); // numChannelsDisplayed
        } else {
            reader.skip(2); // skip
        }

        // Allocate channel data buffer
        byte[] channelData = new byte[parsedFrameCount * numChannels * spriteRecordSize];

        int frameIndex = 0;
        while (!reader.eof() && frameIndex < parsedFrameCount) {
            int length = reader.readU16();
            if (length == 0) break;

            int frameLength = length - 2;
            if (frameLength > 0) {
                byte[] frameData = reader.readBytes(frameLength);
                BinaryReader frameReader = new BinaryReader(frameData, ByteOrder.BIG_ENDIAN);

                // Parse delta-compressed channel data
                while (!frameReader.eof()) {
                    int channelSize = frameReader.readU16();
                    int channelOffset = frameReader.readU16();
                    byte[] channelDelta = frameReader.readBytes(channelSize);

                    int frameOffset = frameIndex * numChannels * spriteRecordSize;
                    int destOffset = frameOffset + channelOffset;

                    if (destOffset + channelSize <= channelData.length) {
                        System.arraycopy(channelDelta, 0, channelData, destOffset, channelSize);
                    }
                }
            }
            frameIndex++;
        }

        // Parse channel data into sprites
        BinaryReader channelReader = new BinaryReader(channelData, ByteOrder.BIG_ENDIAN);

        for (int f = 0; f < parsedFrameCount && f < frames.size(); f++) {
            Frame frame = frames.get(f);

            for (int c = 0; c < numChannels; c++) {
                int pos = channelReader.getPosition();
                ChannelData cd = parseChannelData(channelReader);
                channelReader.setPosition(pos + spriteRecordSize);

                if (cd.castMember > 0 && c >= FIRST_SPRITE_CHANNEL) {
                    int spriteChannel = c - FIRST_SPRITE_CHANNEL + 1;
                    Sprite sprite = new Sprite(spriteChannel);
                    sprite.setCastLib(cd.castLib);
                    sprite.setCastMember(cd.castMember);
                    sprite.setLocH(cd.posX);
                    sprite.setLocV(cd.posY);
                    sprite.setWidth(cd.width);
                    sprite.setHeight(cd.height);
                    sprite.setInk(cd.ink);
                    sprite.setForeColor(cd.foreColor);
                    sprite.setBackColor(cd.backColor);

                    frame.setSprite(spriteChannel, sprite);
                }
            }
        }
    }

    private ChannelData parseChannelData(BinaryReader reader) {
        ChannelData cd = new ChannelData();

        if (reader.bytesLeft() < 24) {
            return cd;
        }

        cd.spriteType = reader.readU8();
        cd.ink = reader.readU8();
        cd.foreColor = reader.readU8();
        cd.backColor = reader.readU8();
        cd.castLib = reader.readU16();
        cd.castMember = reader.readU16();
        reader.skip(4); // unknown
        cd.posY = reader.readU16();
        cd.posX = reader.readU16();
        cd.height = reader.readU16();
        cd.width = reader.readU16();

        return cd;
    }

    private static class ChannelData {
        int spriteType;
        int ink;
        int foreColor;
        int backColor;
        int castLib;
        int castMember;
        int posX;
        int posY;
        int width;
        int height;
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
     * Represents a single frame in the score.
     */
    public static class Frame {
        private final int frameNum;
        private final Map<Integer, Sprite> sprites;
        private int scriptId;
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

        public int getScriptId() { return scriptId; }
        public void setScriptId(int scriptId) { this.scriptId = scriptId; }

        public int getPaletteId() { return paletteId; }
        public void setPaletteId(int paletteId) { this.paletteId = paletteId; }

        public int getTempo() { return tempo; }
        public void setTempo(int tempo) { this.tempo = tempo; }
    }
}
