package com.libreshockwave.player;

/**
 * Represents a sound channel for audio playback.
 * Director supports 8 sound channels (1-8).
 */
public class SoundChannel {

    public static final int MAX_CHANNELS = 8;

    private final int channelNum;
    private SoundMember currentSound;
    private boolean playing;
    private float volume;
    private float pan; // -1.0 (left) to 1.0 (right)
    private boolean paused;

    public SoundChannel(int channelNum) {
        this.channelNum = channelNum;
        this.volume = 1.0f;
        this.pan = 0.0f;
        this.playing = false;
        this.paused = false;
    }

    /**
     * Play a sound in this channel.
     */
    public void play(SoundMember sound) {
        if (currentSound != null && currentSound.isPlaying()) {
            currentSound.stop();
        }
        currentSound = sound;
        if (currentSound != null) {
            currentSound.setVolume(volume);
            currentSound.play();
            playing = true;
            paused = false;
        }
    }

    /**
     * Stop the current sound.
     */
    public void stop() {
        if (currentSound != null) {
            currentSound.stop();
        }
        playing = false;
        paused = false;
    }

    /**
     * Pause playback.
     */
    public void pause() {
        if (currentSound != null && playing && !paused) {
            currentSound.pause();
            paused = true;
        }
    }

    /**
     * Resume playback.
     */
    public void resume() {
        if (currentSound != null && paused) {
            currentSound.resume();
            paused = false;
        }
    }

    /**
     * Check if sound is currently playing.
     */
    public boolean isPlaying() {
        if (currentSound != null) {
            return currentSound.isPlaying();
        }
        return false;
    }

    /**
     * Check if channel is busy (has a sound, playing or paused).
     */
    public boolean isBusy() {
        return currentSound != null && (playing || paused);
    }

    /**
     * Get volume (0-255 in Lingo, but internally 0.0-1.0).
     */
    public int getVolume() {
        return Math.round(volume * 255);
    }

    /**
     * Set volume (0-255 in Lingo).
     */
    public void setVolume(int vol) {
        this.volume = Math.max(0, Math.min(255, vol)) / 255.0f;
        if (currentSound != null) {
            currentSound.setVolume(volume);
        }
    }

    /**
     * Get pan position (-255 to 255 in Lingo).
     */
    public int getPan() {
        return Math.round(pan * 255);
    }

    /**
     * Set pan position (-255 to 255 in Lingo).
     */
    public void setPan(int panValue) {
        this.pan = Math.max(-255, Math.min(255, panValue)) / 255.0f;
        // Pan control would need stereo support in SoundMember
    }

    /**
     * Get current playback position in milliseconds.
     */
    public long getPosition() {
        if (currentSound != null) {
            return currentSound.getPosition();
        }
        return 0;
    }

    /**
     * Get total duration in milliseconds.
     */
    public long getDuration() {
        if (currentSound != null) {
            return currentSound.getDuration();
        }
        return 0;
    }

    /**
     * Get the current sound member.
     */
    public SoundMember getCurrentSound() {
        return currentSound;
    }

    /**
     * Get channel number (1-8).
     */
    public int getChannelNum() {
        return channelNum;
    }

    /**
     * Release resources.
     */
    public void dispose() {
        stop();
        if (currentSound != null) {
            currentSound.dispose();
            currentSound = null;
        }
    }
}
