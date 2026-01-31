package com.libreshockwave.tools.score;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.tools.format.ChannelNames;
import com.libreshockwave.tools.format.PaletteDescriptions;
import com.libreshockwave.tools.model.ScoreCellData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds score grid data for display.
 */
public class ScoreDataBuilder {

    /**
     * Builds a 2D array of score cell data for a Director file.
     * Rows = channels, Columns = frames.
     */
    public ScoreCellData[][] buildScoreData(DirectorFile dirFile) {
        ScoreChunk scoreChunk = dirFile.getScoreChunk();
        if (scoreChunk == null) {
            return new ScoreCellData[0][0];
        }

        int frameCount = scoreChunk.getFrameCount();
        int channelCount = scoreChunk.getChannelCount();

        ScoreCellData[][] data = new ScoreCellData[channelCount][frameCount];

        // Build a map of frame intervals (scripts attached to frames)
        Map<Integer, String> frameScriptMap = buildFrameScriptMap(dirFile, scoreChunk, frameCount);

        // Populate with frame channel data
        ScoreChunk.ScoreFrameData frameData = scoreChunk.frameData();
        if (frameData != null && frameData.frameChannelData() != null) {
            for (ScoreChunk.FrameChannelEntry entry : frameData.frameChannelData()) {
                int fr = entry.frameIndex();
                int ch = entry.channelIndex();
                ScoreChunk.ChannelData chData = entry.data();

                if (fr >= 0 && fr < frameCount && ch >= 0 && ch < channelCount && !chData.isEmpty()) {
                    // Resolve display name based on channel type
                    String displayName;

                    // For Tempo channel, prefer the frame script from FrameIntervals
                    if (ch == 0 && frameScriptMap.containsKey(fr)) {
                        displayName = frameScriptMap.get(fr);
                    } else {
                        displayName = resolveChannelCellName(dirFile, ch, chData);
                    }

                    data[ch][fr] = new ScoreCellData(
                            chData.castLib(),
                            chData.castMember(),
                            chData.spriteType(),
                            chData.ink(),
                            chData.posX(),
                            chData.posY(),
                            chData.width(),
                            chData.height(),
                            displayName
                    );
                }
            }
        }

        return data;
    }

    /**
     * Builds column names for the score table (frame numbers with optional labels).
     */
    public String[] buildColumnNames(DirectorFile dirFile) {
        ScoreChunk scoreChunk = dirFile.getScoreChunk();
        if (scoreChunk == null) {
            return new String[0];
        }

        int frameCount = scoreChunk.getFrameCount();
        String[] columnNames = new String[frameCount];

        // Get frame labels if available
        FrameLabelsChunk frameLabels = dirFile.getFrameLabelsChunk();
        Map<Integer, String> labelMap = new HashMap<>();
        if (frameLabels != null) {
            for (FrameLabelsChunk.FrameLabel label : frameLabels.labels()) {
                labelMap.put(label.frameNum(), label.label());
            }
        }

        for (int fr = 0; fr < frameCount; fr++) {
            String label = labelMap.get(fr + 1);
            if (label != null) {
                columnNames[fr] = (fr + 1) + " [" + label + "]";
            } else {
                columnNames[fr] = String.valueOf(fr + 1);
            }
        }

        return columnNames;
    }

    private Map<Integer, String> buildFrameScriptMap(DirectorFile dirFile, ScoreChunk scoreChunk, int frameCount) {
        Map<Integer, String> frameScriptMap = new HashMap<>();

        for (ScoreChunk.FrameInterval fi : scoreChunk.frameIntervals()) {
            var primary = fi.primary();
            var secondary = fi.secondary();
            if (secondary != null && primary.channelIndex() == 0) {
                String scriptName = resolveCastMemberByNumber(dirFile, secondary.castLib(), secondary.castMember());
                int startF = primary.startFrame() - 1;
                int endF = primary.endFrame() - 1;
                for (int f = startF; f <= endF && f >= 0 && f < frameCount; f++) {
                    frameScriptMap.put(f, scriptName);
                }
            }
        }

        return frameScriptMap;
    }

    private String resolveChannelCellName(DirectorFile dirFile, int channelIndex, ScoreChunk.ChannelData data) {
        switch (channelIndex) {
            case 0 -> {
                // Tempo channel - scripts come from FrameIntervals, not this data
                return "";
            }
            case 1 -> {
                // Palette channel - castMember is palette index
                int paletteId = data.castMember();
                return PaletteDescriptions.get(paletteId);
            }
            case 2 -> {
                // Transition channel - castMember is transition ID
                int transId = data.castMember();
                if (transId > 0) {
                    return "Trans #" + transId;
                }
                return "";
            }
            case 3, 4 -> {
                // Sound channels - these ARE cast member references
                return resolveCastMemberName(dirFile, data.castLib(), data.castMember());
            }
            case 5 -> {
                // Script channel - these ARE cast member references (frame scripts)
                return resolveCastMemberName(dirFile, data.castLib(), data.castMember());
            }
            default -> {
                // Regular sprite channels - cast member references
                return resolveCastMemberName(dirFile, data.castLib(), data.castMember());
            }
        }
    }

    private String resolveCastMemberName(DirectorFile dirFile, int castLib, int castMember) {
        CastMemberChunk member = dirFile.getCastMemberByIndex(castLib, castMember);

        if (member != null) {
            String name = member.name();
            if (name != null && !name.isEmpty()) {
                return "#" + member.id() + " " + name;
            }
            return "#" + member.id() + " " + member.memberType().getName();
        }

        return "Member #" + castMember;
    }

    private String resolveCastMemberByNumber(DirectorFile dirFile, int castLib, int memberNumber) {
        List<CastChunk> casts = dirFile.getCasts();
        if (casts.isEmpty()) {
            return "Member #" + memberNumber;
        }

        int index = memberNumber - 1;

        for (CastChunk cast : casts) {
            if (index >= 0 && index < cast.memberIds().size()) {
                int chunkId = cast.memberIds().get(index);
                if (chunkId != 0) {
                    Chunk chunk = dirFile.getChunk(chunkId);
                    if (chunk instanceof CastMemberChunk cm) {
                        String name = cm.name();
                        if (name != null && !name.isEmpty()) {
                            return "#" + cm.id() + " " + name;
                        }
                        return "#" + cm.id() + " " + cm.memberType().getName();
                    }
                }
            }
        }

        // Fallback
        Chunk chunk = dirFile.getChunk(memberNumber);
        if (chunk instanceof CastMemberChunk cm) {
            String name = cm.name();
            if (name != null && !name.isEmpty()) {
                return "#" + cm.id() + " " + name;
            }
            return "#" + cm.id() + " " + cm.memberType().getName();
        }

        return "Member #" + memberNumber;
    }
}
