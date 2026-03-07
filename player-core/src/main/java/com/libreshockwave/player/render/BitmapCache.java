package com.libreshockwave.player.render;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastMember;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Caches ink-processed bitmaps for rendering.
 * Handles async decode from file-loaded cast members and sync decode from dynamic members.
 * Thread-safe: decoding happens on a background thread pool while rendering reads from cache.
 * <p>
 * Uses functional interfaces (Consumer/Runnable) instead of direct ExecutorService references
 * so that TeaVM can compile this class without pulling in java.util.concurrent.ExecutorService.
 */
public class BitmapCache {

    private final Map<Long, Bitmap> cache = new ConcurrentHashMap<>();
    private final Set<Integer> decoding = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> decodeFailed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Tracks the last known palette version per member ID to detect palette changes. */
    private final Map<Integer, Integer> paletteVersions = new ConcurrentHashMap<>();

    /** Submits a task for async decoding. Null in synchronous mode. */
    private final Consumer<Runnable> taskSubmitter;

    /** Shuts down the decoder thread pool. Null in synchronous mode. */
    private final Runnable shutdownCallback;

    /**
     * Create a BitmapCache with async decoding (desktop player).
     * The ExecutorService is fully contained within this constructor and the lambdas it creates,
     * so TeaVM never traces into it (only the TeaVM constructor is reachable from WASM).
     */
    public BitmapCache() {
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "BitmapCache-Decoder");
                t.setDaemon(true);
                return t;
            }
        );
        this.taskSubmitter = task -> exec.submit(task);
        this.shutdownCallback = () -> {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        };
    }

    /**
     * Create a BitmapCache with synchronous decoding (for TeaVM/WASM environments).
     * No ExecutorService is created or referenced.
     */
    public BitmapCache(boolean async) {
        this.taskSubmitter = null;
        this.shutdownCallback = null;
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

        // Skip if no player, already decoding, or previously failed
        if (player == null || decodeFailed.contains(id) || !decoding.add(id)) {
            return null;
        }

        // Decode bitmap (sync when no submitter available, async otherwise)
        Runnable decodeTask = () -> {
            try {
                Optional<Bitmap> bitmap;
                if (paletteOverride != null) {
                    bitmap = player.decodeBitmap(member, paletteOverride);
                } else {
                    bitmap = player.decodeBitmap(member);
                }
                if (bitmap.isEmpty()) {
                    decodeFailed.add(id);
                    return;
                }

                Bitmap raw = bitmap.get();

                // Parse BitmapInfo for useAlpha and paletteId
                boolean useAlpha = false;
                Palette palette = paletteOverride;
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

                Bitmap processed = InkProcessor.applyInk(raw, ink, backColor, useAlpha, palette);
                cache.put(key, processed);
            } catch (Exception e) {
                decodeFailed.add(id);
            } finally {
                decoding.remove(id);
            }
        };

        if (taskSubmitter != null) {
            taskSubmitter.accept(decodeTask);
            return null;
        } else {
            // Synchronous mode (TeaVM/WASM)
            decodeTask.run();
            return cache.get(key);
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
        decoding.remove(memberId);
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
        decoding.clear();
        decodeFailed.clear();
    }

    /**
     * Shutdown the decoder thread pool.
     */
    public void shutdown() {
        if (shutdownCallback != null) {
            shutdownCallback.run();
        }
    }
}
