package com.libreshockwave.runtime;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.player.Score;
import com.libreshockwave.player.Sprite;
import com.libreshockwave.vm.LingoVM;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Director movie player for the runtime.
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

    // Sprite state
    private final Map<Integer, SpriteState> sprites = new HashMap<>();

    // Event listeners
    private final List<EventListener> eventListeners = new ArrayList<>();

    // Execution scope stack for handler calls (runtime-specific tracking)
    private final Deque<ExecutionScope> scopeStack = new ArrayDeque<>();

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
        this.scopeStack.clear();

        // Determine last frame from score
        if (score != null && score.getFrameCount() > 0) {
            this.lastFrame = score.getFrameCount();
        } else {
            this.lastFrame = 1;
        }
        System.out.println("[DirPlayer] Score: " + (score != null ? score.getFrameCount() + " frames, " + score.getChannelCount() + " channels" : "none"));
    }

    /**
     * Load a movie from an HTTP URL.
     * Sets base path for resolving relative cast URLs.
     */
    public CompletableFuture<Void> loadMovieFromUrl(String url) {
        System.out.println("[DirPlayer] Loading movie from URL: " + url);

        // Set base path from movie URL
        int lastSlash = url.lastIndexOf('/');
        this.baseUrl = lastSlash > 0 ? url.substring(0, lastSlash + 1) : url;
        netManager.setBasePath(baseUrl);
        System.out.println("[DirPlayer] Base URL: " + baseUrl);

        // Download the movie file
        int taskId = netManager.preloadNetThing(url);

        return netManager.awaitTask(taskId).thenAccept(result -> {
            if (result.isSuccess()) {
                try {
                    byte[] data = result.getData();
                    System.out.println("[DirPlayer] Downloaded movie (" + data.length + " bytes)");
                    DirectorFile dirFile = DirectorFile.load(data);
                    loadMovie(dirFile);
                    System.out.println("[DirPlayer] Movie loaded successfully");
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
        
        // Cast preloading
        vm.registerBuiltin("preload", (vmRef, args) -> {
            return Datum.voidValue();
        });
    }

    // Scope management (runtime-specific tracking)

    /**
     * Push a new execution scope for runtime tracking.
     */
    public ExecutionScope pushScope(List<Datum> args) {
        ExecutionScope scope = new ExecutionScope(args);
        scopeStack.push(scope);
        return scope;
    }

    /**
     * Pop the current execution scope.
     */
    public ExecutionScope popScope() {
        if (!scopeStack.isEmpty()) {
            return scopeStack.pop();
        }
        return null;
    }

    /**
     * Get the current execution scope.
     */
    public ExecutionScope currentScope() {
        return scopeStack.peek();
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
     */
    public void goToFrame(int frame) {
        if (frame < 1) frame = 1;
        if (frame > lastFrame) frame = lastFrame;

        int previousFrame = currentFrame;
        dispatchEvent(MovieEvent.EXIT_FRAME);

        currentFrame = frame;

        // Load sprites from score for this frame
        loadSpritesFromScore(currentFrame);

        dispatchEvent(MovieEvent.PREPARE_FRAME);

        // Execute frame script if present
        executeFrameScript(currentFrame);

        dispatchEvent(MovieEvent.ENTER_FRAME);
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
            // Execute the first handler (typically the frame script)
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
        // For now, search through all scripts
        // TODO: Use KeyTable to map cast members to scripts properly
        for (ScriptChunk script : file.getScripts()) {
            // Simple matching - in reality this should use the cast member -> script mapping
            if (script.id() == castMember) {
                return script;
            }
        }
        return null;
    }

    /**
     * Go to a frame by label name.
     */
    public void goToLabel(String label) {
        if (score == null) {
            System.out.println("[goToLabel] No score available");
            return;
        }

        int frameNum = score.getFrameByLabel(label);
        if (frameNum > 0) {
            System.out.println("[goToLabel] " + label + " -> frame " + frameNum);
            goToFrame(frameNum);
        } else {
            System.out.println("[goToLabel] Label not found: " + label);
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

    // Event dispatching

    /**
     * Dispatch a movie event to all handlers.
     */
    public void dispatchEvent(MovieEvent event) {
        String handlerName = event.handlerName();

        for (EventListener listener : eventListeners) {
            listener.onEvent(event, currentFrame);
        }

        try {
            executeHandlerIfExists(handlerName);
        } catch (Exception e) {
            System.err.println("Error in " + handlerName + ": " + e.getMessage());
        }
    }

    /**
     * Execute a handler by name if it exists.
     */
    private void executeHandlerIfExists(String handlerName) {
        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) return;

        int nameId = names.findName(handlerName);
        if (nameId < 0) return;

        for (ScriptChunk script : file.getScripts()) {
            for (ScriptChunk.Handler handler : script.handlers()) {
                if (handler.nameId() == nameId) {
                    try {
                        // Execute using SDK's LingoVM
                        vm.execute(script, handler, new Datum[0]);
                    } catch (Exception e) {
                        System.err.println("Error executing " + handlerName + ": " + e.getMessage());
                    }
                    return;
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

    public int getLastFrame() {
        return lastFrame;
    }

    public int getTempo() {
        return tempo;
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

    // Main entry point

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: DirPlayer <movie.dcr|http://url/movie.dcr>");
            System.out.println("\nDirector Movie Player (Runtime)");
            System.out.println("Loads and executes Lingo scripts from Director files.");
            System.out.println("Supports both local files and HTTP URLs.");
            return;
        }

        try {
            DirPlayer player = new DirPlayer();
            String path = args[0];

            // Check if it's a URL or file path
            if (path.startsWith("http://") || path.startsWith("https://")) {
                System.out.println("=== Director Player (Runtime) - HTTP Mode ===");
                player.loadMovieFromUrl(path).get(60, TimeUnit.SECONDS);
            } else {
                System.out.println("=== Director Player (Runtime) - File Mode ===");
                player.loadMovie(Path.of(path));
            }

            System.out.println("File: " + path);
            System.out.println("Stage: " + player.getStageWidth() + "x" + player.getStageHeight());
            System.out.println("Tempo: " + player.getTempo() + " fps");
            System.out.println("Frames: " + player.getLastFrame());
            System.out.println("Scripts: " + player.getFile().getScripts().size());
            System.out.println("Casts: " + player.getCastManager().getCastCount());

            // Show score info
            Score score = player.getScore();
            if (score != null) {
                System.out.println("\n--- Score ---");
                System.out.println("Channels: " + score.getChannelCount());
                System.out.println("Behavior intervals: " + score.getFrameIntervals().size());
                if (!score.getFrameLabels().isEmpty()) {
                    System.out.println("Frame labels:");
                    for (var entry : score.getFrameLabels().entrySet()) {
                        System.out.println("  " + entry.getKey() + " -> frame " + entry.getValue());
                    }
                }
            }

            // Show external casts
            boolean hasExternal = false;
            for (var cast : player.getCastManager().getCasts()) {
                if (cast.isExternal()) {
                    if (!hasExternal) {
                        System.out.println("\n--- External Casts ---");
                        hasExternal = true;
                    }
                    System.out.println("  " + cast.getName() + " -> " + cast.getFileName());
                }
            }
            System.out.println();

            System.out.println("--- Executing prepareMovie ---");
            player.dispatchEvent(MovieEvent.PREPARE_MOVIE);

            System.out.println("\n--- Executing startMovie ---");
            player.dispatchEvent(MovieEvent.START_MOVIE);

            System.out.println("\n--- Going to frame 1 ---");
            player.goToFrame(1);

            System.out.println("\n=== Done ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
