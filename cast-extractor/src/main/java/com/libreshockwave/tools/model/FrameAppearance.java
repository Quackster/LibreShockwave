package com.libreshockwave.tools.model;

/**
 * Represents a cast member's appearance in a specific frame and channel of the score.
 */
public record FrameAppearance(
        int frame,
        int channel,
        String channelName,
        String frameLabel,
        int posX,
        int posY
) {
}
