package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Score chunk (VWSC).
 * Contains the timeline/score data with frames and sprite channels.
 */
public record ScoreChunk(
    int id,
    int totalLength,
    int headerLength,
    int frameCount,
    int channelCount,
    List<Frame> frames
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.VWSC;
    }

    public record Frame(
        int frameNum,
        List<SpriteSpan> sprites
    ) {}

    public record SpriteSpan(
        int channel,
        int startFrame,
        int endFrame,
        int castMemberId,
        int castLibId,
        int inkMode,
        int blend,
        int locH,
        int locV,
        int width,
        int height,
        boolean visible,
        boolean moveable,
        boolean editable
    ) {}

    public Frame getFrame(int frameNum) {
        if (frameNum >= 1 && frameNum <= frames.size()) {
            return frames.get(frameNum - 1);
        }
        return null;
    }

    public static ScoreChunk read(BinaryReader reader, int id, int version) {
        // Score chunk uses big endian
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        // Add bounds checking - if not enough data, return empty score
        if (reader.bytesLeft() < 12) {
            return new ScoreChunk(id, 0, 0, 0, 0, new ArrayList<>());
        }

        int totalLength = reader.readI32();
        int headerLength = reader.readI32();

        // Sanity check: if values are unreasonable, return empty score
        if (totalLength < 0 || totalLength > 10_000_000 || headerLength < 0 || headerLength > 10_000_000) {
            return new ScoreChunk(id, totalLength, headerLength, 0, 0, new ArrayList<>());
        }

        if (reader.bytesLeft() < 6) {
            return new ScoreChunk(id, totalLength, headerLength, 0, 0, new ArrayList<>());
        }

        int frameCount = reader.readI32();

        // Sanity check: reasonable frame count (max 100000 frames)
        if (frameCount < 0 || frameCount > 100_000) {
            return new ScoreChunk(id, totalLength, headerLength, 0, 0, new ArrayList<>());
        }

        if (reader.bytesLeft() < 4) {
            return new ScoreChunk(id, totalLength, headerLength, frameCount, 0, new ArrayList<>());
        }

        int framesLength = reader.readI16() & 0xFFFF;
        int channelCount = reader.readI16() & 0xFFFF;

        // Sanity check: reasonable channel count (max 1000)
        if (channelCount > 1000) {
            channelCount = 1000;
        }

        // For now, create empty frame placeholders
        // Full parsing would require reading the compressed score data
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < Math.min(frameCount, 10000); i++) {
            frames.add(new Frame(i + 1, new ArrayList<>()));
        }

        return new ScoreChunk(id, totalLength, headerLength, frameCount, channelCount, frames);
    }
}
