package com.libreshockwave.player.audio;

/**
 * Platform-agnostic audio playback backend.
 * Implemented by player-swing (javax.sound.sampled) and player-wasm (Web Audio API).
 *
 * Channels are 1-based (1-8), matching Director's sound channel numbering.
 */
public interface AudioBackend {

    /**
     * Play audio data on the specified channel.
     * @param channelNum 1-based channel number
     * @param audioData WAV or MP3 bytes ready for playback
     * @param format "wav" or "mp3"
     * @param loopCount number of times to play (0 = loop forever, 1 = play once)
     */
    void play(int channelNum, byte[] audioData, String format, int loopCount);

    /**
     * Stop playback on the specified channel.
     */
    void stop(int channelNum);

    /**
     * Stop all channels.
     */
    void stopAll();

    /**
     * Set volume for a channel (0-255, where 255 = full volume).
     */
    void setVolume(int channelNum, int volume);

    /**
     * Check if a channel is currently playing.
     */
    boolean isPlaying(int channelNum);

    /**
     * Get the elapsed time in milliseconds for a channel.
     */
    int getElapsedTime(int channelNum);
}
