package com.libreshockwave.tools.audio;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.tools.model.MemberNodeData;
import com.libreshockwave.tools.scanning.MemberResolver;

import javax.sound.sampled.*;
import javax.swing.*;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Manages audio playback for sound cast members.
 */
public class AudioPlaybackController {

    private Clip currentClip;
    private Timer playbackTimer;
    private MemberNodeData currentSoundMember;
    private final Map<String, DirectorFile> loadedFiles;

    private Consumer<String> statusCallback;
    private Runnable onPlaybackStopped;
    private Consumer<PlaybackState> onStateChanged;

    public record PlaybackState(boolean isPlaying, int progressPercent, String timeLabel) {}

    public AudioPlaybackController(Map<String, DirectorFile> loadedFiles) {
        this.loadedFiles = loadedFiles;
        this.playbackTimer = new Timer(100, e -> updatePlaybackPosition());
    }

    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    public void setOnPlaybackStopped(Runnable callback) {
        this.onPlaybackStopped = callback;
    }

    public void setOnStateChanged(Consumer<PlaybackState> callback) {
        this.onStateChanged = callback;
    }

    public void setCurrentMember(MemberNodeData memberData) {
        this.currentSoundMember = memberData;
    }

    public MemberNodeData getCurrentMember() {
        return currentSoundMember;
    }

    public void play() {
        if (currentSoundMember == null) return;

        stop(); // Stop any playing sound

        DirectorFile dirFile = loadedFiles.get(currentSoundMember.filePath());
        if (dirFile == null) return;

        SoundChunk soundChunk = MemberResolver.findSoundForMember(dirFile, currentSoundMember.memberInfo().member());
        if (soundChunk == null) return;

        try {
            byte[] audioData;

            if (soundChunk.isMp3()) {
                // Extract MP3 data - with mp3spi library, AudioSystem can play MP3
                audioData = SoundConverter.extractMp3(soundChunk);
                if (audioData == null || audioData.length == 0) {
                    setStatus("Failed to extract MP3 data");
                    return;
                }
            } else {
                // Convert PCM to WAV
                audioData = SoundConverter.toWav(soundChunk);
            }

            if (audioData == null || audioData.length <= 44) {
                setStatus("No audio data to play");
                return;
            }

            // Play the audio (mp3spi adds MP3 support to AudioSystem)
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(audioData));

            // For MP3, we need to convert to PCM format that Clip can handle
            AudioFormat baseFormat = audioStream.getFormat();
            if (baseFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
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

            currentClip = AudioSystem.getClip();
            currentClip.open(audioStream);
            currentClip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    SwingUtilities.invokeLater(() -> {
                        if (onPlaybackStopped != null) {
                            onPlaybackStopped.run();
                        }
                        playbackTimer.stop();
                        notifyState(false, 0, formatTimeLabel(0, currentClip != null ? currentClip.getMicrosecondLength() : 0));
                    });
                }
            });

            currentClip.start();
            playbackTimer.start();
            setStatus("Playing: " + currentSoundMember.memberInfo().name());
            notifyState(true, 0, formatTimeLabel(0, currentClip.getMicrosecondLength()));

        } catch (Exception ex) {
            setStatus("Playback error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    public void stop() {
        playbackTimer.stop();
        if (currentClip != null) {
            currentClip.stop();
            currentClip.close();
            currentClip = null;
        }
        notifyState(false, 0, "0.0s / 0.0s");
    }

    public void togglePause() {
        if (currentClip == null) return;

        if (currentClip.isRunning()) {
            currentClip.stop();
            playbackTimer.stop();
        } else {
            currentClip.start();
            playbackTimer.start();
        }
    }

    public boolean isPlaying() {
        return currentClip != null && currentClip.isRunning();
    }

    public void seekTo(int percent) {
        if (currentClip != null) {
            long newPos = (long) ((percent / 100.0) * currentClip.getMicrosecondLength());
            currentClip.setMicrosecondPosition(newPos);
        }
    }

    public long getDurationMicros() {
        return currentClip != null ? currentClip.getMicrosecondLength() : 0;
    }

    public void dispose() {
        stop();
        if (playbackTimer != null) {
            playbackTimer.stop();
        }
    }

    private void updatePlaybackPosition() {
        if (currentClip != null && currentClip.isRunning()) {
            long pos = currentClip.getMicrosecondPosition();
            long len = currentClip.getMicrosecondLength();
            int percent = (int) ((pos * 100.0) / len);
            notifyState(true, percent, formatTimeLabel(pos, len));
        }
    }

    private String formatTimeLabel(long posMicros, long lenMicros) {
        double posSec = posMicros / 1_000_000.0;
        double lenSec = lenMicros / 1_000_000.0;
        return String.format("%.1fs / %.1fs", posSec, lenSec);
    }

    private void setStatus(String status) {
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }

    private void notifyState(boolean playing, int percent, String timeLabel) {
        if (onStateChanged != null) {
            onStateChanged.accept(new PlaybackState(playing, percent, timeLabel));
        }
    }
}
