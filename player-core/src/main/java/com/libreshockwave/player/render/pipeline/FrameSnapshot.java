package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.RenderType;
import com.libreshockwave.player.render.output.AwtFrameRenderer;
import com.libreshockwave.player.render.output.SoftwareFrameRenderer;

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
