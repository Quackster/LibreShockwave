package com.libreshockwave.wasm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.*;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.player.Palette;
import com.libreshockwave.player.Score;
import com.libreshockwave.player.Sprite;
import com.libreshockwave.player.bitmap.Bitmap;
import com.libreshockwave.player.bitmap.BitmapDecoder;

import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight player for WASM environment.
 * Does not support network loading or script execution - basic frame playback only.
 */
public class WasmPlayer {

    public enum PlayState {
        STOPPED, PLAYING, PAUSED
    }

    private DirectorFile movieFile;
    private CastManager castManager;
    private Score score;

    private PlayState state = PlayState.STOPPED;
    private int currentFrame = 1;
    private int lastFrame = 1;
    private int tempo = 15;
    private int stageWidth = 640;
    private int stageHeight = 480;

    // Sprite states
    private final Map<Integer, SpriteState> sprites = new HashMap<>();

    // Bitmap cache: key = "castLib:memberNum", value = decoded RGBA data
    private final Map<String, int[]> bitmapCache = new HashMap<>();
    private final Map<String, int[]> bitmapDimensions = new HashMap<>(); // [width, height]

    public static class SpriteState {
        public int channel;
        public int locH, locV;
        public int width, height;
        public int castLib, castMember;
        public int ink = 0;
        public int blend = 100;
        public boolean visible = true;
    }

    public WasmPlayer() {
        // Initialize with defaults
    }

    /**
     * Load a movie from byte data.
     */
    public void loadMovie(byte[] data) throws Exception {
        log("=== Loading movie (" + data.length + " bytes) ===");

        movieFile = DirectorFile.load(data);
        log("DirectorFile loaded successfully");
        log("  Endian: " + movieFile.getEndian());

        // Log config info
        ConfigChunk config = movieFile.getConfig();
        if (config != null) {
            tempo = config.tempo();
            stageWidth = config.stageRight() - config.stageLeft();
            stageHeight = config.stageBottom() - config.stageTop();
            log("  Director version: " + config.directorVersion());
            log("  Stage size: " + stageWidth + "x" + stageHeight);
            log("  Tempo: " + tempo + " fps");
        } else {
            log("  WARNING: No config chunk found");
        }

        // Create cast manager and log casts
        castManager = movieFile.createCastManager();
        log("CastManager created with " + castManager.getCastCount() + " casts");

        for (CastLib cast : castManager.getCasts()) {
            log("  Cast #" + cast.getNumber() + ": '" + cast.getName() + "'");
            log("    External: " + cast.isExternal());
            log("    State: " + cast.getState());
            log("    Members: " + cast.getMemberCount());
            if (cast.isExternal()) {
                log("    File: " + cast.getFileName());
            }

            // Log member types
            int bitmaps = 0, scripts = 0, others = 0;
            for (CastMemberChunk member : cast.getAllMembers()) {
                if (member.isBitmap()) bitmaps++;
                else if (member.memberType() == CastMemberChunk.MemberType.SCRIPT) scripts++;
                else others++;
            }
            log("    Member types: " + bitmaps + " bitmaps, " + scripts + " scripts, " + others + " others");
        }

        // Create score
        if (movieFile.hasScore()) {
            score = movieFile.createScore();
            lastFrame = score.getFrameCount();
            log("Score loaded: " + lastFrame + " frames, " + score.getChannelCount() + " channels");

            // Log first few frames info
            for (int f = 1; f <= Math.min(3, lastFrame); f++) {
                Score.Frame frame = score.getFrame(f);
                if (frame != null) {
                    log("  Frame " + f + ": " + frame.getSprites().size() + " sprites");
                    if (frame.hasFrameScript()) {
                        log("    Frame script: " + frame.getScriptCastLib() + ":" + frame.getScriptCastMember());
                    }
                    for (Sprite s : frame.getSpritesSorted()) {
                        log("    " + s.toString());
                    }
                }
            }
        } else {
            lastFrame = 1;
            log("WARNING: No score found in movie");
        }

        // Reset state
        currentFrame = 1;
        state = PlayState.STOPPED;
        sprites.clear();
        bitmapCache.clear();
        bitmapDimensions.clear();

        // Log external casts that need loading
        logPendingExternalCasts();

        // Load initial frame
        loadSpritesFromScore();
        log("=== Movie load complete ===");
    }

    /**
     * Log external casts that need to be loaded.
     */
    private void logPendingExternalCasts() {
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE) {
                log("EXTERNAL CAST NEEDED: #" + cast.getNumber() + " '" + cast.getName() +
                    "' file='" + cast.getFileName() + "'");
            }
        }
    }

    /**
     * Get the number of external casts that need to be loaded.
     */
    public int getPendingExternalCastCount() {
        if (castManager == null) return 0;
        int count = 0;
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE && !cast.getFileName().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get info about a pending external cast by index.
     * @return [castNumber, fileName] or null if not found
     */
    public String[] getPendingExternalCastInfo(int index) {
        if (castManager == null) return null;
        int count = 0;
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE && !cast.getFileName().isEmpty()) {
                if (count == index) {
                    return new String[]{String.valueOf(cast.getNumber()), cast.getFileName()};
                }
                count++;
            }
        }
        return null;
    }

    /**
     * Load an external cast from byte data.
     * @param castNumber The 1-based cast number
     * @param data The cast file data
     * @return true if loaded successfully
     */
    public boolean loadExternalCastFromData(int castNumber, byte[] data) {
        if (castManager == null) {
            log("loadExternalCastFromData: castManager is null");
            return false;
        }

        CastLib cast = castManager.getCast(castNumber);
        if (cast == null) {
            log("loadExternalCastFromData: Cast #" + castNumber + " not found");
            return false;
        }

        if (!cast.isExternal()) {
            log("loadExternalCastFromData: Cast #" + castNumber + " is not external");
            return false;
        }

        log("loadExternalCastFromData: Loading cast #" + castNumber + " '" + cast.getName() +
            "' from " + data.length + " bytes");

        try {
            log("loadExternalCastFromData: Parsing " + data.length + " bytes...");
            DirectorFile castFile = DirectorFile.load(data);
            if (castFile == null) {
                log("loadExternalCastFromData: DirectorFile.load returned null");
                return false;
            }
            log("loadExternalCastFromData: DirectorFile parsed, loading into cast...");
            cast.loadFromDirectorFile(castFile);
            log("loadExternalCastFromData: SUCCESS - loaded " + cast.getMemberCount() + " members");

            // Log member details
            int bitmaps = 0, scripts = 0, others = 0;
            for (CastMemberChunk member : cast.getAllMembers()) {
                if (member.isBitmap()) bitmaps++;
                else if (member.memberType() == CastMemberChunk.MemberType.SCRIPT) scripts++;
                else others++;
            }
            log("  Member types: " + bitmaps + " bitmaps, " + scripts + " scripts, " + others + " others");

            return true;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getName();
            log("loadExternalCastFromData: FAILED - " + msg);
            log("  Exception type: " + e.getClass().getName());

            // Print stack trace
            for (StackTraceElement ste : e.getStackTrace()) {
                log("    at " + ste.toString());
            }

            // Check for cause
            Throwable cause = e.getCause();
            if (cause != null) {
                log("  Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
            }

            return false;
        }
    }

    private void log(String message) {
        System.out.println("[WasmPlayer] " + message);
    }

    public void play() {
        state = PlayState.PLAYING;
    }

    public void stop() {
        state = PlayState.STOPPED;
        currentFrame = 1;
        loadSpritesFromScore();
    }

    public void pause() {
        state = PlayState.PAUSED;
    }

    public void nextFrame() {
        if (currentFrame < lastFrame) {
            currentFrame++;
            loadSpritesFromScore();
        }
    }

    public void prevFrame() {
        if (currentFrame > 1) {
            currentFrame--;
            loadSpritesFromScore();
        }
    }

    public void goToFrame(int frame) {
        currentFrame = Math.max(1, Math.min(frame, lastFrame));
        loadSpritesFromScore();
    }

    public void tick() {
        if (state != PlayState.PLAYING) return;

        currentFrame++;
        if (currentFrame > lastFrame) {
            currentFrame = 1; // Loop
        }
        loadSpritesFromScore();
    }

    private void loadSpritesFromScore() {
        sprites.clear();

        if (score == null) {
            log("loadSpritesFromScore: No score available");
            return;
        }

        Score.Frame frame = score.getFrame(currentFrame);
        if (frame == null) {
            log("loadSpritesFromScore: Frame " + currentFrame + " not found");
            return;
        }

        log("Loading frame " + currentFrame + " with " + frame.getSprites().size() + " sprites");

        for (Sprite sprite : frame.getSprites()) {
            if (sprite.getCastMember() <= 0) {
                log("  Skipping sprite ch=" + sprite.getChannel() + " (no member)");
                continue;
            }

            SpriteState ss = new SpriteState();
            ss.channel = sprite.getChannel();
            ss.locH = sprite.getLocH();
            ss.locV = sprite.getLocV();
            ss.width = sprite.getWidth();
            ss.height = sprite.getHeight();
            ss.castLib = sprite.getCastLib();
            ss.castMember = sprite.getCastMember();
            ss.ink = sprite.getInk();
            ss.blend = sprite.getBlend();
            ss.visible = sprite.isVisible();

            sprites.put(ss.channel, ss);
            log("  Loaded sprite: ch=" + ss.channel + " member=" + ss.castLib + ":" + ss.castMember +
                " pos=(" + ss.locH + "," + ss.locV + ") size=" + ss.width + "x" + ss.height);
        }

        log("Total sprites loaded: " + sprites.size());
    }

    // Getters
    public PlayState getState() { return state; }
    public int getCurrentFrame() { return currentFrame; }
    public int getLastFrame() { return lastFrame; }
    public int getTempo() { return tempo; }
    public int getStageWidth() { return stageWidth; }
    public int getStageHeight() { return stageHeight; }
    public Score getScore() { return score; }
    public Map<Integer, SpriteState> getSprites() {
        if (sprites == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(sprites);
    }
    public boolean isLoaded() { return movieFile != null; }

    // =====================================================
    // Bitmap access for WASM rendering
    // =====================================================

    /**
     * Get bitmap dimensions for a cast member.
     * @return [width, height] or null if not a bitmap
     */
    public int[] getBitmapDimensions(int castLib, int memberNum) {
        // Default castLib 0 to cast 1
        int effectiveCastLib = castLib > 0 ? castLib : 1;
        String key = effectiveCastLib + ":" + memberNum;

        // Check cache first
        if (bitmapDimensions.containsKey(key)) {
            return bitmapDimensions.get(key);
        }

        // Try to decode bitmap
        decodeBitmap(castLib, memberNum);

        return bitmapDimensions.get(key);
    }

    /**
     * Get decoded RGBA pixel data for a bitmap cast member.
     * @return RGBA pixel array (packed as 0xAARRGGBB integers) or null if not a bitmap
     */
    public int[] getBitmapPixels(int castLib, int memberNum) {
        // Default castLib 0 to cast 1
        int effectiveCastLib = castLib > 0 ? castLib : 1;
        String key = effectiveCastLib + ":" + memberNum;

        // Check cache first
        if (bitmapCache.containsKey(key)) {
            return bitmapCache.get(key);
        }

        // Try to decode bitmap
        decodeBitmap(castLib, memberNum);

        return bitmapCache.get(key);
    }

    /**
     * Decode a bitmap cast member and cache the result.
     */
    private void decodeBitmap(int castLibNum, int memberNum) {
        // Default castLib 0 to cast 1
        int effectiveCastLib = castLibNum > 0 ? castLibNum : 1;
        String key = effectiveCastLib + ":" + memberNum;
        log("decodeBitmap: Attempting to decode " + castLibNum + ":" + memberNum + " (effective: " + key + ")");

        if (movieFile == null || castManager == null) {
            log("  ERROR: movieFile or castManager is null");
            return;
        }

        try {
            // Get cast member
            CastLib castLib = castManager.getCast(effectiveCastLib);
            if (castLib == null) {
                log("  ERROR: Cast #" + effectiveCastLib + " not found (have " + castManager.getCastCount() + " casts)");
                return;
            }
            log("  Found cast: " + castLib.getName() + " with " + castLib.getMemberCount() + " members");

            CastMemberChunk member = castLib.getMember(memberNum);
            if (member == null) {
                log("  ERROR: Member #" + memberNum + " not found in cast");
                // List available members
                log("  Available members in cast:");
                for (CastMemberChunk m : castLib.getAllMembers()) {
                    log("    Member ID=" + m.id() + " name='" + m.name() + "' type=" + m.memberType());
                }
                return;
            }

            log("  Found member: id=" + member.id() + " name='" + member.name() + "' type=" + member.memberType());

            if (!member.isBitmap()) {
                log("  ERROR: Member is not a bitmap (type=" + member.memberType() + ")");
                return;
            }

            // Parse bitmap info
            BitmapInfo bitmapInfo = BitmapInfo.parse(member.specificData());
            log("  BitmapInfo: " + bitmapInfo.width() + "x" + bitmapInfo.height() +
                ", " + bitmapInfo.bitDepth() + "-bit, paletteId=" + bitmapInfo.paletteId());

            // Find BITD chunk using KeyTable
            KeyTableChunk keyTable = movieFile.getKeyTable();
            if (keyTable == null) {
                log("  ERROR: No KeyTable found");
                return;
            }

            log("  Looking for BITD chunk for member id=" + member.id());
            BitmapChunk bitmapChunk = null;
            for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                String fourccStr = entry.fourccString();
                log("    KeyTable entry: fourcc=" + fourccStr + " sectionId=" + entry.sectionId());
                if (fourccStr.equals("BITD") || fourccStr.equals("DTIB")) {
                    Chunk chunk = movieFile.getChunk(entry.sectionId());
                    log("    Found chunk at sectionId=" + entry.sectionId() + ": " + (chunk != null ? chunk.getClass().getSimpleName() : "null"));
                    if (chunk instanceof BitmapChunk bc) {
                        bitmapChunk = bc;
                        log("    BITD data size: " + bc.data().length + " bytes");
                        break;
                    }
                }
            }

            if (bitmapChunk == null) {
                log("  ERROR: No BITD chunk found for member");
                return;
            }

            // Get palette
            Palette palette;
            int paletteId = bitmapInfo.paletteId();
            if (paletteId < 0) {
                palette = Palette.getBuiltIn(paletteId);
                log("  Using built-in palette: " + palette.getName());
            } else {
                palette = Palette.getBuiltIn(Palette.SYSTEM_MAC);
                log("  Using default System Mac palette");
            }

            // Decode bitmap
            boolean bigEndian = movieFile.getEndian() == ByteOrder.BIG_ENDIAN;
            int directorVersion = movieFile.getConfig() != null ? movieFile.getConfig().directorVersion() : 500;

            log("  Decoding bitmap: bigEndian=" + bigEndian + " directorVersion=" + directorVersion);

            Bitmap bitmap = BitmapDecoder.decode(
                bitmapChunk.data(),
                bitmapInfo.width(),
                bitmapInfo.height(),
                bitmapInfo.bitDepth(),
                palette,
                true,
                bigEndian,
                directorVersion
            );

            log("  Decoded bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // Convert to RGBA pixel array
            int[] pixels = bitmap.getPixels();

            // Cache the result
            bitmapCache.put(key, pixels);
            bitmapDimensions.put(key, new int[]{bitmap.getWidth(), bitmap.getHeight()});
            log("  SUCCESS: Bitmap cached with " + pixels.length + " pixels");

        } catch (Exception e) {
            log("  EXCEPTION: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Clear the bitmap cache.
     */
    public void clearBitmapCache() {
        bitmapCache.clear();
        bitmapDimensions.clear();
    }
}
