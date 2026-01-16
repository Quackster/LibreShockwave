package com.libreshockwave.player;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.sound.sampled.*;

/**
 * Represents a sound cast member with playback support.
 */
public class SoundMember {

    private final int castLib;
    private final int memberNum;
    private final String name;

    // Audio format info
    private int sampleRate;
    private int sampleSize; // bits per sample
    private int channels;
    private boolean signed;
    private boolean bigEndian;

    // Raw audio data
    private byte[] audioData;
    private AudioFormat format;

    // Playback state
    private Clip clip;
    private boolean looping;
    private float volume;

    public SoundMember(int castLib, int memberNum, String name) {
        this.castLib = castLib;
        this.memberNum = memberNum;
        this.name = name;
        this.sampleRate = 22050;
        this.sampleSize = 16;
        this.channels = 1;
        this.signed = true;
        this.bigEndian = true;
        this.looping = false;
        this.volume = 1.0f;
    }

    /**
     * Set audio data from raw PCM samples.
     */
    public void setAudioData(byte[] data, int sampleRate, int sampleSize, int channels,
                              boolean signed, boolean bigEndian) {
        this.audioData = data;
        this.sampleRate = sampleRate;
        this.sampleSize = sampleSize;
        this.channels = channels;
        this.signed = signed;
        this.bigEndian = bigEndian;
        this.format = new AudioFormat(
            sampleRate, sampleSize, channels, signed, bigEndian
        );

        // Clean up existing clip
        if (clip != null) {
            clip.close();
            clip = null;
        }
    }

    /**
     * Set audio data from WAV format bytes.
     */
    public void setWavData(byte[] wavData) throws IOException {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                new ByteArrayInputStream(wavData));
            this.format = ais.getFormat();
            this.sampleRate = (int) format.getSampleRate();
            this.sampleSize = format.getSampleSizeInBits();
            this.channels = format.getChannels();
            this.signed = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
            this.bigEndian = format.isBigEndian();

            // Read all audio data
            this.audioData = ais.readAllBytes();
            ais.close();

            if (clip != null) {
                clip.close();
                clip = null;
            }
        } catch (UnsupportedAudioFileException e) {
            throw new IOException("Unsupported audio format", e);
        }
    }

    /**
     * Play the sound.
     */
    public void play() {
        if (audioData == null || format == null) return;

        try {
            if (clip == null) {
                clip = AudioSystem.getClip();
                AudioInputStream ais = new AudioInputStream(
                    new ByteArrayInputStream(audioData),
                    format,
                    audioData.length / format.getFrameSize()
                );
                clip.open(ais);
            }

            clip.setFramePosition(0);
            setClipVolume(volume);

            if (looping) {
                clip.loop(Clip.LOOP_CONTINUOUSLY);
            } else {
                clip.start();
            }
        } catch (LineUnavailableException | IOException e) {
            System.err.println("Failed to play sound: " + e.getMessage());
        }
    }

    /**
     * Stop playback.
     */
    public void stop() {
        if (clip != null && clip.isRunning()) {
            clip.stop();
        }
    }

    /**
     * Pause playback.
     */
    public void pause() {
        if (clip != null && clip.isRunning()) {
            clip.stop();
        }
    }

    /**
     * Resume playback from current position.
     */
    public void resume() {
        if (clip != null && !clip.isRunning()) {
            clip.start();
        }
    }

    /**
     * Check if currently playing.
     */
    public boolean isPlaying() {
        return clip != null && clip.isRunning();
    }

    /**
     * Get playback position in milliseconds.
     */
    public long getPosition() {
        if (clip == null) return 0;
        return clip.getMicrosecondPosition() / 1000;
    }

    /**
     * Set playback position in milliseconds.
     */
    public void setPosition(long millis) {
        if (clip != null) {
            clip.setMicrosecondPosition(millis * 1000);
        }
    }

    /**
     * Get duration in milliseconds.
     */
    public long getDuration() {
        if (clip == null) return 0;
        return clip.getMicrosecondLength() / 1000;
    }

    /**
     * Set volume (0.0 to 1.0).
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (clip != null) {
            setClipVolume(this.volume);
        }
    }

    private void setClipVolume(float vol) {
        try {
            FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (float) (Math.log10(Math.max(0.0001, vol)) * 20.0);
            gainControl.setValue(Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB)));
        } catch (IllegalArgumentException e) {
            // Volume control not supported
        }
    }

    public float getVolume() {
        return volume;
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    public boolean isLooping() {
        return looping;
    }

    /**
     * Release resources.
     */
    public void dispose() {
        if (clip != null) {
            clip.stop();
            clip.close();
            clip = null;
        }
    }

    // Getters
    public int getCastLib() { return castLib; }
    public int getMemberNum() { return memberNum; }
    public String getName() { return name; }
    public int getSampleRate() { return sampleRate; }
    public int getSampleSize() { return sampleSize; }
    public int getChannels() { return channels; }
    public byte[] getAudioData() { return audioData; }
}
