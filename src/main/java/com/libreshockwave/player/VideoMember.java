package com.libreshockwave.player;

import com.libreshockwave.player.bitmap.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a digital video cast member.
 * Stores video frames as bitmaps for playback.
 */
public class VideoMember {

    private final int castLib;
    private final int memberNum;
    private final String name;

    // Video properties
    private int width;
    private int height;
    private float frameRate;
    private int duration; // in milliseconds

    // Frames storage
    private final List<VideoFrame> frames;

    // Playback state
    private int currentFrameIndex;
    private boolean playing;
    private boolean looping;
    private long playStartTime;
    private long pausePosition;

    // Audio track (optional)
    private SoundMember audioTrack;

    public VideoMember(int castLib, int memberNum, String name) {
        this.castLib = castLib;
        this.memberNum = memberNum;
        this.name = name;
        this.frames = new ArrayList<>();
        this.frameRate = 15.0f;
        this.currentFrameIndex = 0;
        this.playing = false;
        this.looping = false;
    }

    /**
     * A single video frame with timing info.
     */
    public static class VideoFrame {
        private final Bitmap bitmap;
        private final long timestamp; // milliseconds from start
        private final int keyFrame; // 0 = not keyframe, 1 = keyframe

        public VideoFrame(Bitmap bitmap, long timestamp, boolean isKeyFrame) {
            this.bitmap = bitmap;
            this.timestamp = timestamp;
            this.keyFrame = isKeyFrame ? 1 : 0;
        }

        public Bitmap getBitmap() { return bitmap; }
        public long getTimestamp() { return timestamp; }
        public boolean isKeyFrame() { return keyFrame == 1; }
    }

    /**
     * Add a frame to the video.
     */
    public void addFrame(Bitmap bitmap, long timestamp, boolean isKeyFrame) {
        frames.add(new VideoFrame(bitmap, timestamp, isKeyFrame));
        if (frames.size() == 1) {
            this.width = bitmap.getWidth();
            this.height = bitmap.getHeight();
        }
        // Update duration
        this.duration = (int) Math.max(duration, timestamp);
    }

    /**
     * Add a frame at the calculated timestamp based on frame rate.
     */
    public void addFrame(Bitmap bitmap, boolean isKeyFrame) {
        long timestamp = (long) (frames.size() * 1000.0 / frameRate);
        addFrame(bitmap, timestamp, isKeyFrame);
    }

    /**
     * Get the current frame for display.
     */
    public Bitmap getCurrentFrame() {
        if (frames.isEmpty()) return null;
        if (playing) {
            updatePlaybackPosition();
        }
        if (currentFrameIndex >= 0 && currentFrameIndex < frames.size()) {
            return frames.get(currentFrameIndex).getBitmap();
        }
        return null;
    }

    /**
     * Get frame at specific index.
     */
    public Bitmap getFrame(int index) {
        if (index >= 0 && index < frames.size()) {
            return frames.get(index).getBitmap();
        }
        return null;
    }

    /**
     * Update playback position based on elapsed time.
     */
    private void updatePlaybackPosition() {
        if (!playing || frames.isEmpty()) return;

        long elapsed = System.currentTimeMillis() - playStartTime + pausePosition;

        // Handle looping
        if (looping && duration > 0) {
            elapsed = elapsed % duration;
        } else if (elapsed >= duration) {
            elapsed = duration;
            playing = false;
        }

        // Find frame for this timestamp
        int newFrame = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).getTimestamp() <= elapsed) {
                newFrame = i;
            } else {
                break;
            }
        }
        currentFrameIndex = newFrame;
    }

    /**
     * Start playback.
     */
    public void play() {
        if (frames.isEmpty()) return;
        playing = true;
        playStartTime = System.currentTimeMillis();

        if (audioTrack != null) {
            audioTrack.play();
        }
    }

    /**
     * Stop playback and reset to beginning.
     */
    public void stop() {
        playing = false;
        currentFrameIndex = 0;
        pausePosition = 0;

        if (audioTrack != null) {
            audioTrack.stop();
        }
    }

    /**
     * Pause playback.
     */
    public void pause() {
        if (playing) {
            pausePosition += System.currentTimeMillis() - playStartTime;
            playing = false;

            if (audioTrack != null) {
                audioTrack.pause();
            }
        }
    }

    /**
     * Resume playback.
     */
    public void resume() {
        if (!playing && !frames.isEmpty()) {
            playStartTime = System.currentTimeMillis();
            playing = true;

            if (audioTrack != null) {
                audioTrack.resume();
            }
        }
    }

    /**
     * Seek to a specific time in milliseconds.
     */
    public void seek(long millis) {
        pausePosition = Math.max(0, Math.min(duration, millis));
        if (playing) {
            playStartTime = System.currentTimeMillis();
        }
        updatePlaybackPosition();

        if (audioTrack != null) {
            audioTrack.setPosition(millis);
        }
    }

    /**
     * Seek to a specific frame.
     */
    public void seekToFrame(int frameIndex) {
        if (frameIndex >= 0 && frameIndex < frames.size()) {
            currentFrameIndex = frameIndex;
            pausePosition = frames.get(frameIndex).getTimestamp();
            if (playing) {
                playStartTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * Step forward one frame.
     */
    public void stepForward() {
        if (currentFrameIndex < frames.size() - 1) {
            currentFrameIndex++;
            pausePosition = frames.get(currentFrameIndex).getTimestamp();
        }
    }

    /**
     * Step backward one frame.
     */
    public void stepBackward() {
        if (currentFrameIndex > 0) {
            currentFrameIndex--;
            pausePosition = frames.get(currentFrameIndex).getTimestamp();
        }
    }

    /**
     * Get current playback position in milliseconds.
     */
    public long getPosition() {
        if (playing) {
            return System.currentTimeMillis() - playStartTime + pausePosition;
        }
        return pausePosition;
    }

    // Properties

    public int getCastLib() { return castLib; }
    public int getMemberNum() { return memberNum; }
    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public float getFrameRate() { return frameRate; }
    public void setFrameRate(float frameRate) { this.frameRate = frameRate; }
    public int getDuration() { return duration; }
    public int getFrameCount() { return frames.size(); }
    public int getCurrentFrameIndex() { return currentFrameIndex; }
    public boolean isPlaying() { return playing; }
    public boolean isLooping() { return looping; }
    public void setLooping(boolean looping) { this.looping = looping; }

    public void setAudioTrack(SoundMember audio) {
        this.audioTrack = audio;
    }

    public SoundMember getAudioTrack() {
        return audioTrack;
    }

    /**
     * Release resources.
     */
    public void dispose() {
        frames.clear();
        if (audioTrack != null) {
            audioTrack.dispose();
        }
    }
}
