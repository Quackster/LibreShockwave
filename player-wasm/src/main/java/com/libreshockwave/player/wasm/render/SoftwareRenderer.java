package com.libreshockwave.player.wasm.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Software renderer that draws Director frames into an RGBA byte[] buffer.
 * No browser/DOM dependencies - pure computation suitable for standard WASM.
 */
public class SoftwareRenderer {

    private final Player player;
    private final int width;
    private final int height;
    private final byte[] frameBuffer; // RGBA, 4 bytes per pixel

    // Cache decoded bitmaps by cast member ID
    private final Map<Integer, CachedBitmap> bitmapCache = new HashMap<>();

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

        switch (sprite.getType()) {
            case BITMAP -> drawBitmap(sprite, x, y, w, h);
            case SHAPE -> drawShape(sprite, x, y, w, h);
            case TEXT, BUTTON -> drawPlaceholder(x, y, w, h);
            default -> drawPlaceholder(x, y, w, h);
        }
    }

    private void drawBitmap(RenderSprite sprite, int x, int y, int w, int h) {
        CastMemberChunk member = sprite.getCastMember();
        if (member == null) {
            drawPlaceholder(x, y, w, h);
            return;
        }

        CachedBitmap cached = getCachedBitmap(member);
        if (cached == null) {
            drawPlaceholder(x, y, w, h);
            return;
        }

        int srcW = cached.width;
        int srcH = cached.height;
        int dstW = w > 0 ? w : srcW;
        int dstH = h > 0 ? h : srcH;

        for (int dy = 0; dy < dstH; dy++) {
            int dstY = y + dy;
            if (dstY < 0 || dstY >= height) continue;

            int srcY = dstH > 0 ? dy * srcH / dstH : dy;
            if (srcY >= srcH) continue;

            for (int dx = 0; dx < dstW; dx++) {
                int dstX = x + dx;
                if (dstX < 0 || dstX >= width) continue;

                int srcX = dstW > 0 ? dx * srcW / dstW : dx;
                if (srcX >= srcW) continue;

                int srcOff = (srcY * srcW + srcX) * 4;
                int dstOff = (dstY * this.width + dstX) * 4;

                int alpha = cached.rgba[srcOff + 3] & 0xFF;
                if (alpha == 0) continue;

                if (alpha == 255) {
                    frameBuffer[dstOff] = cached.rgba[srcOff];
                    frameBuffer[dstOff + 1] = cached.rgba[srcOff + 1];
                    frameBuffer[dstOff + 2] = cached.rgba[srcOff + 2];
                    frameBuffer[dstOff + 3] = (byte) 0xFF;
                } else {
                    // Alpha blend
                    int sr = cached.rgba[srcOff] & 0xFF;
                    int sg = cached.rgba[srcOff + 1] & 0xFF;
                    int sb = cached.rgba[srcOff + 2] & 0xFF;
                    int dr = frameBuffer[dstOff] & 0xFF;
                    int dg = frameBuffer[dstOff + 1] & 0xFF;
                    int db = frameBuffer[dstOff + 2] & 0xFF;

                    frameBuffer[dstOff] = (byte) (sr + (dr * (255 - alpha)) / 255);
                    frameBuffer[dstOff + 1] = (byte) (sg + (dg * (255 - alpha)) / 255);
                    frameBuffer[dstOff + 2] = (byte) (sb + (db * (255 - alpha)) / 255);
                    frameBuffer[dstOff + 3] = (byte) 0xFF;
                }
            }
        }
    }

    private void drawShape(RenderSprite sprite, int x, int y, int w, int h) {
        int fc = sprite.getForeColor();
        byte r = (byte) ((fc >> 16) & 0xFF);
        byte g = (byte) ((fc >> 8) & 0xFF);
        byte b = (byte) (fc & 0xFF);

        fillRect(x, y, w > 0 ? w : 50, h > 0 ? h : 50, r, g, b, (byte) 0xFF);
    }

    private void drawPlaceholder(int x, int y, int w, int h) {
        fillRect(x, y, w > 0 ? w : 50, h > 0 ? h : 50,
                (byte) 200, (byte) 200, (byte) 200, (byte) 128);
    }

    private void fillRect(int x, int y, int w, int h, byte r, byte g, byte b, byte a) {
        for (int dy = 0; dy < h; dy++) {
            int py = y + dy;
            if (py < 0 || py >= height) continue;
            for (int dx = 0; dx < w; dx++) {
                int px = x + dx;
                if (px < 0 || px >= width) continue;
                int off = (py * width + px) * 4;
                frameBuffer[off] = r;
                frameBuffer[off + 1] = g;
                frameBuffer[off + 2] = b;
                frameBuffer[off + 3] = a;
            }
        }
    }

    private CachedBitmap getCachedBitmap(CastMemberChunk member) {
        int id = member.id();
        if (bitmapCache.containsKey(id)) {
            return bitmapCache.get(id);
        }

        if (player.getFile() == null) return null;

        Optional<Bitmap> bitmap = player.getFile().decodeBitmap(member);
        if (bitmap.isPresent()) {
            Bitmap bmp = bitmap.get();
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            int[] argbPixels = bmp.getPixels();

            // Convert ARGB int[] to RGBA byte[]
            byte[] rgba = new byte[bw * bh * 4];
            for (int i = 0; i < argbPixels.length; i++) {
                int argb = argbPixels[i];
                int off = i * 4;
                rgba[off] = (byte) ((argb >> 16) & 0xFF);     // R
                rgba[off + 1] = (byte) ((argb >> 8) & 0xFF);  // G
                rgba[off + 2] = (byte) (argb & 0xFF);          // B
                rgba[off + 3] = (byte) ((argb >> 24) & 0xFF);  // A
            }

            CachedBitmap cached = new CachedBitmap(rgba, bw, bh);
            bitmapCache.put(id, cached);
            return cached;
        }

        bitmapCache.put(id, null);
        return null;
    }

    private static class CachedBitmap {
        final byte[] rgba;
        final int width;
        final int height;

        CachedBitmap(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }
}
