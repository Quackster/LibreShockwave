package com.libreshockwave.player.audio;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;

/**
 * Audio backend using javax.sound.sampled for desktop (Swing) playback.
 * Each channel maintains an independent Clip for concurrent sound playback.
 */
public class SwingAudioBackend implements AudioBackend {

    private static final int MAX_CHANNELS = 8;

    private final ChannelState[] channels = new ChannelState[MAX_CHANNELS + 1];

    public SwingAudioBackend() {
        for (int i = 1; i <= MAX_CHANNELS; i++) {
            channels[i] = new ChannelState();
        }
    }

    @Override
    public void play(int channelNum, byte[] audioData, String format, int loopCount) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return;
        ChannelState ch = channels[channelNum];

        // Stop any currently playing clip
        ch.stop();

        try {
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(audioData));

            AudioFormat baseFormat = audioStream.getFormat();

            // If not already PCM, decode (e.g., MP3 via SPI)
            if (baseFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    && baseFormat.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
                AudioFormat decodedFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false
                );
                audioStream = AudioSystem.getAudioInputStream(decodedFormat, audioStream);
            }

            Clip clip = AudioSystem.getClip();
            clip.open(audioStream);

            // Set volume
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float volume = ch.volume / 255.0f;
                // Convert linear volume to dB
                float dB = volume > 0 ? (float) (20 * Math.log10(volume)) : gain.getMinimum();
                dB = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB));
                gain.setValue(dB);
            }

            // Set looping
            if (loopCount == 0) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            } else if (loopCount > 1) {
                clip.loop(loopCount - 1);
            } else {
                clip.start();
            }

            if (loopCount != 0 && loopCount <= 1) {
                clip.start();
            }

            ch.clip = clip;
            ch.startTime = System.currentTimeMillis();

        } catch (Exception e) {
            System.err.println("[SwingAudioBackend] Error playing sound on channel " + channelNum + ": " + e.getMessage());
        }
    }

    @Override
    public void stop(int channelNum) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return;
        channels[channelNum].stop();
    }

    @Override
    public void stopAll() {
        for (int i = 1; i <= MAX_CHANNELS; i++) {
            channels[i].stop();
        }
    }

    @Override
    public void setVolume(int channelNum, int volume) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return;
        ChannelState ch = channels[channelNum];
        ch.volume = Math.max(0, Math.min(255, volume));
        if (ch.clip != null && ch.clip.isOpen() && ch.clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) ch.clip.getControl(FloatControl.Type.MASTER_GAIN);
            float vol = ch.volume / 255.0f;
            float dB = vol > 0 ? (float) (20 * Math.log10(vol)) : gain.getMinimum();
            dB = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB));
            gain.setValue(dB);
        }
    }

    @Override
    public boolean isPlaying(int channelNum) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return false;
        Clip clip = channels[channelNum].clip;
        return clip != null && clip.isOpen() && clip.isRunning();
    }

    @Override
    public int getElapsedTime(int channelNum) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return 0;
        ChannelState ch = channels[channelNum];
        if (ch.clip != null && ch.clip.isOpen()) {
            return (int) (ch.clip.getMicrosecondPosition() / 1000);
        }
        return 0;
    }

    private static class ChannelState {
        Clip clip;
        int volume = 255;
        long startTime;

        void stop() {
            if (clip != null) {
                try {
                    if (clip.isRunning()) clip.stop();
                    clip.close();
                } catch (Exception ignored) {}
                clip = null;
            }
        }
    }
}
