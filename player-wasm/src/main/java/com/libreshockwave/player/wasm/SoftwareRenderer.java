package com.libreshockwave.player.wasm;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.SoftwareFrameRenderer;

/**
 * WASM-specific renderer that wraps the shared SoftwareFrameRenderer with
 * caching and ARGB→RGBA byte[] conversion for browser canvas ImageData.
 */
public class SoftwareRenderer {

    private int stageWidth;
    private int stageHeight;

    // Final RGBA byte[] output for JS
    private byte[] rgba;

    // Cache: skip recomposite if frame hasn't changed
    private int lastFrame = -1;
    private int lastCastRevision = -1;
    private int lastSpriteRevision = -1;

    public SoftwareRenderer(int stageWidth, int stageHeight) {
        this.stageWidth = stageWidth;
        this.stageHeight = stageHeight;
        int pixelCount = stageWidth * stageHeight;
        this.rgba = new byte[pixelCount * 4];
    }

    /**
     * Render a FrameSnapshot into an RGBA byte[] buffer.
     * Returns the cached buffer if the frame and cast revision haven't changed.
     */
    public byte[] render(FrameSnapshot snapshot, int castRevision, int spriteRevision) {
        int frame = snapshot.frameNumber();

        // Cache hit — return previously composited buffer
        if (frame == lastFrame && castRevision == lastCastRevision
                && spriteRevision == lastSpriteRevision) {
            return rgba;
        }

        lastFrame = frame;
        lastCastRevision = castRevision;
        lastSpriteRevision = spriteRevision;

        // Resize buffer if stage dimensions changed
        if (snapshot.stageWidth() != stageWidth || snapshot.stageHeight() != stageHeight) {
            stageWidth = snapshot.stageWidth();
            stageHeight = snapshot.stageHeight();
            rgba = new byte[stageWidth * stageHeight * 4];
        }

        // Delegate compositing to the shared renderer
        Bitmap result = SoftwareFrameRenderer.renderFrame(snapshot, stageWidth, stageHeight);
        int[] argb = result.getPixels();
        int pixelCount = stageWidth * stageHeight;

        // Convert ARGB int[] → RGBA byte[]
        for (int i = 0; i < pixelCount; i++) {
            int px = argb[i];
            int off = i * 4;
            rgba[off]     = (byte) ((px >> 16) & 0xFF); // R
            rgba[off + 1] = (byte) ((px >> 8) & 0xFF);  // G
            rgba[off + 2] = (byte) (px & 0xFF);          // B
            rgba[off + 3] = (byte) ((px >> 24) & 0xFF); // A
        }

        return rgba;
    }

    /** Force recomposite on next render call. */
    public void invalidate() {
        lastFrame = -1;
        lastCastRevision = -1;
    }

    public int getWidth() { return stageWidth; }
    public int getHeight() { return stageHeight; }
}
