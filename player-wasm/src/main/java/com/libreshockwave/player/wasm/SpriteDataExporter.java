package com.libreshockwave.player.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.TextChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import java.util.HashMap;
import java.util.Map;

/**
 * Exports per-sprite data as JSON for JS-side Canvas 2D rendering.
 * Caches baked bitmaps by memberId for getBitmapData() retrieval.
 */
public class SpriteDataExporter {

    private static final int STAGE_IMAGE_ID = -1;

    private final Player player;
    private int diagCount = 0;

    // Cache decoded bitmaps by cast member ID (keyed by castMember.id() chunk ID)
    private final Map<Integer, CachedBitmap> bitmapCache = new HashMap<>();

    // Member chunk lookup built from the most recent snapshot
    private final Map<Integer, CastMemberChunk> recentMembers = new HashMap<>();

    public SpriteDataExporter(Player player) {
        this.player = player;
    }

    /**
     * Export current frame data as JSON.
     * Also caches baked bitmaps from the snapshot so getBitmapRGBA() can serve them.
     */
    public String exportFrameData() {
        FrameSnapshot snapshot = player.getFrameSnapshot();

        // Diagnostic: log registry state after prepareMovie has run (frame >= 5)
        if (diagCount < 3 && snapshot.frameNumber() >= 5) {
            diagCount++;
            var registry = player.getStageRenderer().getSpriteRegistry();
            var allSprites = registry.getAll();
            var dynamicSprites = registry.getDynamicSprites();
            System.out.println("[WASM-diag] frame=" + snapshot.frameNumber() +
                    " snapshot.sprites=" + snapshot.sprites().size() +
                    " registry.all=" + allSprites.size() +
                    " registry.dynamic=" + dynamicSprites.size());
            if (!allSprites.isEmpty()) {
                for (var e : allSprites.entrySet()) {
                    var s = e.getValue();
                    System.out.println("[WASM-diag]   ch" + e.getKey() +
                            " dynamic=" + s.isDynamic() +
                            " hasDynMember=" + s.hasDynamicMember() +
                            " puppet=" + s.isPuppet());
                }
            }
        }

        // Index members and cache baked bitmaps from snapshot
        recentMembers.clear();
        for (RenderSprite sprite : snapshot.sprites()) {
            int mid = sprite.getCastMemberId();
            if (mid <= 0) continue;

            if (sprite.getCastMember() != null) {
                recentMembers.put(mid, sprite.getCastMember());
            }

            if (!bitmapCache.containsKey(mid)) {
                Bitmap baked = sprite.getBakedBitmap();
                if (baked != null) {
                    bitmapCache.put(mid, toCachedBitmap(baked));
                }
            }
        }

        // Cache stage image if present
        if (snapshot.stageImage() != null) {
            if (!bitmapCache.containsKey(STAGE_IMAGE_ID)) {
                bitmapCache.put(STAGE_IMAGE_ID, toCachedBitmap(snapshot.stageImage()));
            }
        }

        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"bg\":").append(snapshot.backgroundColor());
        sb.append(",\"frame\":").append(snapshot.frameNumber());
        sb.append(",\"frameCount\":").append(player.getFrameCount());
        if (snapshot.stageImage() != null) {
            sb.append(",\"stageImageId\":").append(STAGE_IMAGE_ID);
        }
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
            sb.append(",\"blend\":").append(sprite.getBlend());

            boolean hasBaked = sprite.getBakedBitmap() != null && sprite.getCastMemberId() > 0;
            sb.append(",\"hasBaked\":").append(hasBaked);

            if ((sprite.getType() == RenderSprite.SpriteType.TEXT ||
                 sprite.getType() == RenderSprite.SpriteType.BUTTON) &&
                sprite.getCastMember() != null) {
                String text = getTextContent(sprite.getCastMember());
                if (text != null && !text.isEmpty()) {
                    sb.append(",\"textContent\":\"").append(escapeJson(text)).append('"');
                }
                sb.append(",\"fontSize\":").append(12);
                sb.append(",\"fontStyle\":\"normal\"");
            }

            sb.append('}');
        }

        sb.append("]}");
        return sb.toString();
    }

    /**
     * Clear all cached bitmaps (called when a new external cast is loaded).
     */
    public void clearBitmapCache() {
        bitmapCache.clear();
        recentMembers.clear();
    }

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

        CastMemberChunk member = recentMembers.get(memberId);
        if (member == null) {
            member = findMemberInAllFiles(memberId);
        }

        if (member == null) {
            bitmapCache.put(memberId, null);
            return null;
        }

        CachedBitmap cached = player.decodeBitmap(member)
                .map(SpriteDataExporter::toCachedBitmap)
                .orElse(null);
        bitmapCache.put(memberId, cached);
        return cached;
    }

    private CastMemberChunk findMemberInAllFiles(int memberId) {
        if (player.getFile() != null) {
            for (CastMemberChunk m : player.getFile().getCastMembers()) {
                if (m.id().value() == memberId) return m;
            }
        }

        if (player.getCastLibManager() != null) {
            for (CastLib castLib : player.getCastLibManager().getCastLibs().values()) {
                DirectorFile src = castLib.getSourceFile();
                if (src != null) {
                    for (CastMemberChunk m : src.getCastMembers()) {
                        if (m.id().value() == memberId) return m;
                    }
                }
            }
        }

        return null;
    }

    private static CachedBitmap toCachedBitmap(Bitmap bmp) {
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        int[] argbPixels = bmp.getPixels();
        byte[] rgba = new byte[bw * bh * 4];
        for (int i = 0; i < argbPixels.length; i++) {
            int argb = argbPixels[i];
            int off = i * 4;
            rgba[off]     = (byte) ((argb >> 16) & 0xFF);  // R
            rgba[off + 1] = (byte) ((argb >> 8) & 0xFF);   // G
            rgba[off + 2] = (byte) (argb & 0xFF);           // B
            rgba[off + 3] = (byte) ((argb >> 24) & 0xFF);  // A
        }
        return new CachedBitmap(rgba, bw, bh);
    }

    private String getTextContent(CastMemberChunk memberChunk) {
        if (memberChunk == null || player.getFile() == null) return "";

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

        return player.getFile().getChunk(memberChunk.id(), TextChunk.class)
                .map(TextChunk::text)
                .orElse("");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    record CachedBitmap(byte[] rgba, int width, int height) {}
}
