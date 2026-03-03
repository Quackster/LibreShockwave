package com.libreshockwave.player.wasm.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import java.util.HashMap;
import java.util.Map;

/**
 * Software renderer that draws Director frames into an RGBA byte[] buffer.
 * No browser/DOM dependencies - pure computation suitable for standard WASM.
 */
public class SoftwareRenderer {

    private final Player player;
    private final int width;
    private final int height;
    private final byte[] frameBuffer; // RGBA, 4 bytes per pixel

    // Cache decoded bitmaps by cast member ID (fallback when baked bitmap not in snapshot).
    // Null values are sentinels for members that failed to decode, to avoid retrying.
    private final Map<Integer, Bitmap> bitmapCache = new HashMap<>();

    public SoftwareRenderer(Player player, int width, int height) {
        this.player = player;
        this.width = width;
        this.height = height;
        this.frameBuffer = new byte[width * height * 4];
    }

    public byte[] getFrameBuffer() {
        return frameBuffer;
    }

    /**
     * Render the current frame into the RGBA frame buffer.
     */
    public void render() {
        FrameSnapshot snapshot = player.getFrameSnapshot();

        // Clear with background color
        int bg = snapshot.backgroundColor();
        byte bgR = (byte) ((bg >> 16) & 0xFF);
        byte bgG = (byte) ((bg >> 8) & 0xFF);
        byte bgB = (byte) (bg & 0xFF);

        for (int i = 0; i < width * height; i++) {
            int off = i * 4;
            frameBuffer[off] = bgR;
            frameBuffer[off + 1] = bgG;
            frameBuffer[off + 2] = bgB;
            frameBuffer[off + 3] = (byte) 0xFF;
        }

        // Draw stage image if scripts have drawn on it (loading bars, etc.)
        if (snapshot.stageImage() != null) {
            blitArgb(snapshot.stageImage().getPixels(),
                    snapshot.stageImage().getWidth(), snapshot.stageImage().getHeight(),
                    0, 0, width, height, 100);
        }

        // Draw all visible sprites
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!sprite.isVisible()) continue;
            drawSprite(sprite);
        }
    }

    private void drawSprite(RenderSprite sprite) {
        int x = sprite.getX();
        int y = sprite.getY();
        int w = sprite.getWidth();
        int h = sprite.getHeight();
        int blend = sprite.getBlend();

        // All sprite types arrive pre-baked from SpriteBaker
        Bitmap baked = sprite.getBakedBitmap();
        if (baked != null) {
            blitArgb(baked.getPixels(), baked.getWidth(), baked.getHeight(), x, y, w, h, blend);
            return;
        }

        // Fallback: decode BITMAP from cast member directly (edge case)
        if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
            CastMemberChunk member = sprite.getCastMember();
            if (member != null) {
                Bitmap cached = getCachedBitmap(member);
                if (cached != null) {
                    blitArgb(cached.getPixels(), cached.getWidth(), cached.getHeight(), x, y, w, h, blend);
                }
            }
        }
        // No placeholder: matches Swing's StagePanel which skips sprites without baked bitmaps
    }

    /**
     * Blit an ARGB int[] bitmap into the frame buffer with blend (opacity).
     * Matches Swing's AlphaComposite.SRC_OVER with blend/100 factor.
     * @param blend 0-100 opacity percentage (100 = fully opaque)
     */
    private void blitArgb(int[] argbPixels, int srcW, int srcH, int x, int y, int w, int h, int blend) {
        int dstW = w > 0 ? w : srcW;
        int dstH = h > 0 ? h : srcH;

        for (int dy = 0; dy < dstH; dy++) {
            int dstY = y + dy;
            if (dstY < 0 || dstY >= height) continue;

            int srcY = dy * srcH / dstH;
            if (srcY >= srcH) continue;

            for (int dx = 0; dx < dstW; dx++) {
                int dstX = x + dx;
                if (dstX < 0 || dstX >= width) continue;

                int srcX = dx * srcW / dstW;
                if (srcX >= srcW) continue;

                int argb = argbPixels[srcY * srcW + srcX];
                int alpha = (argb >> 24) & 0xFF;
                if (alpha == 0) continue;

                // Apply sprite blend (opacity): Swing does AlphaComposite(SRC_OVER, blend/100)
                if (blend >= 0 && blend < 100) {
                    alpha = alpha * blend / 100;
                    if (alpha == 0) continue;
                }

                int dstOff = (dstY * this.width + dstX) * 4;
                if (alpha == 255) {
                    frameBuffer[dstOff]     = (byte) ((argb >> 16) & 0xFF);
                    frameBuffer[dstOff + 1] = (byte) ((argb >> 8) & 0xFF);
                    frameBuffer[dstOff + 2] = (byte) (argb & 0xFF);
                    frameBuffer[dstOff + 3] = (byte) 0xFF;
                } else {
                    int sr = (argb >> 16) & 0xFF;
                    int sg = (argb >> 8) & 0xFF;
                    int sb = argb & 0xFF;
                    int dr = frameBuffer[dstOff] & 0xFF;
                    int dg = frameBuffer[dstOff + 1] & 0xFF;
                    int db = frameBuffer[dstOff + 2] & 0xFF;
                    frameBuffer[dstOff]     = (byte) ((sr * alpha + dr * (255 - alpha)) / 255);
                    frameBuffer[dstOff + 1] = (byte) ((sg * alpha + dg * (255 - alpha)) / 255);
                    frameBuffer[dstOff + 2] = (byte) ((sb * alpha + db * (255 - alpha)) / 255);
                    frameBuffer[dstOff + 3] = (byte) 0xFF;
                }
            }
        }
    }

    private Bitmap getCachedBitmap(CastMemberChunk member) {
        int id = member.id();
        if (bitmapCache.containsKey(id)) {
            return bitmapCache.get(id);
        }

        // Use player.decodeBitmap() which handles cross-file decoding (external casts)
        Bitmap result = player.decodeBitmap(member).orElse(null);
        bitmapCache.put(id, result);
        return result;
    }
}
