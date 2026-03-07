package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.Player;

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
     * Create a snapshot with baked bitmaps for all sprite types.
     * SpriteBaker handles BITMAP, TEXT, SHAPE → pre-rendered pixels.
     */
    public static FrameSnapshot capture(StageRenderer renderer, int frame, String state,
                                         SpriteBaker baker, Player player) {
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
        String debug = String.format("Frame %d | %s", frame, state);

        List<RenderSprite> baked = baker.bakeSprites(sprites);

        // Store baked sprites for ink-aware hit testing
        renderer.setLastBakedSprites(baked);

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

    /**
     * Render this snapshot to a Bitmap using the specified renderer.
     * <ul>
     *   <li>{@link RenderType#AWT} — uses Java2D Graphics2D (desktop)</li>
     *   <li>{@link RenderType#SOFTWARE} — uses pure int[] compositing (WASM-safe)</li>
     * </ul>
     */
    public Bitmap renderFrame(RenderType type) {
        return switch (type) {
            case AWT -> AwtFrameRenderer.renderFrame(this, stageWidth, stageHeight);
            case SOFTWARE -> SoftwareFrameRenderer.renderFrame(this, stageWidth, stageHeight);
        };
    }

    /**
     * Render this snapshot to a Bitmap using software compositing.
     * This is the recommended rendering method — no AWT dependency.
     */
    public Bitmap renderFrame() {
        return renderFrame(RenderType.SOFTWARE);
    }
}
