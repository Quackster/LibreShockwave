package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.frame.FrameContext;
import com.libreshockwave.player.score.ScoreNavigator;
import com.libreshockwave.vm.LingoVM;

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
        this.tempo = file != null ? file.getTempo() : 15;
        if (this.tempo <= 0) this.tempo = 15;

        // Wire up event notifications
        frameContext.setEventListener(event -> {
            if (eventListener != null) {
                eventListener.accept(new PlayerEventInfo(event.event(), event.frame(), 0));
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

    public void setEventListener(Consumer<PlayerEventInfo> listener) {
        this.eventListener = listener;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        frameContext.setDebugEnabled(enabled);
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
            dispatchMovieEvent(PlayerEvent.STOP_MOVIE);
            frameContext.reset();
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

        frameContext.executeFrame();
        frameContext.advanceFrame();
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

        frameContext.executeFrame();
        frameContext.advanceFrame();
        return true;
    }

    // Movie lifecycle

    private void prepareMovie() {
        log("prepareMovie()");
        dispatchMovieEvent(PlayerEvent.PREPARE_MOVIE);
        frameContext.initializeFirstFrame();
        dispatchMovieEvent(PlayerEvent.START_MOVIE);
    }

    private void dispatchMovieEvent(PlayerEvent event) {
        frameContext.getEventDispatcher().dispatchToMovieScripts(event.getHandlerName(), List.of());
    }

    // Debug logging

    private void log(String message) {
        if (debugEnabled) {
            System.out.println("[Player] " + message);
        }
    }

}
