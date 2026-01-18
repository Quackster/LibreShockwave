package com.libreshockwave.execution;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.player.Score;
import com.libreshockwave.player.Sprite;
import com.libreshockwave.vm.LingoVM;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Director movie player.
 * Handles movie playback, frame navigation, and event dispatching.
 * Uses LingoVM from SDK for bytecode execution.
 * Matches dirplayer-rs DirPlayer pattern.
 */
public class DirPlayer {

    /**
     * Playback state.
     */
    public enum PlayState {
        STOPPED,
        PLAYING,
        PAUSED
    }

    /**
     * Movie event types.
     */
    public enum MovieEvent {
        PREPARE_MOVIE("prepareMovie"),
        START_MOVIE("startMovie"),
        STOP_MOVIE("stopMovie"),
        PREPARE_FRAME("prepareFrame"),
        ENTER_FRAME("enterFrame"),
        EXIT_FRAME("exitFrame"),
        IDLE("idle");

        private final String handlerName;

        MovieEvent(String handlerName) {
            this.handlerName = handlerName;
        }

        public String handlerName() {
            return handlerName;
        }
    }

    private DirectorFile file;
    private CastManager castManager;
    private LingoVM vm;
    private NetManager netManager;
    private Score score;
    private String baseUrl;

    private PlayState state = PlayState.STOPPED;
    private int currentFrame = 1;
    private int lastFrame = 1;
    private int tempo = 15;

    // Event dispatch tracking to prevent re-entrancy
    private boolean inEventDispatch = false;
    private Integer pendingFrame = null;  // Deferred frame navigation

    // Frame navigation recursion guard
    private int frameNavigationDepth = 0;
    private static final int MAX_FRAME_NAVIGATION_DEPTH = 100;

    // Sprite state
    private final Map<Integer, SpriteState> sprites = new HashMap<>();

    // Event listeners
    private final List<EventListener> eventListeners = new ArrayList<>();

    public interface EventListener {
        void onEvent(MovieEvent event, int frame);
    }

    public DirPlayer() {
        this.netManager = new NetManager();
    }

    public DirPlayer(DirectorFile file) {
        this();
        loadMovie(file);
    }

    public NetManager getNetManager() {
        return netManager;
    }

    // Loading

    /**
     * Load a movie from a file path.
     */
    public void loadMovie(Path path) throws IOException {
        loadMovie(DirectorFile.load(path));
        // Set base path from file location
        if (path.getParent() != null) {
            baseUrl = path.getParent().toUri().toString();
            netManager.setBasePath(baseUrl);
        }
    }

    /**
     * Load a movie from byte array.
     */
    public void loadMovie(byte[] data) throws IOException {
        loadMovie(DirectorFile.load(data));
    }

    /**
     * Load a movie from a DirectorFile.
     */
    public void loadMovie(DirectorFile file) {
        this.file = file;
        this.castManager = file.createCastManager();
        this.vm = new LingoVM(file);

        // Inject NetManager into VM and CastManager
        vm.setNetManager(netManager);
        vm.setCastManager(castManager);
        castManager.setNetManager(netManager);

        // Create Score from parsed ScoreChunk
        this.score = file.createScore();

        // Set tempo from config
        if (file.getConfig() != null) {
            this.tempo = file.getConfig().tempo();
            if (this.tempo <= 0) this.tempo = 15;
        }

        // Initialize VM with player-specific handlers
        registerPlayerBuiltins();

        // Reset state
        this.state = PlayState.STOPPED;
        this.currentFrame = 1;
        this.sprites.clear();

        // Determine last frame from score
        if (score != null && score.getFrameCount() > 0) {
            this.lastFrame = score.getFrameCount();
        } else {
            this.lastFrame = 1;
        }
    }

    /**
     * Load a movie from an HTTP URL.
     * Sets base path for resolving relative cast URLs.
     */
    public CompletableFuture<Void> loadMovieFromUrl(String url) {
        // Set base path from movie URL
        int lastSlash = url.lastIndexOf('/');
        this.baseUrl = lastSlash > 0 ? url.substring(0, lastSlash + 1) : url;
        netManager.setBasePath(baseUrl);

        // Download the movie file
        int taskId = netManager.preloadNetThing(url);

        return netManager.awaitTask(taskId).thenAccept(result -> {
            if (result.isSuccess()) {
                try {
                    byte[] data = result.getData();
                    DirectorFile dirFile = DirectorFile.load(data);
                    loadMovie(dirFile);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load movie from URL: " + e.getMessage(), e);
                }
            } else {
                throw new RuntimeException("Failed to download movie from: " + url +
                    " (error " + result.getErrorCode() + ")");
            }
        });
    }

    /**
     * Get the base URL for resolving relative paths.
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Set the base URL for resolving relative paths.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        if (netManager != null) {
            netManager.setBasePath(baseUrl);
        }
    }

    /**
     * Register player-specific built-in handlers.
     * These extend the SDK's LingoVM with player functionality.
     */
    private void registerPlayerBuiltins() {
        // Frame navigation
        vm.registerBuiltin("go", (vmRef, args) -> {
            if (!args.isEmpty()) {
                Datum target = args.get(0);
                if (target.isInt()) {
                    goToFrame(target.intValue());
                } else if (target.isString()) {
                    goToLabel(target.stringValue());
                }
            }
            return Datum.voidValue();
        });

        vm.registerBuiltin("play", (vmRef, args) -> {
            play();
            return Datum.voidValue();
        });

        vm.registerBuiltin("stop", (vmRef, args) -> {
            stop();
            return Datum.voidValue();
        });

        vm.registerBuiltin("pause", (vmRef, args) -> {
            pause();
            return Datum.voidValue();
        });

        // Frame properties
        vm.registerBuiltin("frame", (vmRef, args) -> Datum.of(currentFrame));

        // Tempo
        vm.registerBuiltin("puppetTempo", (vmRef, args) -> {
            if (!args.isEmpty()) {
                tempo = args.get(0).intValue();
                if (tempo <= 0) tempo = 15;
            }
            return Datum.voidValue();
        });

        // Update stage
        vm.registerBuiltin("updateStage", (vmRef, args) -> {
            updateStage();
            return Datum.voidValue();
        });

        // Cast preloading
        vm.registerBuiltin("preload", (vmRef, args) -> {
            return Datum.voidValue();
        });
    }

    // Playback control

    /**
     * Start movie playback.
     */
    public void play() {
        if (state == PlayState.STOPPED) {
            dispatchEvent(MovieEvent.PREPARE_MOVIE);
            dispatchEvent(MovieEvent.START_MOVIE);
        }
        state = PlayState.PLAYING;
    }

    /**
     * Stop movie playback.
     */
    public void stop() {
        if (state != PlayState.STOPPED) {
            dispatchEvent(MovieEvent.STOP_MOVIE);
        }
        state = PlayState.STOPPED;
        currentFrame = 1;
    }

    /**
     * Pause movie playback.
     */
    public void pause() {
        state = PlayState.PAUSED;
    }

    /**
     * Step to the next frame.
     */
    public void nextFrame() {
        if (currentFrame < lastFrame) {
            goToFrame(currentFrame + 1);
        }
    }

    /**
     * Step to the previous frame.
     */
    public void prevFrame() {
        if (currentFrame > 1) {
            goToFrame(currentFrame - 1);
        }
    }

    /**
     * Go to a specific frame.
     * If called during event dispatch, defers the navigation to after the current dispatch completes.
     */
    public void goToFrame(int frame) {
        if (frame < 1) frame = 1;
        if (frame > lastFrame) frame = lastFrame;

        // If we're in event dispatch, defer navigation to prevent recursion
        if (inEventDispatch) {
            pendingFrame = frame;
            return;
        }

        goToFrameImmediate(frame);
    }

    /**
     * Internal method to navigate to a frame immediately (only when not in event dispatch).
     */
    private void goToFrameImmediate(int frame) {
        // Guard against infinite frame navigation recursion
        if (frameNavigationDepth >= MAX_FRAME_NAVIGATION_DEPTH) {
            System.err.println("ERROR: Maximum frame navigation depth exceeded (" + MAX_FRAME_NAVIGATION_DEPTH +
                ") - possible infinite loop between frames. Current frame: " + currentFrame + ", target: " + frame);
            return;
        }

        frameNavigationDepth++;
        try {
            dispatchEvent(MovieEvent.EXIT_FRAME);

            currentFrame = frame;

            // Load sprites from score for this frame
            loadSpritesFromScore(currentFrame);

            dispatchEvent(MovieEvent.PREPARE_FRAME);

            // Execute frame script if present
            executeFrameScript(currentFrame);

            dispatchEvent(MovieEvent.ENTER_FRAME);
        } finally {
            frameNavigationDepth--;
        }
    }

    /**
     * Load sprites from the Score for a given frame.
     */
    private void loadSpritesFromScore(int frameNum) {
        if (score == null) return;

        Score.Frame frame = score.getFrame(frameNum);
        if (frame == null) return;

        // Update sprite states from score data
        for (Sprite sprite : frame.getSprites()) {
            SpriteState state = getSprite(sprite.getChannel());
            state.setLocH(sprite.getLocH());
            state.setLocV(sprite.getLocV());
            state.setWidth(sprite.getWidth());
            state.setHeight(sprite.getHeight());
            state.setInk(sprite.getInk());
            state.setBlend(sprite.getBlend());
            state.setVisible(sprite.isVisible());

            if (sprite.getCastMember() > 0) {
                state.setMember(new Datum.CastMemberRef(sprite.getCastLib(), sprite.getCastMember()));
            } else {
                state.setMember(null);
            }
        }
    }

    /**
     * Execute the frame script for a given frame (from Score channel 0).
     */
    private void executeFrameScript(int frameNum) {
        if (score == null) return;

        Score.Frame frame = score.getFrame(frameNum);
        if (frame == null || !frame.hasFrameScript()) return;

        int castLib = frame.getScriptCastLib();
        int castMember = frame.getScriptCastMember();

        // Find and execute the script
        ScriptChunk script = findScriptForCastMember(castLib, castMember);
        if (script != null && !script.handlers().isEmpty()) {
            try {
                vm.execute(script, script.handlers().get(0), new Datum[0]);
            } catch (Exception e) {
                System.err.println("Error executing frame script for frame " + frameNum + ": " + e.getMessage());
            }
        }
    }

    /**
     * Find a script chunk for a given cast member.
     */
    private ScriptChunk findScriptForCastMember(int castLib, int castMember) {
        for (ScriptChunk script : file.getScripts()) {
            if (script.id() == castMember) {
                return script;
            }
        }
        return null;
    }

    /**
     * Format a script identifier for display (member name if available, otherwise "#id").
     */
    private String formatScriptId(ScriptChunk script) {
        String name = this.vm.getScriptMemberName(script);
        if (name != null && !name.isEmpty()) {
            return "\"" + name + "\"";
        }
        return "#" + script.id();
    }

    /**
     * Go to a frame by label name.
     */
    public void goToLabel(String label) {
        if (score == null) {
            return;
        }

        int frameNum = score.getFrameByLabel(label);
        if (frameNum > 0) {
            goToFrame(frameNum);
        }
    }

    /**
     * Execute one frame tick.
     */
    public void tick() {
        if (state != PlayState.PLAYING) {
            return;
        }

        int frameBefore = currentFrame;
        dispatchEvent(MovieEvent.EXIT_FRAME);

        // Only advance if exitFrame didn't change the frame (via go command)
        if (currentFrame == frameBefore) {
            if (currentFrame < lastFrame) {
                currentFrame++;
            } else {
                currentFrame = 1;
            }
        }

        // Load sprites from score for new frame
        loadSpritesFromScore(currentFrame);

        dispatchEvent(MovieEvent.PREPARE_FRAME);

        // Execute frame script
        executeFrameScript(currentFrame);

        dispatchEvent(MovieEvent.ENTER_FRAME);
    }

    /**
     * Update the stage (trigger redraw).
     * Override in UI players for rendering.
     */
    public void updateStage() {
        // Default implementation - subclasses can override for rendering
    }

    // Event dispatching

    /**
     * Dispatch a movie event to all handlers.
     */
    public void dispatchEvent(MovieEvent event) {
        String handlerName = event.handlerName();

        for (EventListener listener : eventListeners) {
            listener.onEvent(event, currentFrame);
        }

        boolean wasInDispatch = inEventDispatch;
        inEventDispatch = true;
        try {
            executeHandlerIfExists(handlerName);
        } catch (Exception e) {
            System.err.println("Error in " + handlerName + ": " + e.getMessage());
        } finally {
            inEventDispatch = wasInDispatch;

            // If this was the outermost dispatch and there's a pending frame, navigate now
            if (!inEventDispatch && pendingFrame != null) {
                int targetFrame = pendingFrame;
                pendingFrame = null;
                // Only navigate if the target is different from current (prevents infinite loop)
                if (targetFrame != currentFrame) {
                    goToFrameImmediate(targetFrame);
                }
            }
        }
    }

    /**
     * Execute a handler by name across ALL scripts that have it.
     * Matches dirplayer-rs player_invoke_global_event / player_invoke_static_event.
     *
     * Event order:
     * 1. Frame script (if current frame has one and has this handler)
     * 2. All movie scripts in the main file
     * 3. All movie scripts in all casts (internal and external)
     */
    private void executeHandlerIfExists(String handlerName) {
        if (file == null) return;

        // Get nameId from main file's name table
        ScriptNamesChunk names = file.getScriptNames();
        int mainNameId = names != null ? names.findName(handlerName) : -1;

        // 1. Execute frame script first (if it has this handler)
        if (score != null && mainNameId >= 0) {
            Score.Frame frame = score.getFrame(currentFrame);
            if (frame != null && frame.hasFrameScript()) {
                ScriptChunk frameScript = findScriptForCastMember(
                    frame.getScriptCastLib(),
                    frame.getScriptCastMember()
                );
                if (frameScript != null) {
                    for (ScriptChunk.Handler handler : frameScript.handlers()) {
                        if (handler.nameId() == mainNameId) {
                            try {
                                vm.execute(frameScript, handler, new Datum[0]);
                            } catch (Exception e) {
                                System.err.println("Error executing " + handlerName + " in frame script: " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
            }
        }

        // 2. Execute in ALL movie scripts from main file
        if (mainNameId >= 0) {
            for (ScriptChunk script : file.getScripts()) {
                // Movie scripts have scriptType == MOVIE_SCRIPT
                // if (script.scriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) continue;

                for (ScriptChunk.Handler handler : script.handlers()) {
                    if (handler.nameId() == mainNameId) {
                        try {
                            vm.execute(script, handler, new Datum[0]);
                        } catch (Exception e) {
                            System.err.println("Error executing " + handlerName + " in script " + formatScriptId(script) + ": " + e.getMessage());
                        }
                        break; // Only execute once per script
                    }
                }
            }
        }

        // 3. Execute in movie scripts from ALL casts (each has its own name table)
        if (castManager != null) {
            for (var castLib : castManager.getCasts()) {
                // Each cast has its own script names table
                ScriptNamesChunk castNames = castLib.getScriptNames();
                int castNameId = castNames != null ? castNames.findName(handlerName) : -1;
                if (castNameId < 0) continue;

                for (ScriptChunk script : castLib.getAllScripts()) {
                    // Movie scripts have scriptType == MOVIE_SCRIPT
                    // if (script.scriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) continue;

                    for (ScriptChunk.Handler handler : script.handlers()) {
                        if (handler.nameId() == castNameId) {
                            try {
                                vm.execute(script, handler, new Datum[0]);
                            } catch (Exception e) {
                                System.err.println("Error executing " + handlerName + " in cast '" + castLib.getName() + "': " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Call a handler by name with arguments.
     */
    public Datum callHandler(String name, Datum... args) {
        return vm.call(name, args);
    }

    // Event listeners

    public void addEventListener(EventListener listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(EventListener listener) {
        eventListeners.remove(listener);
    }

    // Getters

    public DirectorFile getFile() {
        return file;
    }

    public CastManager getCastManager() {
        return castManager;
    }

    public LingoVM getVM() {
        return vm;
    }

    public PlayState getState() {
        return state;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public void setCurrentFrame(int frame) {
        this.currentFrame = Math.max(1, Math.min(frame, lastFrame));
    }

    public int getLastFrame() {
        return lastFrame;
    }

    public int getTempo() {
        return tempo;
    }

    public void setTempo(int tempo) {
        this.tempo = tempo > 0 ? tempo : 15;
    }

    public int getStageWidth() {
        return file != null ? file.getStageWidth() : 0;
    }

    public int getStageHeight() {
        return file != null ? file.getStageHeight() : 0;
    }

    public Score getScore() {
        return score;
    }

    public Map<Integer, SpriteState> getSprites() {
        return Collections.unmodifiableMap(sprites);
    }

    // Sprite state

    public SpriteState getSprite(int channel) {
        return sprites.computeIfAbsent(channel, SpriteState::new);
    }

    /**
     * Sprite runtime state.
     */
    public static class SpriteState {
        private final int channel;
        private int locH, locV;
        private int width, height;
        private int ink = 0;
        private int blend = 100;
        private boolean visible = true;
        private Datum.CastMemberRef member;

        public SpriteState(int channel) {
            this.channel = channel;
        }

        public int getChannel() { return channel; }
        public int getLocH() { return locH; }
        public void setLocH(int locH) { this.locH = locH; }
        public int getLocV() { return locV; }
        public void setLocV(int locV) { this.locV = locV; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
        public int getInk() { return ink; }
        public void setInk(int ink) { this.ink = ink; }
        public int getBlend() { return blend; }
        public void setBlend(int blend) { this.blend = blend; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
        public Datum.CastMemberRef getMember() { return member; }
        public void setMember(Datum.CastMemberRef member) { this.member = member; }
    }
}
