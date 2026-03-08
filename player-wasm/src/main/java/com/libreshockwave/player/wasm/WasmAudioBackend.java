package com.libreshockwave.player.wasm;

import com.libreshockwave.player.audio.AudioBackend;

import java.util.ArrayList;
import java.util.List;

/**
 * Audio backend for WASM/TeaVM that queues sound commands for JavaScript to execute.
 * The WASM worker cannot directly access Web Audio API (main thread only),
 * so commands are queued and polled by JavaScript via exported functions.
 */
public class WasmAudioBackend implements AudioBackend {

    /** Queued sound command. */
    record SoundCommand(String action, int channelNum, byte[] audioData, String format,
                        int loopCount, int volume) {}

    private final List<SoundCommand> pendingCommands = new ArrayList<>();
    private final boolean[] playing = new boolean[9]; // 1-indexed
    private final int[] volumes = new int[9];

    public WasmAudioBackend() {
        for (int i = 1; i <= 8; i++) volumes[i] = 255;
    }

    @Override
    public void play(int channelNum, byte[] audioData, String format, int loopCount) {
        if (channelNum < 1 || channelNum > 8) return;
        pendingCommands.add(new SoundCommand("play", channelNum, audioData, format, loopCount, volumes[channelNum]));
        playing[channelNum] = true;
    }

    @Override
    public void stop(int channelNum) {
        if (channelNum < 1 || channelNum > 8) return;
        pendingCommands.add(new SoundCommand("stop", channelNum, null, null, 0, 0));
        playing[channelNum] = false;
    }

    @Override
    public void stopAll() {
        for (int i = 1; i <= 8; i++) {
            stop(i);
        }
    }

    @Override
    public void setVolume(int channelNum, int volume) {
        if (channelNum < 1 || channelNum > 8) return;
        volumes[channelNum] = Math.max(0, Math.min(255, volume));
        pendingCommands.add(new SoundCommand("volume", channelNum, null, null, 0, volumes[channelNum]));
    }

    @Override
    public boolean isPlaying(int channelNum) {
        if (channelNum < 1 || channelNum > 8) return false;
        return playing[channelNum];
    }

    @Override
    public int getElapsedTime(int channelNum) {
        return 0; // Not tracked on WASM side
    }

    // --- Queue access for JS polling ---

    public int getPendingCount() {
        return pendingCommands.size();
    }

    public SoundCommand getPending(int index) {
        if (index < 0 || index >= pendingCommands.size()) return null;
        return pendingCommands.get(index);
    }

    public void drainPending() {
        pendingCommands.clear();
    }

    /** Called by JS to notify that a channel has stopped playing. */
    public void notifyStopped(int channelNum) {
        if (channelNum >= 1 && channelNum <= 8) {
            playing[channelNum] = false;
        }
    }
}
