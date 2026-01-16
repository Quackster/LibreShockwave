package com.libreshockwave.runtime;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.vm.LingoVM;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

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
    }

    public DirPlayer(DirectorFile file) {
        loadMovie(file);
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
        this.lastFrame = 100;
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

        // Common stub handlers
        vm.registerBuiltin("startClient", (vmRef, args) -> {
            System.out.println("[startClient] called");
            return Datum.voidValue();
        });

        vm.registerBuiltin("stopClient", (vmRef, args) -> {
            System.out.println("[stopClient] called");
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

        dispatchEvent(MovieEvent.EXIT_FRAME);
        currentFrame = frame;
        dispatchEvent(MovieEvent.PREPARE_FRAME);
        dispatchEvent(MovieEvent.ENTER_FRAME);
    }

    /**
     * Go to a frame by label name.
     */
    public void goToLabel(String label) {
        System.out.println("[goToLabel] " + label);
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

        if (currentFrame == frameBefore) {
            if (currentFrame < lastFrame) {
                currentFrame++;
            } else {
                currentFrame = 1;
            }
        }

        dispatchEvent(MovieEvent.PREPARE_FRAME);
        dispatchEvent(MovieEvent.ENTER_FRAME);
    }

    /**
     * Update the stage (trigger redraw).
     */
    public void updateStage() {
        // Would trigger rendering
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
            System.out.println("Usage: DirPlayer <movie.dcr>");
            System.out.println("\nDirector Movie Player (Runtime)");
            System.out.println("Loads and executes Lingo scripts from Director files.");
            return;
        }

        try {
            DirPlayer player = new DirPlayer();
            player.loadMovie(Path.of(args[0]));

            System.out.println("=== Director Player (Runtime) ===");
            System.out.println("File: " + args[0]);
            System.out.println("Stage: " + player.getStageWidth() + "x" + player.getStageHeight());
            System.out.println("Tempo: " + player.getTempo() + " fps");
            System.out.println("Scripts: " + player.getFile().getScripts().size());
            System.out.println();

            System.out.println("--- Executing prepareMovie ---");
            player.dispatchEvent(MovieEvent.PREPARE_MOVIE);

            System.out.println("\n--- Executing startMovie ---");
            player.dispatchEvent(MovieEvent.START_MOVIE);

            System.out.println("\n--- Executing frame 1 ---");
            player.dispatchEvent(MovieEvent.ENTER_FRAME);
            player.dispatchEvent(MovieEvent.EXIT_FRAME);

            System.out.println("\n=== Done ===");

        } catch (IOException e) {
            System.err.println("Error loading file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
