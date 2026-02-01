package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.frame.FrameContext;
import com.libreshockwave.player.net.NetManager;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.StageRenderer;
import com.libreshockwave.player.score.ScoreNavigator;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;
import com.libreshockwave.vm.builtin.NetBuiltins;
import com.libreshockwave.vm.builtin.XtraBuiltins;
import com.libreshockwave.vm.xtra.XtraManager;

import java.util.*;
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
    private final NetManager netManager;
    private final XtraManager xtraManager;
    private final MovieProperties movieProperties;
    private final CastLibManager castLibManager;

    private PlayerState state = PlayerState.STOPPED;
    private int tempo;  // Frames per second

    // Event listeners for external notification
    private Consumer<PlayerEventInfo> eventListener;

    // Debug mode
    private boolean debugEnabled = false;

    public Player(DirectorFile file) {
        this.file = file;
        this.vm = new LingoVM(file);
        this.frameContext = new FrameContext(file, vm);
        this.stageRenderer = new StageRenderer(file);
        this.netManager = new NetManager();
        this.xtraManager = new XtraManager();
        this.movieProperties = new MovieProperties(this, file);
        this.castLibManager = new CastLibManager(file);
        this.tempo = file != null ? file.getTempo() : 15;
        if (this.tempo <= 0) this.tempo = 15;

        // Set base path for network requests from the file location
        if (file != null && file.getBasePath() != null && !file.getBasePath().isEmpty()) {
            netManager.setBasePath(file.getBasePath());
        }

        // Wire up network completion callback to handle external cast loading
        netManager.setCompletionCallback((fileName, data) -> {
            // Check if this URL matches an external cast library
            if (castLibManager.setExternalCastDataByUrl(fileName, data)) {
                System.out.println("[Player] Loaded external cast from: " + fileName); // TODO Normalise
            }
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

    public MovieProperties getMovieProperties() {
        return movieProperties;
    }

    public CastLibManager getCastLibManager() {
        return castLibManager;
    }

    public PlayerState getState() {
        return state;
    }

    public int getCurrentFrame() {
        return frameContext.getCurrentFrame();
    }

    public int getTempo() {
        return tempo;
    }

    public void setTempo(int tempo) {
        this.tempo = tempo > 0 ? tempo : 15;
    }

    public int getFrameCount() {
        return frameContext.getFrameCount();
    }

    /**
     * Get a snapshot of the current frame for rendering.
     * This captures all sprite states at the moment it's called.
     */
    public FrameSnapshot getFrameSnapshot() {
        return FrameSnapshot.capture(stageRenderer, getCurrentFrame(), state.name());
    }

    public void setEventListener(Consumer<PlayerEventInfo> listener) {
        this.eventListener = listener;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        frameContext.setDebugEnabled(enabled);
        if (enabled) {
            dumpScriptInfo();
        }
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
     */
    public void play() {
        if (state == PlayerState.STOPPED) {
            prepareMovie();
        }
        state = PlayerState.PLAYING;
        log("play()");
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
                // stopMovie -> dispatched to movie scripts
                frameContext.getEventDispatcher().dispatchToMovieScripts("stopMovie", List.of());
            } finally {
                clearProviders();
            }
            frameContext.reset();
            stageRenderer.reset();
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
     */
    public void stepFrame() {
        if (state == PlayerState.STOPPED) {
            prepareMovie();
            state = PlayerState.PAUSED;
        }

        setupProviders();
        try {
            frameContext.executeFrame();
            frameContext.advanceFrame();
        } finally {
            clearProviders();
        }
    }

    // Frame execution (called by external timer/loop)

    /**
     * Execute one frame tick. Call this at tempo rate.
     * @return true if still playing, false if stopped/paused
     */
    public boolean tick() {
        if (state != PlayerState.PLAYING) {
            return state == PlayerState.PAUSED;
        }

        // Set up thread-local providers before script execution
        setupProviders();
        try {
            frameContext.executeFrame();
            frameContext.advanceFrame();
        } finally {
            clearProviders();
        }
        return true;
    }

    /**
     * Set up thread-local providers for builtin functions.
     */
    private void setupProviders() {
        NetBuiltins.setProvider(netManager);
        XtraBuiltins.setManager(xtraManager);
        MoviePropertyProvider.setProvider(movieProperties);
        CastLibProvider.setProvider(castLibManager);
    }

    /**
     * Clear thread-local providers after script execution.
     */
    private void clearProviders() {
        NetBuiltins.clearProvider();
        XtraBuiltins.clearManager();
        MoviePropertyProvider.clearProvider();
        CastLibProvider.clearProvider();
    }

    // Movie lifecycle - follows dirplayer-rs flow exactly

    private void prepareMovie() {
        log("prepareMovie()");

        setupProviders();
        try {
            // 0. Preload casts with preloadMode=1 (before frame 1)
            castLibManager.preloadCasts(1);

            // 1. prepareMovie -> dispatched to movie scripts (behaviors not initialized yet)
            frameContext.getEventDispatcher().dispatchToMovieScripts("prepareMovie", List.of());

            // 2. Initialize sprites for frame 1
            frameContext.initializeFirstFrame();

            // 3. beginSprite events
            frameContext.dispatchBeginSpriteEvents();

            // 4. prepareFrame -> dispatched to all behaviors + frame/movie scripts
            frameContext.getEventDispatcher().dispatchGlobalEvent(PlayerEvent.PREPARE_FRAME, List.of());

            // 5. startMovie -> dispatched to movie scripts
            frameContext.getEventDispatcher().dispatchToMovieScripts("startMovie", List.of());

            // 6. enterFrame -> dispatched to all behaviors + frame/movie scripts
            frameContext.getEventDispatcher().dispatchGlobalEvent(PlayerEvent.ENTER_FRAME, List.of());

            // 7. exitFrame -> dispatched to all behaviors + frame/movie scripts
            frameContext.getEventDispatcher().dispatchGlobalEvent(PlayerEvent.EXIT_FRAME, List.of());

            // 8. Preload casts with preloadMode=2 (after frame 1)
            castLibManager.preloadCasts(2);

            // Frame loop will handle subsequent frames
        } finally {
            clearProviders();
        }
    }

    /**
     * Shutdown the player and release resources.
     * Call this when the player is no longer needed.
     */
    public void shutdown() {
        stop();
        netManager.shutdown();
    }

    // Debug logging

    private void log(String message) {
        if (debugEnabled) {
            System.out.println("[Player] " + message);
        }
    }

}
