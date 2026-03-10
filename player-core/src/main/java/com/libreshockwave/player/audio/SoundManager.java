package com.libreshockwave.player.audio;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.MediaChunk;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.builtin.media.SoundProvider;

/**
 * Manages 8 sound channels for Director audio playback.
 * Resolves cast member references to audio data and delegates to AudioBackend.
 */
public class SoundManager implements SoundProvider {

    public static final int MAX_CHANNELS = 8;

    private final CastLibManager castLibManager;
    private AudioBackend backend;

    // Per-channel state
    private final int[] volumes = new int[MAX_CHANNELS + 1]; // 1-indexed

    public SoundManager(CastLibManager castLibManager) {
        this.castLibManager = castLibManager;
        for (int i = 1; i <= MAX_CHANNELS; i++) {
            volumes[i] = 255;
        }
    }

    public void setBackend(AudioBackend backend) {
        this.backend = backend;
    }

    public AudioBackend getBackend() {
        return backend;
    }

    /**
     * Play a sound member on the given channel.
     * Called from Lingo: sound(N).play([#member: memberRef, #loopCount: count])
     *
     * @param channelNum 1-based channel number
     * @param args PropList with #member and optional #loopCount
     */
    public void play(int channelNum, Datum args) {
        if (backend == null || channelNum < 1 || channelNum > MAX_CHANNELS) return;

        // Extract member reference and loop count from args
        Datum memberRef = null;
        int loopCount = 1;

        if (args instanceof Datum.PropList pl) {
            Datum memberDatum = pl.get("member");
            if (memberDatum != null) memberRef = memberDatum;
            Datum loopDatum = pl.get("loopCount");
            if (loopDatum != null && !loopDatum.isVoid()) loopCount = loopDatum.toInt();
        } else if (args instanceof Datum.CastMemberRef) {
            memberRef = args;
        } else if (args instanceof Datum.List list && !list.items().isEmpty()) {
            // Could be a playlist - play the first item
            Datum first = list.items().get(0);
            if (first instanceof Datum.PropList) {
                play(channelNum, first);
                return;
            }
        }

        if (memberRef == null) {
            // No member specified - play() with no args means resume (no-op for now)
            return;
        }

        byte[] audioData = resolveAudioData(memberRef);
        if (audioData == null) return;

        String format = audioData.length > 2 && audioData[0] == 'R' && audioData[1] == 'I' ? "wav" : "mp3";
        backend.play(channelNum, audioData, format, loopCount);
        backend.setVolume(channelNum, volumes[channelNum]);
    }

    /**
     * Stop a sound channel.
     */
    public void stop(int channelNum) {
        if (backend == null || channelNum < 1 || channelNum > MAX_CHANNELS) return;
        backend.stop(channelNum);
    }

    /**
     * Stop all channels.
     */
    public void stopAll() {
        if (backend != null) backend.stopAll();
    }

    /**
     * Set volume for a channel.
     */
    public void setVolume(int channelNum, int volume) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return;
        volumes[channelNum] = Math.max(0, Math.min(255, volume));
        if (backend != null) backend.setVolume(channelNum, volumes[channelNum]);
    }

    /**
     * Get volume for a channel.
     */
    public int getVolume(int channelNum) {
        if (channelNum < 1 || channelNum > MAX_CHANNELS) return 255;
        return volumes[channelNum];
    }

    /**
     * Check if a channel is playing.
     */
    public boolean isPlaying(int channelNum) {
        if (backend == null || channelNum < 1 || channelNum > MAX_CHANNELS) return false;
        return backend.isPlaying(channelNum);
    }

    /**
     * Get elapsed time in milliseconds.
     */
    public int getElapsedTime(int channelNum) {
        if (backend == null || channelNum < 1 || channelNum > MAX_CHANNELS) return 0;
        return backend.getElapsedTime(channelNum);
    }

    /**
     * Resolve a member reference to playable audio bytes (WAV or MP3).
     */
    private byte[] resolveAudioData(Datum memberRef) {
        int castLibNum, memberNum;

        if (memberRef instanceof Datum.CastMemberRef cmr) {
            castLibNum = cmr.castLibNum();
            memberNum = cmr.memberNum();
        } else {
            return null;
        }

        // Get the CastLib and its source DirectorFile
        CastLib castLib = castLibManager.getCastLib(castLibNum);
        if (castLib == null) return null;

        DirectorFile sourceFile = castLib.getSourceFile();
        if (sourceFile == null) return null;

        // Find the CastMemberChunk
        CastMemberChunk memberChunk = castLib.findMemberByNumber(memberNum);
        if (memberChunk == null) return null;

        // Find associated SoundChunk via KeyTable
        SoundChunk soundChunk = findSoundForMember(sourceFile, memberChunk);
        if (soundChunk == null) return null;

        // Convert to playable format
        try {
            if (soundChunk.isMp3()) {
                byte[] mp3 = SoundConverter.extractMp3(soundChunk);
                return mp3 != null && mp3.length > 0 ? mp3 : null;
            } else if (soundChunk.isAdpcm()) {
                return SoundConverter.imaAdpcmToWav(
                        soundChunk.audioData(),
                        soundChunk.sampleRate(),
                        soundChunk.channelCount(), 0, 0);
            } else {
                return SoundConverter.toWav(soundChunk);
            }
        } catch (Exception e) {
            System.err.println("[SoundManager] Failed to convert audio for member " + memberNum + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Find the SoundChunk associated with a cast member (same logic as MemberResolver).
     */
    private static SoundChunk findSoundForMember(DirectorFile dirFile, CastMemberChunk member) {
        var keyTable = dirFile.getKeyTable();
        if (keyTable == null) return null;

        for (var entry : keyTable.getEntriesForOwner(member.id())) {
            var chunk = dirFile.getChunk(entry.sectionId());
            if (chunk instanceof SoundChunk sc) {
                return sc;
            }
            if (chunk instanceof MediaChunk mc) {
                return mc.toSoundChunk();
            }
        }
        return null;
    }
}
