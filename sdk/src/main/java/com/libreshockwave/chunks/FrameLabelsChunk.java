package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.vm.LingoVM;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame labels chunk (VWLB).
 * Contains labels for specific frames in the score.
 *
 * Structure matches dirplayer-rs vm-rust/src/director/chunks/score.rs FrameLabelsChunk
 */
public record FrameLabelsChunk(
    DirectorFile file,
    int id,
    List<FrameLabel> labels
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.VWLB;
    }

    /**
     * A single frame label.
     */
    public record FrameLabel(
        int frameNum,
        String label
    ) {}

    /**
     * Get a frame number by label name.
     */
    public int getFrameByLabel(String labelName) {
        for (FrameLabel label : labels) {
            if (label.label().equalsIgnoreCase(labelName)) {
                return label.frameNum();
            }
        }
        return -1;
    }

    /**
     * Get the label for a frame number.
     */
    public String getLabelForFrame(int frameNum) {
        for (FrameLabel label : labels) {
            if (label.frameNum() == frameNum) {
                return label.label();
            }
        }
        return null;
    }

    public static FrameLabelsChunk read(DirectorFile file, BinaryReader reader, int id, int version) {
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        List<FrameLabel> labels = new ArrayList<>();

        if (reader.bytesLeft() < 2) {
            return new FrameLabelsChunk(file, id, labels);
        }

        int labelsCount = reader.readU16();

        // Read label frame/offset pairs
        List<int[]> labelFrames = new ArrayList<>();
        for (int i = 0; i < labelsCount; i++) {
            if (reader.bytesLeft() < 4) break;
            int frameNum = reader.readU16();
            int labelOffset = reader.readU16();
            labelFrames.add(new int[] { labelOffset, frameNum });
        }

        if (reader.bytesLeft() < 4) {
            return new FrameLabelsChunk(file, id, labels);
        }

        int labelsSize = reader.readI32();

        // Read label strings
        for (int i = 0; i < labelFrames.size(); i++) {
            int labelOffset = labelFrames.get(i)[0];
            int frameNum = labelFrames.get(i)[1];

            int labelLen;
            if (i < labelFrames.size() - 1) {
                labelLen = labelFrames.get(i + 1)[0] - labelOffset;
            } else {
                labelLen = labelsSize - labelOffset;
            }

            if (reader.bytesLeft() >= labelLen && labelLen > 0) {
                String labelStr = reader.readString(labelLen);
                labels.add(new FrameLabel(frameNum, labelStr));
            }
        }

        return new FrameLabelsChunk(file, id, labels);
    }
}
