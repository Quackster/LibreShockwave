package com.libreshockwave.player.render;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastMember;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Caches ink-processed bitmaps for rendering.
 * Handles async decode from file-loaded cast members and sync decode from dynamic members.
 * Thread-safe: decoding happens on a background thread pool while rendering reads from cache.
 */
public class BitmapCache {

    private final Map<Long, Bitmap> cache = new ConcurrentHashMap<>();
    private final Set<Integer> decoding = ConcurrentHashMap.newKeySet();
    private final Set<Integer> decodeFailed = ConcurrentHashMap.newKeySet();
    private final ExecutorService decoder;

    /**
     * Create a BitmapCache with async decoding (desktop player).
     */
    public BitmapCache() {
        this.decoder = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "BitmapCache-Decoder");
                t.setDaemon(true);
                return t;
            }
        );
    }

    /**
     * Create a BitmapCache with synchronous decoding (for TeaVM/WASM environments).
     * Pass false to avoid pulling in java.util.concurrent classes.
     */
    public BitmapCache(boolean async) {
        this.decoder = null;
    }

    /**
     * Build a cache key from member ID, ink, and backColor.
     * Same bitmap can be cached differently per ink/backColor combo.
     */
    private static long cacheKey(int memberId, int ink, int backColor) {
        return ((long) memberId << 32) | (((long) ink & 0xFF) << 24) | (backColor & 0xFFFFFFL);
    }

    /**
     * Get an ink-processed bitmap for a file-loaded cast member.
     * Returns null on first call (triggers async decode); returns cached bitmap on subsequent calls.
     */
    public Bitmap getProcessed(CastMemberChunk member, int ink, int backColor, Player player) {
        int id = member.id();
        long key = cacheKey(id, ink, backColor);

        Bitmap cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        // Skip if no player, already decoding, or previously failed
        if (player == null || decodeFailed.contains(id) || !decoding.add(id)) {
            return null;
        }

        // Decode bitmap (sync when no executor available, async otherwise)
        Runnable decodeTask = () -> {
            try {
                Optional<Bitmap> bitmap = player.decodeBitmap(member);
                if (bitmap.isEmpty()) {
                    decodeFailed.add(id);
                    return;
                }

                Bitmap raw = bitmap.get();

                // Parse BitmapInfo for useAlpha and paletteId
                boolean useAlpha = false;
                Palette palette = null;
                if (member.specificData() != null && member.specificData().length >= 10) {
                    DirectorFile memberFile = member.file();
                    int dirVer = 1200;
                    if (memberFile != null && memberFile.getConfig() != null) {
                        dirVer = memberFile.getConfig().directorVersion();
                    }
                    BitmapInfo info = BitmapInfo.parse(member.specificData(), dirVer);
                    useAlpha = info.useAlpha();
                    if (memberFile != null) {
                        palette = memberFile.resolvePalette(info.paletteId());
                    }
                }

                Bitmap processed = InkProcessor.applyInk(raw, ink, backColor, useAlpha, palette);
                cache.put(key, processed);
            } catch (Exception e) {
                decodeFailed.add(id);
            } finally {
                decoding.remove(id);
            }
        };

        if (decoder != null) {
            decoder.submit(decodeTask);
            return null;
        } else {
            // Synchronous mode (TeaVM/WASM)
            decodeTask.run();
            return cache.get(key);
        }
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
        decoding.clear();
        decodeFailed.clear();
    }

    /**
     * Shutdown the decoder thread pool.
     */
    public void shutdown() {
        if (decoder == null) return;
        decoder.shutdown();
        try {
            if (!decoder.awaitTermination(2, TimeUnit.SECONDS)) {
                decoder.shutdownNow();
            }
        } catch (InterruptedException e) {
            decoder.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
