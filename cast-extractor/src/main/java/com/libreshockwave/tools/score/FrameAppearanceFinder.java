package com.libreshockwave.tools.score;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.FrameLabelsChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.tools.format.ChannelNames;
import com.libreshockwave.tools.model.FrameAppearance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds where cast members appear in the score.
 */
public class FrameAppearanceFinder {

    /**
     * Find which frames and channels a cast member appears in from the score.
     * Returns a list of frame appearances with channel info.
     */
    public List<FrameAppearance> find(DirectorFile dirFile, int memberId) {
        List<FrameAppearance> appearances = new ArrayList<>();

        if (!dirFile.hasScore()) {
            return appearances;
        }

        ScoreChunk scoreChunk = dirFile.getScoreChunk();
        if (scoreChunk == null || scoreChunk.frameData() == null) {
            return appearances;
        }

        ScoreChunk.ScoreFrameData frameData = scoreChunk.frameData();
        if (frameData.frameChannelData() == null) {
            return appearances;
        }

        // Get frame labels
        FrameLabelsChunk frameLabels = dirFile.getFrameLabelsChunk();
        Map<Integer, String> labelMap = new HashMap<>();
        if (frameLabels != null) {
            for (FrameLabelsChunk.FrameLabel label : frameLabels.labels()) {
                labelMap.put(label.frameNum(), label.label());
            }
        }

        // Find all frames where this member appears
        for (ScoreChunk.FrameChannelEntry entry : frameData.frameChannelData()) {
            ScoreChunk.ChannelData data = entry.data();

            // Resolve the score's castMember index to an actual member and check if it matches
            CastMemberChunk resolvedMember = dirFile.getCastMemberByIndex(data.castLib(), data.castMember());
            if (resolvedMember != null && resolvedMember.id() == memberId) {
                String channelName = ChannelNames.get(entry.channelIndex());
                String frameLabel = labelMap.get(entry.frameIndex());
                appearances.add(new FrameAppearance(
                        entry.frameIndex() + 1, // 1-based frame number
                        entry.channelIndex(),
                        channelName,
                        frameLabel,
                        data.posX(),
                        data.posY()
                ));
            }
        }

        // Sort by frame number, then channel
        appearances.sort((a, b) -> {
            int cmp = Integer.compare(a.frame(), b.frame());
            if (cmp != 0) return cmp;
            return Integer.compare(a.channel(), b.channel());
        });

        return appearances;
    }

    /**
     * Formats a list of frame appearances into a human-readable summary.
     */
    public String format(List<FrameAppearance> appearances) {
        if (appearances.isEmpty()) {
            return "Not used in score";
        }

        StringBuilder sb = new StringBuilder();

        // Group consecutive frames in the same channel
        List<String> ranges = new ArrayList<>();
        int rangeStart = -1;
        int rangeEnd = -1;
        int lastChannel = -1;
        String lastChannelName = null;

        for (FrameAppearance app : appearances) {
            if (lastChannel == app.channel() && rangeEnd + 1 == app.frame()) {
                // Extend current range
                rangeEnd = app.frame();
            } else {
                // Save previous range if any
                if (rangeStart > 0) {
                    ranges.add(formatRange(rangeStart, rangeEnd, lastChannelName));
                }
                // Start new range
                rangeStart = app.frame();
                rangeEnd = app.frame();
                lastChannel = app.channel();
                lastChannelName = app.channelName();
            }
        }
        // Don't forget the last range
        if (rangeStart > 0) {
            ranges.add(formatRange(rangeStart, rangeEnd, lastChannelName));
        }

        // Format output
        if (ranges.size() <= 5) {
            sb.append(String.join(", ", ranges));
        } else {
            // Show first few and count
            sb.append(String.join(", ", ranges.subList(0, 3)));
            sb.append(" ... and ").append(ranges.size() - 3).append(" more");
        }

        return sb.toString();
    }

    private String formatRange(int start, int end, String channelName) {
        if (start == end) {
            return "Frame " + start + " (" + channelName + ")";
        } else {
            return "Frames " + start + "-" + end + " (" + channelName + ")";
        }
    }
}
