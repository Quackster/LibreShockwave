package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.CastMemberChunk;

import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastMember;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches ink-processed bitmaps for rendering.
 * All decoding is synchronous — WASM is single-threaded and desktop bitmaps decode fast enough.
 */
public class BitmapCache {

    private final Map<Long, Bitmap> cache = new ConcurrentHashMap<>();
    private final Set<Integer> decodeFailed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Tracks the last known palette version per member ID to detect palette changes. */
    private final Map<Integer, Integer> paletteVersions = new ConcurrentHashMap<>();

    /**
     * Build a cache key from member ID, ink, and backColor.
     * Same bitmap can be cached differently per ink/backColor combo.
     */
    private static long cacheKey(int memberId, int ink, int backColor) {
        return ((long) memberId << 32) | (((long) ink & 0xFF) << 24) | (backColor & 0xFFFFFFL);
    }

    /**
     * Get an ink-processed bitmap for a file-loaded cast member.
     * Decodes synchronously on first call; returns cached bitmap on subsequent calls.
     */
    public Bitmap getProcessed(CastMemberChunk member, int ink, int backColor, Player player) {
        return getProcessed(member, ink, backColor, player, null);
    }

    /**
     * Get an ink-processed bitmap with an optional palette override.
     * Used for palette swap animation where the runtime palette differs from the embedded one.
     */
    public Bitmap getProcessed(CastMemberChunk member, int ink, int backColor, Player player, Palette paletteOverride) {
        int id = member.id().value();
        long key = cacheKey(id, ink, backColor);

        Bitmap cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        if (player == null || decodeFailed.contains(id)) {
            return null;
        }

        try {
            Palette effectivePalette = paletteOverride;

            // Parse BitmapInfo for useAlpha and paletteId.
            // Resolve the palette BEFORE decoding so we use the same palette for
            // both bitmap decoding and InkProcessor backColor resolution.
            boolean useAlpha = false;
            Palette palette = effectivePalette;
            if (member.specificData() != null && member.specificData().length >= 10) {
                DirectorFile memberFile = member.file();
                int dirVer = 1200;
                if (memberFile != null && memberFile.getConfig() != null) {
                    dirVer = memberFile.getConfig().directorVersion();
                }
                BitmapInfo info = BitmapInfo.parse(member.specificData(), dirVer);
                useAlpha = info.useAlpha();
                if (palette == null && memberFile != null) {
                    palette = memberFile.resolvePalette(info.paletteId());
                }
            }

            // Decode with resolved palette to ensure pixel colors match the
            // palette used for InkProcessor backColor resolution.
            Optional<Bitmap> bitmap;
            if (palette != null) {
                bitmap = player.decodeBitmap(member, palette);
            } else {
                bitmap = player.decodeBitmap(member);
            }
            if (bitmap.isEmpty()) {
                decodeFailed.add(id);
                return null;
            }

            Bitmap raw = bitmap.get();

            Bitmap processed = InkProcessor.applyInk(raw, ink, backColor, useAlpha, palette);
            cache.put(key, processed);
            return processed;
        } catch (Exception e) {
            decodeFailed.add(id);
            return null;
        }
    }

    /**
     * Invalidate cache entries for a member if its palette version has changed.
     * Returns true if the cache was actually invalidated (palette changed since last render).
     */
    public boolean invalidateIfPaletteChanged(int memberId, int paletteVersion) {
        Integer lastVersion = paletteVersions.get(memberId);
        if (lastVersion != null && lastVersion == paletteVersion) {
            return false; // No change
        }
        paletteVersions.put(memberId, paletteVersion);
        // Remove from caches - scan for any key containing this member ID
        cache.keySet().removeIf(key -> (key >> 32) == memberId);
        decodeFailed.remove(memberId);
        return true;
    }

    /**
     * Get an ink-processed bitmap for a dynamic (runtime-created) cast member.
     * Synchronous — dynamic members already have their bitmap decoded.
     * NOT cached because dynamic member bitmaps are mutable (window system updates them).
     */
    public Bitmap getProcessedDynamic(CastMember dynMember, int ink, int backColor) {
        Bitmap bmp = dynMember.getBitmap();
        if (bmp == null) {
            return null;
        }

        if (InkProcessor.shouldProcessInk(ink)) {
            return InkProcessor.applyInk(bmp, ink, backColor, false, null);
        }
        return bmp;
    }

    /**
     * Clear all cached bitmaps. Call when external casts are loaded.
     */
    public void clear() {
        cache.clear();
        decodeFailed.clear();
    }
}
