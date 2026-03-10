package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.frame.FrameContext;
import com.libreshockwave.player.input.HitTester;
import com.libreshockwave.player.input.InputEvent;
import com.libreshockwave.player.input.InputState;
import com.libreshockwave.player.net.NetManager;
import com.libreshockwave.player.render.pipeline.BitmapCache;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;
import com.libreshockwave.player.render.pipeline.RenderSprite;
import com.libreshockwave.player.render.pipeline.SpriteBaker;
import com.libreshockwave.player.render.pipeline.StageRenderer;
import com.libreshockwave.player.score.ScoreNavigator;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.datum.LingoException;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.builtin.net.ExternalParamProvider;
import com.libreshockwave.vm.builtin.movie.MoviePropertyProvider;
import com.libreshockwave.vm.builtin.net.NetBuiltins;
import com.libreshockwave.vm.builtin.sprite.SpritePropertyProvider;
import java.util.Optional;
import com.libreshockwave.vm.builtin.media.SoundProvider;
import com.libreshockwave.vm.builtin.timeout.TimeoutProvider;
import com.libreshockwave.vm.builtin.xtra.XtraBuiltins;
import com.libreshockwave.player.audio.AudioBackend;
import com.libreshockwave.player.audio.SoundManager;
import com.libreshockwave.player.timeout.TimeoutManager;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.xtra.MultiuserXtra;
import com.libreshockwave.player.xtra.SocketMultiuserBridge;
import com.libreshockwave.vm.xtra.XtraManager;
import com.libreshockwave.player.debug.DebugControllerApi;

import com.libreshockwave.bitmap.Bitmap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Director movie player.
 * Handles frame playback, event dispatch, and score traversal.
 * Uses modular components for score navigation, behavior management, and event dispatch.
 */
public class Player {

    private final DirectorFile file;
    private final LingoVM vm;
    private final FrameContext frameContext;
    private final StageRenderer stageRenderer;
    private final NetManager netManager;  // null when using external NetProvider (e.g. TeaVM)
    private final XtraManager xtraManager;
    private final MovieProperties movieProperties;
    private final SpriteProperties spriteProperties;
    private final CastLibManager castLibManager;
    private final TimeoutManager timeoutManager;
    private final SoundManager soundManager;
    private final BitmapCache bitmapCache;
    private final SpriteBaker spriteBaker;
    private final InputState inputState;

    // Rollover tracking for mouseEnter/mouseLeave/mouseWithin events
    private int previousRolloverSprite = 0;

    // Movie-level palette (from score's palette channel) — used for all 8-bit bitmap decoding
    private Palette moviePalette;
    private int moviePaletteFrame = -1; // frame at which moviePalette was last resolved

    private final PlayerTraceListener playerTraceListener;

    private PlayerState state = PlayerState.STOPPED;
    private int tempo;  // Frames per second

    // Event listeners for external notification
    private Consumer<PlayerEventInfo> eventListener;

    // Cast loaded listener (called when external cast libraries are loaded and matched)
    private Runnable castLoadedListener;

    // Error listener (called on Lingo script errors)
    private java.util.function.BiConsumer<String, LingoException> errorListener;

    // Debug mode
    private boolean debugEnabled = false;

    // Debug controller for bytecode debugging
    private DebugControllerApi debugController;

    // Executor for running VM on background thread (required for debugger blocking)
    // Lazy-initialized to avoid creating threads in environments that don't support them (e.g. TeaVM)
    private ExecutorService vmExecutor;

    // Executor for parsing external cast files off the network thread
    // Lazy-initialized to avoid pulling in java.util.concurrent in TeaVM environments
    private ExecutorService castParserExecutor;
    private Runnable castParserShutdown;  // Shutdown hook, avoids referencing ExecutorService in shutdown()
    private Runnable vmExecutorShutdown;  // Shutdown hook, avoids referencing ExecutorService in shutdown()
    private volatile boolean vmRunning = false;

    // External parameters (Shockwave PARAM tags)
    private final Map<String, String> externalParams = new LinkedHashMap<>();
    private final ExternalParamProvider externalParamProvider = new ExternalParamProvider() {
        @Override
        public String getParamValue(String name) {
            for (var entry : externalParams.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public String getParamName(int index) {
            if (index < 1 || index > externalParams.size()) return null;
            int i = 1;
            for (String key : externalParams.keySet()) {
                if (i == index) return key;
                i++;
            }
            return null;
        }

        @Override
        public int getParamCount() {
            return externalParams.size();
        }

        @Override
        public Map<String, String> getAllParams() {
            return Collections.unmodifiableMap(externalParams);
        }
    };

    // Optional override for the network provider (used by player-wasm to substitute FetchNetManager)
    private NetBuiltins.NetProvider overrideNetProvider;

    public Player(DirectorFile file) {
        this.file = file;
        this.vm = new LingoVM(file);
        this.frameContext = new FrameContext(file, vm);
        this.stageRenderer = new StageRenderer(file);
        // Apply stage background color from movie config (palette-aware)
        if (file != null && file.getConfig() != null) {
            this.stageRenderer.setBackgroundColor(file.getConfig().stageColorRGB());
        }
        this.netManager = new NetManager();
        this.xtraManager = new XtraManager();
        registerMultiuserXtra(new SocketMultiuserBridge());
        this.movieProperties = new MovieProperties(this, file);
        this.spriteProperties = new SpriteProperties(stageRenderer.getSpriteRegistry());
        // Initialize cast parser executor (only needed for desktop player with NetManager)
        this.castParserExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> { Thread t = new Thread(r, "CastParser"); t.setDaemon(true); return t; }
        );
        this.castParserShutdown = () -> castParserExecutor.shutdownNow();

        // Cast data request callback: when Lingo sets castLib.fileName, load the data from
        // NetManager's cache (already downloaded by preloadNetThing) into the specific cast.
        this.castLibManager = new CastLibManager(file, (castLibNumber, fileName) ->
            loadCastFromNetCache(castLibNumber, fileName));
        this.stageRenderer.setCastLibManager(castLibManager);
        this.spriteProperties.setCastLibManager(castLibManager);
        this.timeoutManager = new TimeoutManager();
        this.soundManager = new SoundManager(castLibManager);
        this.bitmapCache = new BitmapCache();
        this.spriteBaker = new SpriteBaker(bitmapCache, castLibManager, this);
        this.inputState = new InputState();
        this.movieProperties.setInputState(inputState);
        this.frameContext.setTimeoutManager(timeoutManager);
        this.frameContext.getEventDispatcher().setCastLibManager(castLibManager);
        this.frameContext.getEventDispatcher().setSpriteRegistry(stageRenderer.getSpriteRegistry());
        this.frameContext.setActorListSupplier(movieProperties::getActorList);
        this.playerTraceListener = new PlayerTraceListener();
        vm.setTraceListener(playerTraceListener);

        // Shockwave plugin effectively capped playback at ~15fps regardless of
        // the authored config tempo. Use 15fps as the base; score tempo channel
        // and puppetTempo can still override this per-frame.
        this.tempo = 15;

        // Set up AWT JPEG decoder for ediM bitmap support (desktop only)
        DirectorFile.setJpegDecoder(jpegData -> {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegData));
                if (img == null) return null;
                Bitmap bmp = new Bitmap(img.getWidth(), img.getHeight(), 32);
                for (int y = 0; y < img.getHeight(); y++) {
                    for (int x = 0; x < img.getWidth(); x++) {
                        bmp.setPixel(x, y, img.getRGB(x, y));
                    }
                }
                return bmp;
            } catch (Exception e) {
                return null;
            }
        });

        // Set AWT text renderer for desktop environment
        com.libreshockwave.player.cast.CastMember.setTextRenderer(new com.libreshockwave.player.render.output.AwtTextRenderer());
        // Wire up member visual change callback to bump sprite revision
        com.libreshockwave.player.cast.CastMember.setMemberVisualChangedCallback(
                () -> stageRenderer.getSpriteRegistry().bumpRevision());

        // Set base path for network requests from the file location
        if (file != null && file.getBasePath() != null && !file.getBasePath().isEmpty()) {
            netManager.setBasePath(file.getBasePath());
        }

        // Wire up network completion callback to handle external cast loading
        netManager.setCompletionCallback((fileName, data) -> {
            // Offload heavy DirectorFile.load() + CastLib.load() to dedicated thread pool
            // so the NetManager worker can immediately handle more downloads
            castParserExecutor.submit(() -> {
                if (castLibManager.setExternalCastDataByUrl(fileName, data)) {
                    System.out.println("[Player] Loaded external cast from: " + fileName);
                    bitmapCache.clear();
                    if (castLoadedListener != null) {
                        castLoadedListener.run();
                    }
                }
            });
        });

        // Wire up event notifications
        frameContext.setEventListener(event -> {
            if (eventListener != null) {
                eventListener.accept(new PlayerEventInfo(event.event(), event.frame(), 0));
            }
            // Notify stage renderer of frame changes
            if (event.event() == PlayerEvent.ENTER_FRAME) {
                stageRenderer.onFrameEnter(event.frame());
            }
        });
    }

    /**
     * Constructor for environments that provide their own NetProvider (e.g. TeaVM/browser).
     * Skips creating the JVM NetManager to avoid pulling in java.util.concurrent classes.
     */
    public Player(DirectorFile file, NetBuiltins.NetProvider netProvider,
                  java.util.function.BiConsumer<Integer, String> castDataRequestCallback) {
        this.file = file;
        this.vm = new LingoVM(file);
        this.frameContext = new FrameContext(file, vm);
        this.stageRenderer = new StageRenderer(file);
        // Apply stage background color from movie config (palette-aware)
        if (file != null && file.getConfig() != null) {
            this.stageRenderer.setBackgroundColor(file.getConfig().stageColorRGB());
        }
        this.netManager = null;
        this.overrideNetProvider = netProvider;
        this.xtraManager = new XtraManager();
        // No auto-register here — TeaVM has no socket support.
        // WASM callers should call registerMultiuserXtra() with their own bridge.
        this.movieProperties = new MovieProperties(this, file);
        this.spriteProperties = new SpriteProperties(stageRenderer.getSpriteRegistry());
        this.castLibManager = new CastLibManager(file, castDataRequestCallback);
        this.stageRenderer.setCastLibManager(castLibManager);
        this.spriteProperties.setCastLibManager(castLibManager);
        this.timeoutManager = new TimeoutManager();
        this.soundManager = new SoundManager(castLibManager);
        this.bitmapCache = new BitmapCache();
        this.spriteBaker = new SpriteBaker(bitmapCache, castLibManager, this);
        this.inputState = new InputState();
        this.movieProperties.setInputState(inputState);
        // Set simple text renderer for TeaVM/WASM (no AWT)
        com.libreshockwave.player.cast.CastMember.setTextRenderer(new com.libreshockwave.player.render.output.SimpleTextRenderer());
        com.libreshockwave.player.cast.CastMember.setMemberVisualChangedCallback(
                () -> stageRenderer.getSpriteRegistry().bumpRevision());
        this.frameContext.setTimeoutManager(timeoutManager);
        this.frameContext.getEventDispatcher().setCastLibManager(castLibManager);
        this.frameContext.getEventDispatcher().setSpriteRegistry(stageRenderer.getSpriteRegistry());
        this.frameContext.setActorListSupplier(movieProperties::getActorList);
        this.playerTraceListener = new PlayerTraceListener();
        vm.setTraceListener(playerTraceListener);

        // Shockwave plugin effectively capped playback at ~15fps regardless of
        // the authored config tempo. Use 15fps as the base; score tempo channel
        // and puppetTempo can still override this per-frame.
        this.tempo = 15;

        // Wire up event notifications
        frameContext.setEventListener(event -> {
            if (eventListener != null) {
                eventListener.accept(new PlayerEventInfo(event.event(), event.frame(), 0));
            }
            // Notify stage renderer of frame changes
            if (event.event() == PlayerEvent.ENTER_FRAME) {
                stageRenderer.onFrameEnter(event.frame());
            }
        });
    }

    // Accessors

    public DirectorFile getFile() {
        return file;
    }

    public LingoVM getVM() {
        return vm;
    }

    public FrameContext getFrameContext() {
        return frameContext;
    }

    public ScoreNavigator getNavigator() {
        return frameContext.getNavigator();
    }

    public BehaviorManager getBehaviorManager() {
        return frameContext.getBehaviorManager();
    }

    public EventDispatcher getEventDispatcher() {
        return frameContext.getEventDispatcher();
    }

    public StageRenderer getStageRenderer() {
        return stageRenderer;
    }

    public NetManager getNetManager() {
        return netManager;
    }

    public XtraManager getXtraManager() {
        return xtraManager;
    }

    /**
     * Register the Multiuser Xtra with a platform-specific network bridge.
     * Call this before play() to enable Lingo's xtra("Multiuser") functionality.
     */
    public void registerMultiuserXtra(MultiuserNetBridge netBridge) {
        MultiuserXtra multiuserXtra = new MultiuserXtra(netBridge, (target, handlerName, args) -> {
            if (target instanceof Datum.ScriptInstance si) {
                invokeOnScriptInstance(si, handlerName, args);
            } else {
                try {
                    vm.callHandler(handlerName, args);
                } catch (Exception e) {
                    System.err.println("[MultiuserXtra] Callback error: " + e.getMessage());
                }
            }
        });
        xtraManager.registerXtra(multiuserXtra);
    }

    /**
     * Invoke a handler on a script instance, walking the ancestor chain.
     * Shared utility used by timeout callbacks, Xtra callbacks, etc.
     */
    private void invokeOnScriptInstance(Datum.ScriptInstance target,
                                         String handlerName, java.util.List<Datum> args) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            try { vm.callHandler(handlerName, args); }
            catch (Exception e) {
                System.err.println("[Player] Error in script callback " + handlerName + ": " + e.getMessage());
            }
            return;
        }

        Datum.ScriptInstance current = target;
        for (int i = 0; i < 20; i++) {
            Datum scriptRefDatum = current.properties().get(Datum.PROP_SCRIPT_REF);
            CastLibProvider.HandlerLocation location;

            if (scriptRefDatum instanceof Datum.ScriptRef sr) {
                location = provider.findHandlerInScript(sr.castLibNum(), sr.memberNum(), handlerName);
            } else {
                location = provider.findHandlerInScript(current.scriptId(), handlerName);
            }

            if (location != null && location.script() instanceof ScriptChunk script
                    && location.handler() instanceof ScriptChunk.Handler handler) {
                try {
                    vm.executeHandler(script, handler, args, target);
                } catch (Exception e) {
                    System.err.println("[Player] Error in script callback " + handlerName + ": " + e.getMessage());
                }
                return;
            }

            Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }

        try { vm.callHandler(handlerName, args); }
        catch (Exception e) {
            System.err.println("[Player] Error in script callback " + handlerName + " (global): " + e.getMessage());
        }
    }

    public MovieProperties getMovieProperties() {
        return movieProperties;
    }

    public CastLibManager getCastLibManager() {
        return castLibManager;
    }

    public SoundManager getSoundManager() {
        return soundManager;
    }

    /**
     * Set audio backend for sound playback.
     * Call before play() to enable sound. Platform-specific:
     * - player-swing: SwingAudioBackend (javax.sound.sampled)
     * - player-wasm: WasmAudioBackend (Web Audio API via JS bridge)
     */
    public void setAudioBackend(AudioBackend backend) {
        soundManager.setBackend(backend);
    }

    /**
     * Set external parameters (Shockwave PARAM tags).
     * These are accessible to Lingo scripts via externalParamValue(), etc.
     */
    public void setExternalParams(Map<String, String> params) {
        externalParams.clear();
        if (params != null) {
            externalParams.putAll(params);
        }
    }

    /**
     * Get external parameters.
     */
    public Map<String, String> getExternalParams() {
        return Collections.unmodifiableMap(externalParams);
    }

    /**
     * Decode a bitmap from any loaded source — main file or external casts.
     * Uses the member's own file reference to avoid chunk ID collisions.
     */
    public Optional<Bitmap> decodeBitmap(CastMemberChunk member) {
        // Each CastMemberChunk stores a reference to the DirectorFile it was loaded from.
        // Use that file first to avoid cross-file chunk ID collisions.
        DirectorFile memberFile = member.file();

        // For external cast bitmaps, resolve palette cross-file.
        // External casts often don't contain palette cast members, so the bitmap's
        // palette reference (clutId) may point to a palette in the main movie file.
        if (memberFile != null && memberFile != file && member.isBitmap()) {
            Palette crossFilePalette = resolvePaletteCrossFile(member, memberFile);
            if (crossFilePalette != null) {
                Optional<Bitmap> result = memberFile.decodeBitmap(member, crossFilePalette);
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        if (memberFile != null) {
            Optional<Bitmap> result = memberFile.decodeBitmap(member);
            if (result.isPresent()) {
                return result;
            }
        }

        // Fallback: try main file
        if (file != null && file != memberFile) {
            Optional<Bitmap> result = file.decodeBitmap(member);
            if (result.isPresent()) {
                return result;
            }
        }

        // Last resort: try all external casts
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                if (!castLib.isLoaded()) continue;
                DirectorFile src = castLib.getSourceFile();
                if (src != null && src != memberFile && src != file) {
                    Optional<Bitmap> result = src.decodeBitmap(member);
                    if (result.isPresent()) {
                        return result;
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Resolve palette cross-file for an external cast bitmap.
     * If the bitmap's palette can't be found in its own file, tries the main movie
     * file and other loaded cast libraries.
     * @return The resolved palette, or null if no cross-file resolution is needed
     */
    private Palette resolvePaletteCrossFile(CastMemberChunk member, DirectorFile memberFile) {
        if (member.specificData() == null || member.specificData().length < 10) {
            return null;
        }

        int dirVer = 1200;
        if (memberFile.getConfig() != null) {
            dirVer = memberFile.getConfig().directorVersion();
        }
        BitmapInfo info = BitmapInfo.parse(member.specificData(), dirVer);
        int paletteId = info.paletteId();

        // Built-in palettes (negative IDs) don't need cross-file resolution
        if (paletteId < 0) {
            return null;
        }

        // Check if the palette exists in the member's own file
        Palette pal = memberFile.resolvePaletteExact(paletteId);
        if (pal != null) {
            return null; // Found in own file — no override needed
        }

        // Not found in own file — try main movie file
        if (file != null) {
            pal = file.resolvePaletteExact(paletteId);
            if (pal != null) {
                return pal;
            }
        }

        // Try other loaded cast libraries
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                if (!castLib.isLoaded()) continue;
                DirectorFile src = castLib.getSourceFile();
                if (src != null && src != memberFile && src != file) {
                    pal = src.resolvePaletteExact(paletteId);
                    if (pal != null) {
                        return pal;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Decode a bitmap with a palette override.
     * Used for palette swap animation where the runtime palette differs from the embedded one.
     */
    public Optional<Bitmap> decodeBitmap(CastMemberChunk member, Palette paletteOverride) {
        DirectorFile memberFile = member.file();
        if (memberFile != null) {
            Optional<Bitmap> result = memberFile.decodeBitmap(member, paletteOverride);
            if (result.isPresent()) {
                return result;
            }
        }
        if (file != null && file != memberFile) {
            Optional<Bitmap> result = file.decodeBitmap(member, paletteOverride);
            if (result.isPresent()) {
                return result;
            }
        }
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                if (!castLib.isLoaded()) continue;
                DirectorFile src = castLib.getSourceFile();
                if (src != null && src != memberFile && src != file) {
                    Optional<Bitmap> result = src.decodeBitmap(member, paletteOverride);
                    if (result.isPresent()) {
                        return result;
                    }
                }
            }
        }
        return Optional.empty();
    }

    public TimeoutManager getTimeoutManager() {
        return timeoutManager;
    }

    /**
     * Preload all external cast libraries.
     * This triggers async loading of any casts that have external paths/URLs.
     * Use this to make all scripts available for debugging before playback starts.
     * @return The number of casts that were queued for loading
     */
    public int preloadAllCasts() {
        NetBuiltins.NetProvider provider = overrideNetProvider != null ? overrideNetProvider : netManager;
        if (provider == null) return 0;
        int count = 0;
        for (var entry : castLibManager.getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            if (castLib.isExternal() && !castLib.isLoaded() && !castLib.isFetching()) {
                String fileName = castLib.getFileName();
                if (fileName != null && !fileName.isEmpty()) {
                    castLib.markFetching();
                    provider.preloadNetThing(fileName);
                    count++;
                    System.out.println("[Player] Preloading external cast: " + fileName);
                }
            }
        }
        return count;
    }

    public PlayerState getState() {
        return state;
    }

    public int getCurrentFrame() {
        return frameContext.getCurrentFrame();
    }

    /**
     * Get the effective tempo (frames per second).
     * Priority: puppetTempo > score tempo channel > config tempo.
     */
    public int getTempo() {
        int puppetTempo = movieProperties.getPuppetTempo();
        if (puppetTempo > 0) {
            return puppetTempo;
        }
        // Check per-frame tempo from score's tempo channel (0-indexed)
        if (file != null) {
            int scoreTempo = file.getScoreTempo(frameContext.getCurrentFrame() - 1);
            if (scoreTempo > 0) {
                return scoreTempo;
            }
        }
        return tempo;
    }

    /**
     * Set the base tempo (from score).
     * This can be overridden by puppetTempo.
     */
    public void setTempo(int tempo) {
        this.tempo = tempo > 0 ? tempo : 15;
    }

    /**
     * Get the base tempo (ignoring puppetTempo).
     */
    public int getBaseTempo() {
        return tempo;
    }

    public int getFrameCount() {
        return frameContext.getFrameCount();
    }

    /**
     * Get a snapshot of the current frame for rendering.
     * This captures all sprite states at the moment it's called.
     */
    public FrameSnapshot getFrameSnapshot() {
        int frame = getCurrentFrame();
        List<RenderSprite> sprites = stageRenderer.getSpritesForFrame(frame);
        List<RenderSprite> baked = spriteBaker.bakeSprites(sprites);
        stageRenderer.setLastBakedSprites(baked);
        String debug = String.format("Frame %d | %s", frame, state.name());
        return new FrameSnapshot(
            frame,
            stageRenderer.getStageWidth(),
            stageRenderer.getStageHeight(),
            stageRenderer.getBackgroundColor(),
            List.copyOf(baked),
            debug,
            stageRenderer.hasStageImage() ? stageRenderer.getStageImage() : null
        );
    }

    /**
     * Get the movie's current palette (from score's palette channel).
     * In Director's 8-bit color model, all bitmaps on stage share this palette.
     * Returns null if no palette channel is set (use bitmap's own palette).
     */
    public Palette getMoviePalette() {
        int currentFrame = frameContext.getCurrentFrame() - 1; // 0-indexed
        if (currentFrame != moviePaletteFrame) {
            moviePaletteFrame = currentFrame;
            moviePalette = resolveMoviePalette(currentFrame);
        }
        return moviePalette;
    }

    private Palette resolveMoviePalette(int frame) {
        if (file == null) return null;

        // Priority 1: Score palette channel (per-frame)
        var paletteData = file.getScorePalette(frame);
        if (paletteData != null) {
            // ScummVM: negative member values are built-in palette IDs
            if (paletteData.castMember() < 0) {
                return Palette.getBuiltIn(paletteData.castMember());
            }
            Palette pal = resolvePaletteByMember(paletteData.castLib(), paletteData.castMember());
            if (pal != null) return pal;
        }

        // Priority 2: Config default palette (movie-level)
        var config = file.getConfig();
        if (config != null && config.defaultPaletteMember() != 0) {
            int castLib = config.defaultPaletteCastLib();
            int member = config.defaultPaletteMember();
            // ScummVM: negative member values are built-in palette IDs
            if (member < 0) {
                return Palette.getBuiltIn(member);
            }
            Palette pal = resolvePaletteByMember(castLib, member);
            if (pal != null) return pal;
        }

        return null;
    }

    private Palette resolvePaletteByMember(int castLib, int memberNum) {
        if (castLibManager != null) {
            CastMemberChunk palChunk = castLibManager.getCastMember(
                castLib > 0 ? castLib : 1, memberNum);
            if (palChunk != null && palChunk.file() != null) {
                return palChunk.file().resolvePalette(memberNum - 1);
            }
        }
        if (file != null) {
            return file.resolvePalette(memberNum - 1);
        }
        return null;
    }

    /**
     * Get the bitmap cache (for external cache management).
     */
    public BitmapCache getBitmapCache() {
        return bitmapCache;
    }

    public void setEventListener(Consumer<PlayerEventInfo> listener) {
        this.eventListener = listener;
    }

    /**
     * Set a listener to be notified when external cast libraries are loaded.
     * This is useful for refreshing UI components that display cast contents.
     */
    public void setCastLoadedListener(Runnable listener) {
        this.castLoadedListener = listener;
    }

    /**
     * Set a listener for Lingo script errors.
     * Called with the error message and the Lingo call stack at the point of the error.
     * The LingoException carries the call stack — use {@code e.getLingoCallStack()} or
     * {@code e.formatLingoCallStack()} to inspect it.
     */
    public void setErrorListener(java.util.function.BiConsumer<String, com.libreshockwave.vm.datum.LingoException> listener) {
        this.errorListener = listener;
    }

    /**
     * Get the current Lingo call stack. Safe to call at any time.
     * Returns an empty list when no handlers are executing.
     */
    public List<LingoVM.CallStackFrame> getLingoCallStack() {
        return vm.getCallStack();
    }

    /**
     * Get the current Lingo call stack as a formatted string.
     * Returns "(empty)" when no handlers are executing.
     */
    public String formatLingoCallStack() {
        return vm.formatCallStack();
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        frameContext.setDebugEnabled(enabled);
        if (enabled) {
            dumpScriptInfo();
        }
    }

    /**
     * Set the debug controller for bytecode-level debugging.
     * The controller will receive TraceListener callbacks and can pause/step the VM.
     * The controller is installed as a delegate inside our PlayerTraceListener so that
     * the constructObjectManager interception continues to work alongside debugging.
     */
    public void setDebugController(DebugControllerApi controller) {
        this.debugController = controller;
        playerTraceListener.setDelegate(controller);
    }

    /**
     * Get the debug controller.
     */
    public DebugControllerApi getDebugController() {
        return debugController;
    }

    /**
     * Check if VM is currently running on background thread.
     */
    public boolean isVmRunning() {
        return vmRunning;
    }

    /**
     * Get or lazily create the VM executor service.
     * Only used by async methods (playAsync, tickAsync, stepFrameAsync, shutdown).
     */
    private ExecutorService getVmExecutor() {
        if (vmExecutor == null) {
            ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "LingoVM-Executor");
                t.setDaemon(true);
                return t;
            });
            vmExecutor = exec;
            vmExecutorShutdown = () -> {
                exec.shutdown();
                try {
                    if (!exec.awaitTermination(2, TimeUnit.SECONDS)) {
                        exec.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    exec.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            };
        }
        return vmExecutor;
    }

    /**
     * Set an override network provider. When set, this provider is used
     * instead of the default NetManager in setupProviders().
     * Used by player-wasm to substitute FetchNetManager for browser fetch().
     */
    public void setNetProvider(NetBuiltins.NetProvider provider) {
        this.overrideNetProvider = provider;
    }

    /**
     * Dump information about loaded scripts for debugging.
     */
    public void dumpScriptInfo() {
        if (file == null) {
            System.out.println("[Player] No file loaded");
            return;
        }

        ScriptNamesChunk names = file.getScriptNames();
        System.out.println("[Player] === Script Summary ===");
        System.out.println("[Player] Total scripts: " + file.getScripts().size());

        int movieScripts = 0;
        int behaviors = 0;
        int parents = 0;
        int unknown = 0;

        for (ScriptChunk script : file.getScripts()) {
            String typeName = script.getScriptType() != null ? script.getScriptType().name() : "null";
            switch (script.getScriptType()) {
                case MOVIE_SCRIPT -> movieScripts++;
                case BEHAVIOR -> behaviors++;
                case PARENT -> parents++;
                default -> unknown++;
            }

            // List handlers in movie scripts
            if (script.getScriptType() == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                System.out.println("[Player] Movie script #" + script.id() + " handlers:");
                for (ScriptChunk.Handler handler : script.handlers()) {
                    String handlerName = names != null ? names.getName(handler.nameId()) : "name#" + handler.nameId();
                    System.out.println("[Player]   - " + handlerName);
                }
            }
        }

        System.out.println("[Player] Movie scripts: " + movieScripts);
        System.out.println("[Player] Behaviors: " + behaviors);
        System.out.println("[Player] Parent scripts: " + parents);
        System.out.println("[Player] Unknown type: " + unknown);
        System.out.println("[Player] ======================");
    }

    // Frame labels (delegated to navigator)

    public int getFrameForLabel(String label) {
        return frameContext.getNavigator().getFrameForLabel(label);
    }

    public Set<String> getFrameLabels() {
        return frameContext.getNavigator().getFrameLabels();
    }

    // Playback control

    /**
     * Start playback from the beginning.
     * This runs synchronously - use playAsync() when debugger is enabled.
     */
    public void play() {
        boolean wasStopped = state == PlayerState.STOPPED;
        state = PlayerState.PLAYING;  // Set first so tick() works even if prepareMovie errors
        if (wasStopped) {
            prepareMovie();
        }
        log("play()");
    }

    /**
     * Start playback asynchronously on background thread.
     * Use this when the debugger is enabled to prevent UI freezing.
     * @param onReady Callback when movie is prepared and ready to play (called on VM thread)
     */
    public void playAsync(Runnable onReady) {
        if (state != PlayerState.STOPPED) {
            state = PlayerState.PLAYING;
            if (onReady != null) onReady.run();
            return;
        }

        vmRunning = true;
        getVmExecutor().submit(() -> {
            try {
                prepareMovie();
                state = PlayerState.PLAYING;
                log("playAsync()");
            } finally {
                vmRunning = false;
                if (onReady != null) {
                    onReady.run();
                }
            }
        });
    }

    /**
     * Pause playback at the current frame.
     */
    public void pause() {
        if (state == PlayerState.PLAYING) {
            state = PlayerState.PAUSED;
            log("pause()");
        }
    }

    /**
     * Resume playback from a paused state.
     */
    public void resume() {
        if (state == PlayerState.PAUSED) {
            state = PlayerState.PLAYING;
            log("resume()");
        }
    }

    /**
     * Stop playback and reset to frame 1.
     */
    public void stop() {
        if (state != PlayerState.STOPPED) {
            log("stop()");
            setupProviders();
            try {
                // stopMovie -> timeout targets first, then movie scripts
                timeoutManager.dispatchSystemEvent(vm, "stopMovie");
                frameContext.getEventDispatcher().dispatchToMovieScripts(PlayerEvent.STOP_MOVIE, List.of());
            } finally {
                clearProviders();
            }
            frameContext.reset();
            stageRenderer.reset();
            timeoutManager.clear();
            playerTraceListener.reset();
            state = PlayerState.STOPPED;
        }
    }

    /**
     * Go to a specific frame.
     */
    public void goToFrame(int frame) {
        frameContext.goToFrame(frame);
    }

    /**
     * Go to a labeled frame.
     */
    public void goToLabel(String label) {
        frameContext.goToLabel(label);
    }

    /**
     * Step forward one frame (manual advance).
     * This runs synchronously - use stepFrameAsync() when debugger is enabled.
     */
    public void stepFrame() {
        if (state == PlayerState.STOPPED) {
            prepareMovie();
            state = PlayerState.PAUSED;
        }

        setupProviders();
        try {
            frameContext.executeFrame();
            timeoutManager.processTimeouts(vm, System.currentTimeMillis());
            frameContext.advanceFrame();
        } finally {
            clearProviders();
        }
    }

    /**
     * Step forward one frame asynchronously on background thread.
     * Use this when the debugger is enabled to prevent UI freezing.
     * @param onComplete Callback when frame is complete (called on VM thread)
     */
    public void stepFrameAsync(Runnable onComplete) {
        if (vmRunning) {
            return;
        }

        vmRunning = true;
        getVmExecutor().submit(() -> {
            try {
                if (state == PlayerState.STOPPED) {
                    prepareMovie();
                    state = PlayerState.PAUSED;
                }

                // Update debug controller with current globals
                if (debugController != null) {
                    debugController.setGlobalsSnapshot(vm.getGlobals());
                }

                // Execute frames in a loop - if stepping completes without pausing
                // (e.g., handler/frame ended on last instruction), continue to
                // the next frame so stepping can pause on its first instruction
                do {
                    setupProviders();
                    try {
                        processInputEvents();
                        xtraManager.tickAll();
                        frameContext.executeFrame();
                        timeoutManager.processTimeouts(vm, System.currentTimeMillis());
                        frameContext.advanceFrame();
                    } finally {
                        clearProviders();
                    }
                } while (debugController != null && debugController.isAwaitingStepContinuation());
            } finally {
                vmRunning = false;
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    // Frame execution (called by external timer/loop)

    /**
     * Execute one frame tick. Call this at tempo rate.
     * This is synchronous - for debugging support, use tickAsync() instead.
     * @return true if still playing, false if stopped/paused
     */
    public boolean tick() {
        if (state != PlayerState.PLAYING) {
            return state == PlayerState.PAUSED;
        }

        // Update debug controller with current globals
        if (debugController != null) {
            debugController.setGlobalsSnapshot(vm.getGlobals());
        }

        // Set up thread-local providers before script execution
        setupProviders();
        // Arm the tick-level deadline if configured (default 30s, 0 = disabled).
        long deadlineMs = vm.getTickDeadlineMs();
        if (deadlineMs > 0) {
            vm.setTickDeadline(System.currentTimeMillis() + deadlineMs);
        }
        try {
            // Process queued mouse/keyboard input events before frame execution
            processInputEvents();
            // Process pending Xtra callbacks (e.g., Multiuser Xtra auto-fires
            // setNetMessageHandler callbacks when messages arrive)
            xtraManager.tickAll();
            frameContext.executeFrame();
            timeoutManager.processTimeouts(vm, System.currentTimeMillis());
            frameContext.advanceFrame();
        } finally {
            vm.setTickDeadline(0);
            clearProviders();
        }
        return true;
    }

    /**
     * Execute one frame tick asynchronously on a background thread.
     * Use this when the debugger is enabled to allow the VM to be paused
     * without blocking the UI thread.
     * @param onComplete Callback invoked on completion (on the VM thread)
     */
    public void tickAsync(Runnable onComplete) {
        if (state != PlayerState.PLAYING || vmRunning) {
            return;
        }

        // Update debug controller with current globals
        if (debugController != null) {
            debugController.setGlobalsSnapshot(vm.getGlobals());
        }

        vmRunning = true;
        getVmExecutor().submit(() -> {
            try {
                // Execute frames in a loop - if stepping completes without pausing
                // (e.g., handler/frame ended on last instruction), continue to
                // the next frame so stepping can pause on its first instruction
                do {
                    setupProviders();
                    try {
                        processInputEvents();
                        xtraManager.tickAll();
                        frameContext.executeFrame();
                        timeoutManager.processTimeouts(vm, System.currentTimeMillis());
                        frameContext.advanceFrame();
                    } finally {
                        clearProviders();
                    }
                } while (debugController != null && debugController.isAwaitingStepContinuation());
            } finally {
                vmRunning = false;
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    /**
     * Set up thread-local providers for builtin functions.
     */
    private void setupProviders() {
        NetBuiltins.setProvider(overrideNetProvider != null ? overrideNetProvider : netManager);
        XtraBuiltins.setManager(xtraManager);
        MoviePropertyProvider.setProvider(movieProperties);
        SpritePropertyProvider.setProvider(spriteProperties);
        CastLibProvider.setProvider(castLibManager);
        TimeoutProvider.setProvider(timeoutManager);
        ExternalParamProvider.setProvider(externalParamProvider);
        SoundProvider.setProvider(soundManager);
    }

    /**
     * Clear thread-local providers after script execution.
     */
    private void clearProviders() {
        NetBuiltins.clearProvider();
        XtraBuiltins.clearManager();
        MoviePropertyProvider.clearProvider();
        SpritePropertyProvider.clearProvider();
        CastLibProvider.clearProvider();
        TimeoutProvider.clearProvider();
        ExternalParamProvider.clearProvider();
        SoundProvider.clearProvider();
    }

    // Movie lifecycle - follows dirplayer-rs flow exactly

    private void prepareMovie() {
        log("prepareMovie()");

        setupProviders();
        try {
            // 0. Initiate fetch of all external casts (async network requests)
            preloadAllCasts();

            // 1. Preload casts with preloadMode=2 (BeforeFrameOne / MovieLoaded)
            // dirplayer-rs: Mode 2 = BeforeFrameOne, Mode 1 = AfterFrameOne
            castLibManager.preloadCasts(2);

            // 2. prepareMovie -> timeout targets first, then movie scripts
            timeoutManager.dispatchSystemEvent(vm, "prepareMovie");
            frameContext.getEventDispatcher().dispatchToMovieScripts(PlayerEvent.PREPARE_MOVIE, List.of());

            // 3. Initialize sprites for frame 1
            frameContext.initializeFirstFrame();

            // 4. beginSprite events
            frameContext.dispatchBeginSpriteEvents();

            // 5. prepareFrame -> timeout targets first, then behaviors + frame/movie scripts
            timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
            frameContext.getEventDispatcher().dispatchGlobalEvent(PlayerEvent.PREPARE_FRAME, List.of());

            // 6. startMovie -> movie scripts first, then timeout targets
            frameContext.getEventDispatcher().dispatchToMovieScripts(PlayerEvent.START_MOVIE, List.of());
            timeoutManager.dispatchSystemEvent(vm, "startMovie");

            // 7. enterFrame -> dispatched to all behaviors + frame/movie scripts
            frameContext.getEventDispatcher().dispatchGlobalEvent(PlayerEvent.ENTER_FRAME, List.of());

            // 8. exitFrame -> timeout targets first, then behaviors + frame/movie scripts
            timeoutManager.dispatchSystemEvent(vm, "exitFrame");
            frameContext.getEventDispatcher().dispatchGlobalEvent(PlayerEvent.EXIT_FRAME, List.of());

            // 9. Preload casts with preloadMode=1 (AfterFrameOne)
            castLibManager.preloadCasts(1);

            // Frame loop will handle subsequent frames
        } finally {
            clearProviders();
        }
    }

    /**
     * Called when Lingo sets castLib.fileName — loads the data from NetManager's
     * download cache into the specific cast by number.
     */
    private void loadCastFromNetCache(int castLibNumber, String fileName) {
        if (netManager == null) return;
        byte[] data = netManager.getCachedData(fileName);
        if (data != null) {
            if (castLibManager.setExternalCastData(castLibNumber, data)) {
                bitmapCache.clear();
                if (castLoadedListener != null) {
                    castLoadedListener.run();
                }
            }
        }
    }

    /**
     * Fire a test error through the VM's alertHook mechanism.
     * Sets up providers, fires the hook, then cleans up.
     * Used to verify error dialog appearance.
     * @return true if alertHook handled the error
     */
    public boolean fireTestError(String errorMsg) {
        setupProviders();
        try {
            return vm.fireAlertHook(errorMsg);
        } finally {
            clearProviders();
        }
    }

    // --- Input handling ---

    /**
     * Get the input state for reading mouse/keyboard state.
     */
    public InputState getInputState() {
        return inputState;
    }

    /**
     * Update the mouse position (stage coordinates).
     * Called by the UI layer on mouse move.
     */
    public void onMouseMove(int stageX, int stageY) {
        inputState.setMousePosition(stageX, stageY);
        // Update rollover sprite
        int hit = HitTester.hitTest(stageRenderer, getCurrentFrame(), stageX, stageY);
        inputState.setRolloverSprite(hit);
    }

    /**
     * Get the cursor type for the current mouse position.
     * Returns Director cursor codes: -1=arrow, 0=default, 1=ibeam, 2=crosshair, 3=crossbar, 4=wait
     * Returns 5 for custom bitmap cursor (call getCursorBitmap() to get the image).
     */
    public int getCursorAtMouse() {
        int mouseH = inputState.getMouseH();
        int mouseV = inputState.getMouseV();
        int hitChannel = HitTester.hitTest(stageRenderer, getCurrentFrame(), mouseH, mouseV);
        if (hitChannel > 0) {
            SpriteState sprite = stageRenderer.getSpriteRegistry().get(hitChannel);
            if (sprite != null) {
                // Check for bitmap cursor first
                if (sprite.hasBitmapCursor()) {
                    return 5; // custom bitmap cursor
                }
                // Auto-detect editable text fields and buttons — Director shows ibeam
                // for editable text/field members and pointer for buttons, regardless
                // of sprite cursor. The Event Broker Behavior sets cursor=-1 (arrow) on
                // all window sprites, but Director still overrides for these types.
                int castLibNum = sprite.getEffectiveCastLib();
                int memberNum = sprite.getEffectiveCastMember();
                if (memberNum > 0) {
                    CastMember member = castLibManager.getDynamicMember(castLibNum, memberNum);
                    if (member != null) {
                        if (member.isEditable() && member.getMemberType() == MemberType.TEXT) {
                            return 1; // ibeam for editable text
                        }
                        if (member.getMemberType() == MemberType.BUTTON) {
                            return 6; // pointer/hand for buttons
                        }
                    }
                }
                int spriteCursor = sprite.getCursor();
                if (spriteCursor != 0) {
                    return spriteCursor;
                }
            }
        }
        return -1; // default arrow when not over any sprite
    }

    /**
     * Get the current custom cursor bitmap for rendering.
     * Returns null if no bitmap cursor is active.
     * The bitmap's regPoint defines the hotspot offset.
     */
    public Bitmap getCursorBitmap() {
        int mouseH = inputState.getMouseH();
        int mouseV = inputState.getMouseV();
        int hitChannel = HitTester.hitTest(stageRenderer, getCurrentFrame(), mouseH, mouseV);
        if (hitChannel <= 0) return null;

        SpriteState sprite = stageRenderer.getSpriteRegistry().get(hitChannel);
        if (sprite == null || !sprite.hasBitmapCursor()) return null;

        int encodedMember = sprite.getCursorMemberNum();
        int castLibNum = (encodedMember >> 16) & 0xFFFF;
        int memberNum = encodedMember & 0xFFFF;
        if (castLibNum == 0) castLibNum = 1; // default cast lib

        // Try to find and decode the cursor member bitmap
        CastMemberChunk chunk = castLibManager.getCastMember(castLibNum, memberNum);
        if (chunk == null) return null;

        return decodeBitmap(chunk).orElse(null);
    }

    /**
     * Get the cursor bitmap's registration point (hotspot).
     * Returns [regX, regY] or null if no bitmap cursor is active.
     */
    public int[] getCursorRegPoint() {
        int mouseH = inputState.getMouseH();
        int mouseV = inputState.getMouseV();
        int hitChannel = HitTester.hitTest(stageRenderer, getCurrentFrame(), mouseH, mouseV);
        if (hitChannel <= 0) return null;

        SpriteState sprite = stageRenderer.getSpriteRegistry().get(hitChannel);
        if (sprite == null || !sprite.hasBitmapCursor()) return null;

        int encodedMember = sprite.getCursorMemberNum();
        int castLibNum = (encodedMember >> 16) & 0xFFFF;
        int memberNum = encodedMember & 0xFFFF;
        if (castLibNum == 0) castLibNum = 1;

        CastMemberChunk chunk = castLibManager.getCastMember(castLibNum, memberNum);
        if (chunk == null) return new int[]{0, 0};

        return new int[]{chunk.regPointX(), chunk.regPointY()};
    }

    /**
     * Handle a mouse button press. Queues a MOUSE_DOWN event for dispatch during tick.
     * Called by the UI layer.
     */
    public void onMouseDown(int stageX, int stageY, boolean rightButton) {
        inputState.setMousePosition(stageX, stageY);
        if (rightButton) {
            inputState.setRightMouseDown(true);
            inputState.queueEvent(InputEvent.rightMouseDown(stageX, stageY));
        } else {
            inputState.setMouseDown(true);
            int hit = HitTester.hitTest(stageRenderer, getCurrentFrame(), stageX, stageY);
            inputState.setClickOnSprite(hit);
            inputState.setClickLoc(stageX, stageY);
            inputState.queueEvent(InputEvent.mouseDown(stageX, stageY));
        }
    }

    /**
     * Handle a mouse button release. Queues a MOUSE_UP event for dispatch during tick.
     * Called by the UI layer.
     */
    public void onMouseUp(int stageX, int stageY, boolean rightButton) {
        inputState.setMousePosition(stageX, stageY);
        if (rightButton) {
            inputState.setRightMouseDown(false);
            inputState.queueEvent(InputEvent.rightMouseUp(stageX, stageY));
        } else {
            inputState.setMouseDown(false);
            inputState.queueEvent(InputEvent.mouseUp(stageX, stageY));
        }
    }

    /**
     * Handle a key press. Queues a KEY_DOWN event for dispatch during tick.
     * @param directorKeyCode Director Mac virtual keycode (use DirectorKeyCodes to convert)
     * @param keyChar the character string (e.g. "a", "A", "\r")
     * @param shift shift modifier state
     * @param ctrl control/command modifier state
     * @param alt alt/option modifier state
     */
    public void onKeyDown(int directorKeyCode, String keyChar, boolean shift, boolean ctrl, boolean alt) {
        inputState.setLastKey(keyChar);
        inputState.setLastKeyCode(directorKeyCode);
        inputState.setShiftDown(shift);
        inputState.setControlDown(ctrl);
        inputState.setAltDown(alt);
        inputState.queueEvent(InputEvent.keyDown(directorKeyCode, keyChar));
    }

    /**
     * Handle a key release. Queues a KEY_UP event for dispatch during tick.
     */
    public void onKeyUp(int directorKeyCode, String keyChar, boolean shift, boolean ctrl, boolean alt) {
        inputState.setShiftDown(shift);
        inputState.setControlDown(ctrl);
        inputState.setAltDown(alt);
        inputState.queueEvent(InputEvent.keyUp(directorKeyCode, keyChar));
    }

    /**
     * Process queued input events. Called at the beginning of each tick
     * before frame execution, so Lingo scripts see the events.
     * Also dispatches mouseEnter/mouseLeave/mouseWithin based on rollover state.
     */
    private void processInputEvents() {
        InputEvent event;
        boolean hadEvents = false;
        while ((event = inputState.pollEvent()) != null) {
            dispatchInputEvent(event);
            hadEvents = true;
        }

        // Dispatch mouseEnter/mouseLeave/mouseWithin based on current rollover sprite
        dispatchRolloverEvents();

        // Bump sprite revision so WASM SoftwareRenderer re-renders after input
        // (input handlers may change member.text or other visual properties)
        if (hadEvents) {
            stageRenderer.getSpriteRegistry().bumpRevision();
        }
    }

    /**
     * Dispatch mouseEnter, mouseLeave, and mouseWithin events based on rollover tracking.
     * In Director, these fire every frame:
     * - mouseLeave: when the mouse leaves a sprite's rect
     * - mouseEnter: when the mouse enters a new sprite's rect
     * - mouseWithin: every frame while the mouse is within a sprite's rect
     */
    private void dispatchRolloverEvents() {
        EventDispatcher dispatcher = frameContext.getEventDispatcher();
        int currentRollover = inputState.getRolloverSprite();

        if (currentRollover != previousRolloverSprite) {
            // Mouse moved to a different sprite (or off all sprites)
            if (previousRolloverSprite > 0) {
                dispatcher.dispatchSpriteEvent(previousRolloverSprite, PlayerEvent.MOUSE_LEAVE, List.of());
            }
            if (currentRollover > 0) {
                dispatcher.dispatchSpriteEvent(currentRollover, PlayerEvent.MOUSE_ENTER, List.of());
            }
            previousRolloverSprite = currentRollover;
        }

        // mouseWithin fires every frame while mouse is over a sprite
        if (currentRollover > 0) {
            dispatcher.dispatchSpriteEvent(currentRollover, PlayerEvent.MOUSE_WITHIN, List.of());
        }
    }

    /**
     * Dispatch a single input event to the appropriate Lingo handlers.
     */
    private void dispatchInputEvent(InputEvent event) {
        EventDispatcher dispatcher = frameContext.getEventDispatcher();
        switch (event.type()) {
            case MOUSE_DOWN -> {
                int hitSprite = HitTester.hitTest(stageRenderer, getCurrentFrame(),
                        event.stageX(), event.stageY());
                // Director D6+: if the previously clicked sprite is different from
                // the current one, send mouseUpOutSide to the old sprite (ScummVM behavior).
                int lastClicked = inputState.getClickOnSprite();
                if (lastClicked > 0 && lastClicked != hitSprite) {
                    dispatcher.dispatchSpriteEvent(lastClicked, "mouseUpOutSide", List.of());
                }
                // Track which sprite was clicked so mouseUp can target it
                inputState.setClickOnSprite(hitSprite);
                // Built-in Director behavior: clicking on an editable text/field sprite
                // automatically sets keyboardFocusSprite to that sprite's channel.
                // Clicking elsewhere clears the keyboard focus.
                autoFocusEditableField(hitSprite);
                if (hitSprite > 0) {
                    dispatcher.dispatchSpriteEvent(hitSprite, PlayerEvent.MOUSE_DOWN, List.of());
                }
                dispatcher.dispatchGlobalEvent(PlayerEvent.MOUSE_DOWN, List.of());
            }
            case MOUSE_UP -> {
                // Director dispatches mouseUp to the sprite currently under the mouse,
                // NOT the sprite that received mouseDown (confirmed via ScummVM).
                // mouseUpOutSide is dispatched on the NEXT mouseDown if the clicked
                // sprite has changed (see MOUSE_DOWN case above).
                int hitSprite = HitTester.hitTest(stageRenderer, getCurrentFrame(),
                        event.stageX(), event.stageY());
                if (hitSprite > 0) {
                    dispatcher.dispatchSpriteEvent(hitSprite, PlayerEvent.MOUSE_UP, List.of());
                }
                dispatcher.dispatchGlobalEvent(PlayerEvent.MOUSE_UP, List.of());
            }
            case RIGHT_MOUSE_DOWN -> {
                dispatcher.dispatchGlobalEvent(PlayerEvent.RIGHT_MOUSE_DOWN, List.of());
            }
            case RIGHT_MOUSE_UP -> {
                dispatcher.dispatchGlobalEvent(PlayerEvent.RIGHT_MOUSE_UP, List.of());
            }
            case KEY_DOWN -> {
                // Restore per-event key state so Lingo's "the key" / "the keyCode" read correctly
                inputState.setLastKey(event.keyChar());
                inputState.setLastKeyCode(event.keyCode());
                int focusSprite = inputState.getKeyboardFocusSprite();
                // Built-in editable field keyboard handling (Director inserts typed chars into member.text)
                if (focusSprite > 0) {
                    handleEditableFieldInput(focusSprite, event.keyChar());
                    dispatcher.dispatchSpriteEvent(focusSprite, PlayerEvent.KEY_DOWN, List.of());
                }
                dispatcher.dispatchGlobalEvent(PlayerEvent.KEY_DOWN, List.of());
            }
            case KEY_UP -> {
                inputState.setLastKey(event.keyChar());
                inputState.setLastKeyCode(event.keyCode());
                int focusSprite = inputState.getKeyboardFocusSprite();
                if (focusSprite > 0) {
                    dispatcher.dispatchSpriteEvent(focusSprite, PlayerEvent.KEY_UP, List.of());
                }
                dispatcher.dispatchGlobalEvent(PlayerEvent.KEY_UP, List.of());
            }
        }
    }

    /**
     * Built-in Director behavior: clicking on an editable text/field sprite
     * automatically sets keyboardFocusSprite to that sprite's channel.
     * Clicking on a non-editable sprite or empty stage clears focus.
     */
    private void autoFocusEditableField(int hitChannel) {
        if (hitChannel > 0) {
            SpriteState sprite = stageRenderer.getSpriteRegistry().get(hitChannel);
            if (sprite != null) {
                int castLibNum = sprite.getEffectiveCastLib();
                int memberNum = sprite.getEffectiveCastMember();
                if (memberNum > 0) {
                    CastMember member = castLibManager.getDynamicMember(castLibNum, memberNum);
                    if (member != null && member.isEditable()
                            && (member.getMemberType() == MemberType.TEXT)) {
                        inputState.setKeyboardFocusSprite(hitChannel);
                        return;
                    }
                }
            }
        }
        // Clicked on non-editable sprite or empty stage — clear focus
        inputState.setKeyboardFocusSprite(0);
    }

    /**
     * Built-in Director behavior: when keyboardFocusSprite is set and the sprite's
     * member is an editable field/text, the engine inserts typed characters into member.text.
     */
    private void handleEditableFieldInput(int channel, String keyChar) {
        SpriteState sprite = stageRenderer.getSpriteRegistry().get(channel);
        if (sprite == null) return;

        int castLibNum = sprite.getEffectiveCastLib();
        int memberNum = sprite.getEffectiveCastMember();
        if (memberNum <= 0) return;

        CastMember member = castLibManager.getDynamicMember(castLibNum, memberNum);
        if (member == null) return;

        MemberType type = member.getMemberType();
        if (type != MemberType.TEXT && type != MemberType.BUTTON) return;
        if (!member.isEditable()) return;

        String text = member.getTextContent();
        if (text == null) text = "";

        if (keyChar.equals("\b") || keyChar.isEmpty()) {
            // Backspace: check the key code
            if (inputState.getLastKeyCode() == 51) { // Mac kVK_Delete (backspace)
                if (!text.isEmpty()) {
                    member.setDynamicText(text.substring(0, text.length() - 1));
                }
            }
        } else if (keyChar.length() == 1) {
            member.setDynamicText(text + keyChar);
        }
    }

    /**
     * Shutdown the player and release resources.
     * Call this when the player is no longer needed.
     */
    public void shutdown() {
        stop();
        if (netManager != null) {
            netManager.shutdown();
        }

        // Shutdown cast parser executor (only exists in desktop player)
        if (castParserShutdown != null) {
            castParserShutdown.run();
        }

        // Reset debug controller (releases any blocked threads)
        if (debugController != null) {
            debugController.reset();
        }

        // Shutdown VM executor (only if it was created)
        if (vmExecutorShutdown != null) {
            vmExecutorShutdown.run();
        }
    }

    // Debug logging

    private void log(String message) {
        if (debugEnabled) {
            System.out.println("[Player] " + message);
        }
    }

    /**
     * Delegating TraceListener that intercepts constructObjectManager handler exit
     * to auto-create the fuse_frameProxy timeout. This enables the Fuse Object Manager
     * to receive prepareFrame system events each frame (the "frameProxy" trick).
     *
     * An optional delegate (typically a DebugControllerApi) receives all callbacks
     * so debugging works alongside this interception.
     */
    private class PlayerTraceListener implements TraceListener {

        private TraceListener delegate;
        private boolean frameProxyCreated;

        void setDelegate(TraceListener delegate) {
            this.delegate = delegate;
        }

        void reset() {
            frameProxyCreated = false;
        }

        @Override
        public void onHandlerEnter(HandlerInfo info) {
            if (delegate != null) delegate.onHandlerEnter(info);
        }

        @Override
        public void onHandlerExit(HandlerInfo info, Datum returnValue) {
            // Intercept: when constructObjectManager returns a ScriptInstance,
            // register it as a timeout target so it receives prepareFrame events.
            String hn = info.handlerName();
            if (!frameProxyCreated
                    && hn.equalsIgnoreCase("constructObjectManager")
                    && returnValue instanceof Datum.ScriptInstance) {
                frameProxyCreated = true;
                timeoutManager.createTimeout(
                        "fuse_frameProxy", Integer.MAX_VALUE, "null", returnValue);
            }

            if (delegate != null) delegate.onHandlerExit(info, returnValue);
        }

        @Override
        public boolean needsInstructionTrace() {
            return delegate != null && delegate.needsInstructionTrace();
        }

        @Override
        public void onInstruction(InstructionInfo info) {
            if (delegate != null) delegate.onInstruction(info);
        }

        @Override
        public void onVariableSet(String type, String name, Datum value) {
            if (delegate != null) delegate.onVariableSet(type, name, value);
        }

        @Override
        public void onError(String message, Exception error) {
            if (delegate != null) delegate.onError(message, error);
            if (errorListener != null && error instanceof LingoException le) {
                errorListener.accept(message, le);
            }
        }

        @Override
        public void onDebugMessage(String message) {
            if (delegate != null) delegate.onDebugMessage(message);
        }
    }


}
