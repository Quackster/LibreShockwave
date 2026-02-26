package com.libreshockwave.player.wasm.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.TextChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Exports per-sprite data as JSON for JS-side Canvas 2D rendering.
 * Replaces full-frame SoftwareRenderer with individual sprite metadata
 * so JavaScript can render with drawImage, fillText, fillRect.
 */
public class SpriteDataExporter {

    private final Player player;

    // Cache decoded bitmaps by cast member ID (same pattern as SoftwareRenderer)
    private final Map<Integer, CachedBitmap> bitmapCache = new HashMap<>();

    public SpriteDataExporter(Player player) {
        this.player = player;
    }

    /**
     * Export current frame data as JSON.
     * Returns: {bg, frame, frameCount, sprites: [{channel, type, x, y, w, h, memberId, ...}]}
     */
    public String exportFrameData() {
        FrameSnapshot snapshot = player.getFrameSnapshot();

        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"bg\":").append(snapshot.backgroundColor());
        sb.append(",\"frame\":").append(snapshot.frameNumber());
        sb.append(",\"frameCount\":").append(player.getFrameCount());
        sb.append(",\"sprites\":[");

        boolean first = true;
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!first) sb.append(',');
            first = false;

            sb.append("{\"channel\":").append(sprite.getChannel());
            sb.append(",\"type\":\"").append(sprite.getType().name()).append('"');
            sb.append(",\"x\":").append(sprite.getX());
            sb.append(",\"y\":").append(sprite.getY());
            sb.append(",\"w\":").append(sprite.getWidth());
            sb.append(",\"h\":").append(sprite.getHeight());
            sb.append(",\"visible\":").append(sprite.isVisible());
            sb.append(",\"memberId\":").append(sprite.getCastMemberId());
            sb.append(",\"foreColor\":").append(sprite.getForeColor());
            sb.append(",\"backColor\":").append(sprite.getBackColor());
            sb.append(",\"ink\":").append(sprite.getInk());

            // For text/button sprites, include text content
            if ((sprite.getType() == RenderSprite.SpriteType.TEXT ||
                 sprite.getType() == RenderSprite.SpriteType.BUTTON) &&
                sprite.getCastMember() != null) {
                String text = getTextContent(sprite.getCastMember());
                if (text != null && !text.isEmpty()) {
                    sb.append(",\"textContent\":\"").append(escapeJson(text)).append('"');
                }
                // Include font info from the cast member
                sb.append(",\"fontSize\":").append(12); // Default
                sb.append(",\"fontStyle\":\"normal\"");
            }

            sb.append('}');
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Get RGBA byte array for a bitmap cast member.
     * Returns null if the member is not a bitmap or cannot be decoded.
     */
    public byte[] getBitmapRGBA(int memberId) {
        CachedBitmap cached = getCachedBitmap(memberId);
        return cached != null ? cached.rgba : null;
    }

    public int getBitmapWidth(int memberId) {
        CachedBitmap cached = getCachedBitmap(memberId);
        return cached != null ? cached.width : 0;
    }

    public int getBitmapHeight(int memberId) {
        CachedBitmap cached = getCachedBitmap(memberId);
        return cached != null ? cached.height : 0;
    }

    private CachedBitmap getCachedBitmap(int memberId) {
        if (bitmapCache.containsKey(memberId)) {
            return bitmapCache.get(memberId);
        }

        if (player.getFile() == null) return null;

        // Find the CastMemberChunk by ID
        CastMemberChunk member = null;
        for (CastMemberChunk m : player.getFile().getCastMembers()) {
            if (m.id() == memberId) {
                member = m;
                break;
            }
        }

        if (member == null) {
            bitmapCache.put(memberId, null);
            return null;
        }

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
            bitmapCache.put(memberId, cached);
            return cached;
        }

        bitmapCache.put(memberId, null);
        return null;
    }

    private String getTextContent(CastMemberChunk memberChunk) {
        if (memberChunk == null || player.getFile() == null) return "";

        // Read text from STXT chunk (same pattern as CastMember.loadText())
        KeyTableChunk keyTable = player.getFile().getKeyTable();
        if (keyTable != null) {
            int stxtFourcc = ChunkType.STXT.getFourCC();
            var entry = keyTable.findEntry(memberChunk.id(), stxtFourcc);
            if (entry != null) {
                var textChunk = player.getFile().getChunk(entry.sectionId(), TextChunk.class);
                if (textChunk.isPresent()) {
                    return textChunk.get().text();
                }
            }
        }

        // Fallback
        var textChunk = player.getFile().getChunk(memberChunk.id(), TextChunk.class);
        return textChunk.isPresent() ? textChunk.get().text() : "";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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
