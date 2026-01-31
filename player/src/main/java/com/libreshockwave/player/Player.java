package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.FrameLabelsChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.*;
import java.util.function.Consumer;

/**
 * Director movie player.
 * Handles frame playback, event dispatch, and score traversal.
 * Similar to dirplayer-rs player/mod.rs.
 */
public class Player {

    private final DirectorFile file;
    private final LingoVM vm;

    private PlayerState state = PlayerState.STOPPED;
    private int currentFrame = 1;  // Director frames are 1-indexed
    private Integer nextFrame = null;  // Set by go/jump commands
    private int tempo;  // Frames per second

    // Sprite state (channel -> active)
    private final Map<Integer, SpriteState> sprites = new HashMap<>();

    // Event listeners for external notification
    private Consumer<PlayerEventInfo> eventListener = null;

    // Frame labels cache
    private Map<String, Integer> frameLabels = null;

    public Player(DirectorFile file) {
        this.file = file;
        this.vm = new LingoVM(file);
        this.tempo = file != null ? file.getTempo() : 15;
        if (this.tempo <= 0) this.tempo = 15;  // Default

        buildFrameLabelsCache();
    }

    // Accessors

    public DirectorFile getFile() {
        return file;
    }

    public LingoVM getVM() {
        return vm;
    }

    public PlayerState getState() {
        return state;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public int getTempo() {
        return tempo;
    }

    public void setTempo(int tempo) {
        this.tempo = tempo > 0 ? tempo : 15;
    }

    public int getFrameCount() {
        if (file == null) return 0;
        ScoreChunk score = file.getScoreChunk();
        return score != null ? score.getFrameCount() : 0;
    }

    public void setEventListener(Consumer<PlayerEventInfo> listener) {
        this.eventListener = listener;
    }

    // Frame labels

    private void buildFrameLabelsCache() {
        frameLabels = new HashMap<>();
        if (file == null) return;
        FrameLabelsChunk labels = file.getFrameLabelsChunk();
        if (labels != null) {
            for (FrameLabelsChunk.FrameLabel label : labels.labels()) {
                frameLabels.put(label.label().toLowerCase(), label.frameNum());
            }
        }
    }

    public int getFrameForLabel(String label) {
        Integer frame = frameLabels.get(label.toLowerCase());
        return frame != null ? frame : -1;
    }

    public Set<String> getFrameLabels() {
        return Collections.unmodifiableSet(frameLabels.keySet());
    }

    // Playback control

    /**
     * Start playback from the beginning.
     */
    public void play() {
        if (state == PlayerState.STOPPED) {
            currentFrame = 1;
            prepareMovie();
        }
        state = PlayerState.PLAYING;
    }

    /**
     * Pause playback at the current frame.
     */
    public void pause() {
        if (state == PlayerState.PLAYING) {
            state = PlayerState.PAUSED;
        }
    }

    /**
     * Resume playback from a paused state.
     */
    public void resume() {
        if (state == PlayerState.PAUSED) {
            state = PlayerState.PLAYING;
        }
    }

    /**
     * Stop playback and reset to frame 1.
     */
    public void stop() {
        if (state != PlayerState.STOPPED) {
            dispatchEvent(PlayerEvent.STOP_MOVIE);
            endAllSprites();
            state = PlayerState.STOPPED;
            currentFrame = 1;
            nextFrame = null;
        }
    }

    /**
     * Go to a specific frame.
     */
    public void goToFrame(int frame) {
        int maxFrame = getFrameCount();
        if (frame >= 1 && frame <= maxFrame) {
            nextFrame = frame;
        }
    }

    /**
     * Go to a labeled frame.
     */
    public void goToLabel(String label) {
        int frame = getFrameForLabel(label);
        if (frame > 0) {
            goToFrame(frame);
        }
    }

    /**
     * Step forward one frame (manual advance).
     */
    public void stepFrame() {
        if (state == PlayerState.STOPPED) {
            currentFrame = 1;
            prepareMovie();
            state = PlayerState.PAUSED;
        }

        executeFrameUpdate();
        advanceFrame();
    }

    /**
     * Advance to the next frame (or nextFrame if set).
     */
    private void advanceFrame() {
        int newFrame;
        if (nextFrame != null) {
            newFrame = nextFrame;
            nextFrame = null;
        } else {
            newFrame = currentFrame + 1;
        }

        int maxFrame = getFrameCount();
        if (newFrame > maxFrame) {
            // Loop back to frame 1 or stop
            newFrame = 1;
        }

        if (newFrame != currentFrame) {
            dispatchEvent(PlayerEvent.EXIT_FRAME);
            endSpritesLeavingFrame(currentFrame, newFrame);
            currentFrame = newFrame;
            beginSpritesEnteringFrame(currentFrame);
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

        executeFrameUpdate();
        advanceFrame();
        return true;
    }

    /**
     * Execute the current frame's handlers.
     */
    private void executeFrameUpdate() {
        dispatchEvent(PlayerEvent.STEP_FRAME);
        dispatchEvent(PlayerEvent.PREPARE_FRAME);
        dispatchEvent(PlayerEvent.ENTER_FRAME);

        // Execute frame scripts (behaviors attached to current frame)
        executeFrameScripts();

        notifyEvent(PlayerEvent.ENTER_FRAME, currentFrame);
    }

    // Movie lifecycle

    private void prepareMovie() {
        dispatchEvent(PlayerEvent.PREPARE_MOVIE);
        beginAllSprites();
        dispatchEvent(PlayerEvent.START_MOVIE);
    }

    // Sprite management

    private void beginAllSprites() {
        if (file == null) return;
        ScoreChunk score = file.getScoreChunk();
        if (score == null) return;

        // Find all sprites in frame 1 and initialize them
        for (ScoreChunk.FrameChannelEntry entry : score.frameData().frameChannelData()) {
            if (entry.frameIndex() == 0) {  // Frame 0 = frame 1 in score data
                int channel = entry.channelIndex();
                if (!sprites.containsKey(channel)) {
                    SpriteState sprite = new SpriteState(channel, entry.data());
                    sprites.put(channel, sprite);
                    dispatchSpriteEvent(PlayerEvent.BEGIN_SPRITE, channel);
                }
            }
        }
    }

    private void endAllSprites() {
        for (int channel : new ArrayList<>(sprites.keySet())) {
            dispatchSpriteEvent(PlayerEvent.END_SPRITE, channel);
        }
        sprites.clear();
    }

    private void beginSpritesEnteringFrame(int frame) {
        if (file == null) return;
        ScoreChunk score = file.getScoreChunk();
        if (score == null) return;

        int frameIndex = frame - 1;  // Convert to 0-indexed
        for (ScoreChunk.FrameChannelEntry entry : score.frameData().frameChannelData()) {
            if (entry.frameIndex() == frameIndex) {
                int channel = entry.channelIndex();
                if (!sprites.containsKey(channel)) {
                    SpriteState sprite = new SpriteState(channel, entry.data());
                    sprites.put(channel, sprite);
                    dispatchSpriteEvent(PlayerEvent.BEGIN_SPRITE, channel);
                }
            }
        }
    }

    private void endSpritesLeavingFrame(int oldFrame, int newFrame) {
        // In a full implementation, we would track sprite spans
        // For now, sprites persist until explicitly ended
    }

    // Event dispatch

    private void dispatchEvent(PlayerEvent event) {
        if (file == null) return;

        // Find and execute all handlers for this event
        String handlerName = event.getHandlerName();

        // First, movie scripts
        for (ScriptChunk script : file.getScripts()) {
            if (script.scriptType() == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                ScriptChunk.Handler handler = script.findHandler(handlerName, file.getScriptNames());
                if (handler != null) {
                    try {
                        vm.executeHandler(script, handler, List.of(), null);
                    } catch (Exception e) {
                        System.err.println("Error in " + handlerName + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private void dispatchSpriteEvent(PlayerEvent event, int channel) {
        if (file == null) return;

        String handlerName = event.getHandlerName();

        // Find behaviors attached to this sprite/channel
        ScoreChunk score = file.getScoreChunk();
        if (score == null) return;

        for (ScoreChunk.FrameInterval interval : score.frameIntervals()) {
            if (interval.primary().channelIndex() == channel) {
                // Check if current frame is within the interval
                int start = interval.primary().startFrame();
                int end = interval.primary().endFrame();
                if (currentFrame >= start && currentFrame <= end && interval.secondary() != null) {
                    // Find the behavior script
                    int castLib = interval.secondary().castLib();
                    int castMember = interval.secondary().castMember();

                    CastMemberChunk member = file.getCastMemberByIndex(castLib, castMember);
                    if (member != null && member.isScript()) {
                        // Find the script by scriptId
                        for (ScriptChunk script : file.getScripts()) {
                            if (script.scriptType() == ScriptChunk.ScriptType.BEHAVIOR) {
                                ScriptChunk.Handler handler = script.findHandler(handlerName, file.getScriptNames());
                                if (handler != null) {
                                    try {
                                        Datum receiver = new Datum.SpriteRef(channel);
                                        vm.executeHandler(script, handler, List.of(), receiver);
                                    } catch (Exception e) {
                                        System.err.println("Error in sprite " + channel + " " + handlerName + ": " + e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void executeFrameScripts() {
        // Frame scripts are behaviors attached to the frame (channel 0)
        // This is a simplified version - full implementation would track frame behaviors
    }

    private void notifyEvent(PlayerEvent event, int data) {
        if (eventListener != null) {
            eventListener.accept(new PlayerEventInfo(event, currentFrame, data));
        }
    }

    // Sprite state

    public static class SpriteState {
        private final int channel;
        private final ScoreChunk.ChannelData initialData;

        private int locH;
        private int locV;
        private int width;
        private int height;
        private boolean visible = true;

        public SpriteState(int channel, ScoreChunk.ChannelData data) {
            this.channel = channel;
            this.initialData = data;
            this.locH = data.posX();
            this.locV = data.posY();
            this.width = data.width();
            this.height = data.height();
        }

        public int getChannel() { return channel; }
        public int getLocH() { return locH; }
        public int getLocV() { return locV; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public boolean isVisible() { return visible; }

        public void setLocH(int locH) { this.locH = locH; }
        public void setLocV(int locV) { this.locV = locV; }
        public void setVisible(boolean visible) { this.visible = visible; }

        public ScoreChunk.ChannelData getInitialData() { return initialData; }
    }

    /**
     * Information about a player event.
     */
    public record PlayerEventInfo(PlayerEvent event, int frame, int data) {}
}
