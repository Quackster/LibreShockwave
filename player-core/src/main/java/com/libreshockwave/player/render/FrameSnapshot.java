package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.Player;
import com.libreshockwave.vm.builtin.WindowProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    Bitmap stageImage,
    Map<String, WindowProvider.WindowBuffer> windowBuffers
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
            renderer.hasStageImage() ? renderer.getStageImage() : null,
            Collections.emptyMap()
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

        // Capture window buffers for rendering
        Map<String, WindowProvider.WindowBuffer> windowBuffers = Collections.emptyMap();
        WindowManager wm = player.getWindowManager();
        if (wm != null && wm.hasWindows()) {
            windowBuffers = wm.getWindowBuffers();
        }

        return new FrameSnapshot(
            frame,
            renderer.getStageWidth(),
            renderer.getStageHeight(),
            renderer.getBackgroundColor(),
            List.copyOf(baked),
            debug,
            renderer.hasStageImage() ? renderer.getStageImage() : null,
            windowBuffers
        );
    }
}
