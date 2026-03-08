package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;

/**
 * Thread-local provider for sound channel operations.
 * Bridges the VM layer to the player's SoundManager.
 */
public interface SoundProvider {

    void play(int channelNum, Datum args);
    void stop(int channelNum);
    void stopAll();
    void setVolume(int channelNum, int volume);
    int getVolume(int channelNum);
    boolean isPlaying(int channelNum);
    int getElapsedTime(int channelNum);

    // Thread-local provider pattern
    ThreadLocal<SoundProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(SoundProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static SoundProvider getProvider() {
        return CURRENT.get();
    }
}
