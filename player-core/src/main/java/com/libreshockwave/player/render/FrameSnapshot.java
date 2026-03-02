package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.Player;

import java.util.ArrayList;
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
    String debugInfo,
    Bitmap stageImage
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
            debug,
            renderer.hasStageImage() ? renderer.getStageImage() : null
        );
    }

    /**
     * Create a snapshot with ink-processed (baked) bitmaps.
     * Each BITMAP sprite gets its decoded+ink-processed bitmap attached via {@link RenderSprite#withBakedBitmap}.
     */
    public static FrameSnapshot capture(StageRenderer renderer, int frame, String state,
                                         BitmapCache cache, Player player) {
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
        String debug = String.format("Frame %d | %s", frame, state);

        List<RenderSprite> baked = new ArrayList<>(sprites.size());
        for (RenderSprite s : sprites) {
            if (s.getType() == RenderSprite.SpriteType.BITMAP) {
                Bitmap b = null;
                if (s.getCastMember() != null) {
                    b = cache.getProcessed(s.getCastMember(), s.getInk(), s.getBackColor(), player);
                }
                if (b == null && s.getDynamicMember() != null) {
                    b = cache.getProcessedDynamic(s.getDynamicMember(), s.getInk(), s.getBackColor());
                }
                baked.add(s.withBakedBitmap(b));
            } else {
                baked.add(s);
            }
        }

        return new FrameSnapshot(
            frame,
            renderer.getStageWidth(),
            renderer.getStageHeight(),
            renderer.getBackgroundColor(),
            List.copyOf(baked),
            debug,
            renderer.hasStageImage() ? renderer.getStageImage() : null
        );
    }
}
