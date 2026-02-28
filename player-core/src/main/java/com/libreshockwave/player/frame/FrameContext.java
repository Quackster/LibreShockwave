package com.libreshockwave.player.frame;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.PlayerEvent;
import com.libreshockwave.player.behavior.BehaviorInstance;
import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.score.ScoreBehaviorRef;
import com.libreshockwave.player.score.ScoreNavigator;
import com.libreshockwave.player.score.SpriteSpan;
import com.libreshockwave.player.timeout.TimeoutManager;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.*;
import java.util.function.Consumer;

/**
 * Manages the execution context for a single frame.
 * Handles sprite initialization, event dispatch, and frame transitions.
 */
public class FrameContext {

    private final DirectorFile file;
    private final LingoVM vm;
    private final ScoreNavigator navigator;
    private final BehaviorManager behaviorManager;
    private final EventDispatcher eventDispatcher;

    private TimeoutManager timeoutManager;  // Set by Player for system event forwarding

    private int currentFrame = 1;
    private Integer pendingFrame = null;  // Set by go/jump commands
    private boolean inFrameScript = false;

    // Active sprite channels
    private final Set<Integer> activeChannels = new HashSet<>();
    private final Set<Integer> enteredChannels = new HashSet<>();

    // Debug logging
    private boolean debugEnabled = false;
    private Consumer<FrameEvent> eventListener;

    public FrameContext(DirectorFile file, LingoVM vm) {
        this.file = file;
        this.vm = vm;
        this.navigator = new ScoreNavigator(file);
        this.behaviorManager = new BehaviorManager(file);
        this.eventDispatcher = new EventDispatcher(file, vm, behaviorManager);
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        behaviorManager.setDebugEnabled(enabled);
        eventDispatcher.setDebugEnabled(enabled);
    }

    public void setEventListener(Consumer<FrameEvent> listener) {
        this.eventListener = listener;
    }

    public void setTimeoutManager(TimeoutManager timeoutManager) {
        this.timeoutManager = timeoutManager;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public int getFrameCount() {
        return navigator.getFrameCount();
    }

    public ScoreNavigator getNavigator() {
        return navigator;
    }

    public BehaviorManager getBehaviorManager() {
        return behaviorManager;
    }

    public EventDispatcher getEventDispatcher() {
        return eventDispatcher;
    }

    // Frame navigation

    /**
     * Go to a specific frame (queued for next advance).
     */
    public void goToFrame(int frame) {
        int max = getFrameCount();
        if (frame >= 1 && frame <= max) {
            pendingFrame = frame;
            logEvent("goToFrame(" + frame + ")");
        }
    }

    /**
     * Go to a labeled frame.
     */
    public void goToLabel(String label) {
        int frame = navigator.getFrameForLabel(label);
        if (frame > 0) {
            goToFrame(frame);
        }
    }

    // Frame execution

    /**
     * Initialize the first frame (called on movie start).
     */
    public void initializeFirstFrame() {
        currentFrame = 1;
        pendingFrame = null;
        activeChannels.clear();
        enteredChannels.clear();
        behaviorManager.clear();

        logEvent("initializeFirstFrame");

        // Begin sprites for frame 1
        beginSpritesForFrame(currentFrame);

        // Create frame script instance if any
        initializeFrameScript(currentFrame);
    }

    /**
     * Execute one frame update cycle (for frame loop, not first frame).
     * First frame events are handled in Player.prepareMovie().
     * Returns true if frame was executed successfully.
     */
    public boolean executeFrame() {
        logEvent("executeFrame(" + currentFrame + ")");

        // 1. stepFrame event (to actorList and behaviors)
        dispatchEvent(PlayerEvent.STEP_FRAME);

        // 2. prepareFrame -> timeout targets first, then behaviors + frame/movie scripts
        if (timeoutManager != null) {
            timeoutManager.dispatchSystemEvent(vm, "prepareFrame");
        }
        dispatchEvent(PlayerEvent.PREPARE_FRAME);

        // 3. enterFrame -> all behaviors + frame/movie scripts
        inFrameScript = true;
        dispatchEvent(PlayerEvent.ENTER_FRAME);
        inFrameScript = false;

        return true;
    }

    /**
     * Dispatch beginSprite events to newly entered sprites.
     * Called from Player.prepareMovie() for first frame setup.
     */
    public void dispatchBeginSpriteEvents() {
        dispatchBeginSprite();
    }

    /**
     * Advance to the next frame (or pending frame if set).
     * Returns the new frame number.
     */
    public int advanceFrame() {
        int oldFrame = currentFrame;
        int newFrame;

        if (pendingFrame != null) {
            newFrame = pendingFrame;
            pendingFrame = null;
        } else {
            newFrame = currentFrame + 1;
        }

        int max = getFrameCount();
        if (newFrame > max) {
            newFrame = 1;  // Loop
        }

        if (newFrame != oldFrame) {
            logEvent("advanceFrame: " + oldFrame + " -> " + newFrame);

            // Exit old frame
            exitFrame(oldFrame, newFrame);

            // Enter new frame
            currentFrame = newFrame;
            enterFrame(newFrame);
        }

        return currentFrame;
    }

    /**
     * Exit the current frame (cleanup).
     */
    private void exitFrame(int oldFrame, int newFrame) {
        // exitFrame -> timeout targets first, then behaviors
        if (timeoutManager != null) {
            timeoutManager.dispatchSystemEvent(vm, "exitFrame");
        }
        dispatchEvent(PlayerEvent.EXIT_FRAME);

        // End sprites that are leaving
        endSpritesLeavingFrame(oldFrame, newFrame);

        // Clear frame script
        behaviorManager.clearFrameScript();
    }

    /**
     * Enter a new frame (initialization).
     */
    private void enterFrame(int frame) {
        // Begin new sprites
        beginSpritesForFrame(frame);

        // Initialize frame script
        initializeFrameScript(frame);

        // Dispatch beginSprite events to newly entered sprites
        dispatchBeginSprite();

        // Clear entered set for next beginSprite dispatch
        enteredChannels.clear();
    }

    // Sprite management

    /**
     * Begin sprites that are active in the given frame.
     */
    private void beginSpritesForFrame(int frame) {
        List<SpriteSpan> spans = navigator.getActiveSprites(frame);

        for (SpriteSpan span : spans) {
            int channel = span.getChannel();

            if (!activeChannels.contains(channel)) {
                // New sprite entering
                activeChannels.add(channel);
                enteredChannels.add(channel);

                // Create behavior instances for this sprite
                for (ScoreBehaviorRef behaviorRef : span.getBehaviors()) {
                    behaviorManager.createInstance(behaviorRef, channel);
                }

                logEvent("beginSprite: channel " + channel);
            }
        }
    }

    /**
     * End sprites that are leaving when transitioning frames.
     */
    private void endSpritesLeavingFrame(int oldFrame, int newFrame) {
        Set<Integer> newActiveChannels = navigator.getActiveChannels(newFrame);

        List<Integer> leaving = new ArrayList<>();
        for (int channel : activeChannels) {
            if (!newActiveChannels.contains(channel)) {
                leaving.add(channel);
            }
        }

        for (int channel : leaving) {
            // Dispatch endSprite event
            eventDispatcher.dispatchSpriteEvent(channel, PlayerEvent.END_SPRITE, List.of());

            // Mark instances as ended
            for (BehaviorInstance instance : behaviorManager.getInstancesForChannel(channel)) {
                instance.setEndSpriteCalled(true);
            }

            // Remove from active set
            activeChannels.remove(channel);
            behaviorManager.removeInstancesForChannel(channel);

            logEvent("endSprite: channel " + channel);
        }
    }

    /**
     * Initialize the frame script for the given frame.
     */
    private void initializeFrameScript(int frame) {
        ScoreBehaviorRef frameScript = navigator.getFrameScript(frame);
        if (frameScript != null) {
            behaviorManager.getOrCreateFrameScript(frameScript, frame);
            logEvent("initializeFrameScript: frame " + frame + " -> " + frameScript);
        } else {
            logEvent("initializeFrameScript: frame " + frame + " has no frame script");
        }


    }

    // Event dispatch

    /**
     * Dispatch a player event.
     */
    private void dispatchEvent(PlayerEvent event) {
        eventDispatcher.dispatchGlobalEvent(event, List.of());
        notifyEvent(event);
    }

    /**
     * Dispatch beginSprite to newly entered sprites.
     */
    private void dispatchBeginSprite() {
        for (int channel : enteredChannels) {
            List<BehaviorInstance> instances = behaviorManager.getInstancesForChannel(channel);
            for (BehaviorInstance instance : instances) {
                if (!instance.isBeginSpriteCalled()) {
                    eventDispatcher.dispatchSpriteEvent(channel, PlayerEvent.BEGIN_SPRITE, List.of());
                    instance.setBeginSpriteCalled(true);
                }
            }
        }

        // Also dispatch beginSprite for frame behavior if new
        BehaviorInstance frameInstance = behaviorManager.getFrameScriptInstance();
        if (frameInstance != null && !frameInstance.isBeginSpriteCalled()) {
            eventDispatcher.dispatchFrameAndMovieEvent(PlayerEvent.BEGIN_SPRITE, List.of());
            frameInstance.setBeginSpriteCalled(true);
        }
    }

    // Debug and notification

    private void logEvent(String message) {
        if (debugEnabled) {
            System.out.println("[FrameContext] " + message);
        }
    }

    private void notifyEvent(PlayerEvent event) {
        if (eventListener != null) {
            eventListener.accept(new FrameEvent(event, currentFrame));
        }
    }

    /**
     * Reset the context (called on stop).
     */
    public void reset() {
        currentFrame = 1;
        pendingFrame = null;
        activeChannels.clear();
        enteredChannels.clear();
        behaviorManager.clear();
        inFrameScript = false;
    }

}
