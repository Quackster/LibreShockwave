package com.libreshockwave.player.render;

import java.util.List;

/**
 * Complete snapshot of what to render for a single frame.
 * Immutable data structure for thread-safe rendering.
 */
public record FrameSnapshot(
    int frameNumber,
    int stageWidth,
    int stageHeight,
    int backgroundColor,
    List<RenderSprite> sprites,
    String debugInfo
) {
    /**
     * Create a snapshot from the stage renderer.
     */
    public static FrameSnapshot capture(StageRenderer renderer, int frame, String state) {
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
        String debug = String.format("Frame %d | %s", frame, state);

        return new FrameSnapshot(
            frame,
            renderer.getStageWidth(),
            renderer.getStageHeight(),
            renderer.getBackgroundColor(),
            List.copyOf(sprites),
            debug
        );
    }
}
