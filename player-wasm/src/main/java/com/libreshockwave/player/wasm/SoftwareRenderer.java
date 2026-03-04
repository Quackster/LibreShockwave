package com.libreshockwave.player.wasm;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

/**
 * Pure-Java software renderer that composites a FrameSnapshot into an RGBA byte[] buffer.
 * No AWT dependency — works in WASM via TeaVM.
 *
 * The output is a flat RGBA byte array (4 bytes per pixel, row-major) suitable for
 * direct use as ImageData in a browser canvas.
 */
public class SoftwareRenderer {

    private int stageWidth;
    private int stageHeight;

    // Intermediate ARGB int[] for compositing (same format as Bitmap pixels)
    private int[] argb;

    // Final RGBA byte[] output for JS
    private byte[] rgba;

    // Cache: skip recomposite if frame hasn't changed
    private int lastFrame = -1;
    private int lastCastRevision = -1;

    public SoftwareRenderer(int stageWidth, int stageHeight) {
        this.stageWidth = stageWidth;
        this.stageHeight = stageHeight;
        int pixelCount = stageWidth * stageHeight;
        this.argb = new int[pixelCount];
        this.rgba = new byte[pixelCount * 4];
    }

    /**
     * Render a FrameSnapshot into an RGBA byte[] buffer.
     * Returns the cached buffer if the frame and cast revision haven't changed.
     */
    public byte[] render(FrameSnapshot snapshot, int castRevision) {
        int frame = snapshot.frameNumber();

        // Cache hit — return previously composited buffer
        if (frame == lastFrame && castRevision == lastCastRevision) {
            return rgba;
        }

        lastFrame = frame;
        lastCastRevision = castRevision;

        int pixelCount = stageWidth * stageHeight;

        // Resize buffers if stage dimensions changed
        if (snapshot.stageWidth() != stageWidth || snapshot.stageHeight() != stageHeight) {
            stageWidth = snapshot.stageWidth();
            stageHeight = snapshot.stageHeight();
            pixelCount = stageWidth * stageHeight;
            argb = new int[pixelCount];
            rgba = new byte[pixelCount * 4];
        }

        // 1. Clear to background color (opaque)
        int bg = snapshot.backgroundColor() | 0xFF000000;
        for (int i = 0; i < pixelCount; i++) {
            argb[i] = bg;
        }

        // 2. Blit stage image if present (script-drawn content)
        Bitmap stageImage = snapshot.stageImage();
        if (stageImage != null) {
            blitBitmap(stageImage.getPixels(), stageImage.getWidth(), stageImage.getHeight(),
                    0, 0, 100);
        }

        // 3. Composite each visible sprite in order
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!sprite.isVisible()) continue;

            Bitmap baked = sprite.getBakedBitmap();
            if (baked == null) continue;
            if (baked.getWidth() <= 0 || baked.getHeight() <= 0) continue;
            if (baked.getPixels() == null || baked.getPixels().length == 0) continue;

            int sx = sprite.getX();
            int sy = sprite.getY();
            int sw = sprite.getWidth() > 0 ? sprite.getWidth() : baked.getWidth();
            int sh = sprite.getHeight() > 0 ? sprite.getHeight() : baked.getHeight();
            int blend = sprite.getBlend();

            if (sw == baked.getWidth() && sh == baked.getHeight()) {
                blitBitmap(baked.getPixels(), baked.getWidth(), baked.getHeight(),
                        sx, sy, blend);
            } else {
                blitBitmapScaled(baked.getPixels(), baked.getWidth(), baked.getHeight(),
                        sx, sy, sw, sh, blend);
            }
        }

        // 4. Convert ARGB int[] → RGBA byte[]
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

    // ========================================================================
    // Alpha blitting (unscaled)
    // ========================================================================

    private void blitBitmap(int[] srcPixels, int srcW, int srcH,
                            int dstX, int dstY, int blend) {
        if (srcPixels == null || srcW <= 0 || srcH <= 0) return;
        if (srcPixels.length < srcW * srcH) return;

        // Clip source rect to stage bounds
        int sx0 = Math.max(0, -dstX);
        int sy0 = Math.max(0, -dstY);
        int sx1 = Math.min(srcW, stageWidth - dstX);
        int sy1 = Math.min(srcH, stageHeight - dstY);
        if (sx0 >= sx1 || sy0 >= sy1) return;

        int argbLen = argb.length;

        for (int sy = sy0; sy < sy1; sy++) {
            for (int sx = sx0; sx < sx1; sx++) {
                int srcIdx = sy * srcW + sx;
                int src = srcPixels[srcIdx];
                int srcA = (src >> 24) & 0xFF;
                if (srcA == 0) continue;

                // Apply blend/opacity
                if (blend < 100) {
                    srcA = (srcA * blend) / 100;
                    if (srcA == 0) continue;
                }

                int dstIdx = (dstY + sy) * stageWidth + (dstX + sx);
                if (dstIdx < 0 || dstIdx >= argbLen) continue;

                if (srcA >= 255) {
                    // Fully opaque — just copy
                    argb[dstIdx] = src | 0xFF000000;
                } else {
                    // Alpha composite: out = src + dst * (1 - srcA/255)
                    alphaComposite(dstIdx, src, srcA);
                }
            }
        }
    }

    // ========================================================================
    // Alpha blitting (scaled — nearest neighbor)
    // ========================================================================

    private void blitBitmapScaled(int[] srcPixels, int srcW, int srcH,
                                  int dstX, int dstY, int dstW, int dstH, int blend) {
        if (srcPixels == null || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return;
        if (srcPixels.length < srcW * srcH) return;

        // Clip destination rect to stage bounds
        int dx0 = Math.max(0, dstX);
        int dy0 = Math.max(0, dstY);
        int dx1 = Math.min(stageWidth, dstX + dstW);
        int dy1 = Math.min(stageHeight, dstY + dstH);
        if (dx0 >= dx1 || dy0 >= dy1) return;

        int srcLen = srcPixels.length;
        int argbLen = argb.length;

        for (int dy = dy0; dy < dy1; dy++) {
            // Nearest neighbor: map destination pixel to source pixel
            int srcY = ((dy - dstY) * srcH) / dstH;
            if (srcY < 0 || srcY >= srcH) continue;

            for (int dx = dx0; dx < dx1; dx++) {
                int srcX = ((dx - dstX) * srcW) / dstW;
                if (srcX < 0 || srcX >= srcW) continue;

                int srcIdx = srcY * srcW + srcX;
                if (srcIdx < 0 || srcIdx >= srcLen) continue;

                int src = srcPixels[srcIdx];
                int srcA = (src >> 24) & 0xFF;
                if (srcA == 0) continue;

                if (blend < 100) {
                    srcA = (srcA * blend) / 100;
                    if (srcA == 0) continue;
                }

                int dstIdx = dy * stageWidth + dx;
                if (dstIdx < 0 || dstIdx >= argbLen) continue;

                if (srcA >= 255) {
                    argb[dstIdx] = src | 0xFF000000;
                } else {
                    alphaComposite(dstIdx, src, srcA);
                }
            }
        }
    }

    // ========================================================================
    // Alpha composite helper
    // ========================================================================

    private void alphaComposite(int dstIdx, int src, int srcA) {
        if (dstIdx < 0 || dstIdx >= argb.length) return;
        int dst = argb[dstIdx];
        int dstA = (dst >> 24) & 0xFF;
        int invA = 255 - srcA;

        int outA = srcA + (dstA * invA / 255);
        if (outA == 0) {
            argb[dstIdx] = 0;
            return;
        }

        int srcR = (src >> 16) & 0xFF;
        int srcG = (src >> 8) & 0xFF;
        int srcB = src & 0xFF;
        int dstR = (dst >> 16) & 0xFF;
        int dstG = (dst >> 8) & 0xFF;
        int dstB = dst & 0xFF;

        int outR = (srcR * srcA + dstR * dstA * invA / 255) / outA;
        int outG = (srcG * srcA + dstG * dstA * invA / 255) / outA;
        int outB = (srcB * srcA + dstB * dstA * invA / 255) / outA;

        argb[dstIdx] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
    }
}
