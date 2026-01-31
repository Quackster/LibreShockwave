package com.libreshockwave.tools.preview;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.tools.model.CastMemberInfo;
import com.libreshockwave.tools.model.FrameAppearance;
import com.libreshockwave.tools.scanning.MemberResolver;
import com.libreshockwave.tools.score.FrameAppearanceFinder;

import java.util.List;

/**
 * Generates sound preview content.
 */
public class SoundPreview {

    private final FrameAppearanceFinder appearanceFinder = new FrameAppearanceFinder();

    /**
     * Formats sound details for display.
     */
    public String format(DirectorFile dirFile, CastMemberInfo memberInfo) {
        SoundChunk soundChunk = MemberResolver.findSoundForMember(dirFile, memberInfo.member());

        StringBuilder sb = new StringBuilder();
        sb.append("=== SOUND: ").append(memberInfo.name()).append(" ===\n\n");
        sb.append("Member ID: ").append(memberInfo.memberNum()).append("\n\n");

        if (soundChunk != null) {
            sb.append("--- Audio Properties ---\n");
            sb.append("Codec: ").append(soundChunk.isMp3() ? "MP3" : "PCM (16-bit)").append("\n");
            sb.append("Sample Rate: ").append(soundChunk.sampleRate()).append(" Hz\n");
            sb.append("Bits Per Sample: ").append(soundChunk.bitsPerSample()).append("\n");
            sb.append("Channels: ").append(soundChunk.channelCount() == 1 ? "Mono" : "Stereo").append("\n");
            sb.append("Duration: ").append(String.format("%.2f", soundChunk.durationSeconds())).append(" seconds\n");
            sb.append("Audio Data Size: ").append(soundChunk.audioData().length).append(" bytes\n");
        } else {
            sb.append("[Sound data not found]\n");
        }

        // Show frame appearances from score
        sb.append("\n--- Score Appearances ---\n");
        List<FrameAppearance> appearances = appearanceFinder.find(dirFile, memberInfo.memberNum());
        if (appearances.isEmpty()) {
            sb.append("Not used in score\n");
        } else {
            sb.append(appearanceFinder.format(appearances)).append("\n");
            // Show detailed list if not too many
            if (appearances.size() <= 20) {
                sb.append("\nDetailed appearances:\n");
                for (FrameAppearance app : appearances) {
                    sb.append(String.format("  Frame %d, %s", app.frame(), app.channelName()));
                    if (app.frameLabel() != null) {
                        sb.append(" [").append(app.frameLabel()).append("]");
                    }
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Returns true if the sound chunk exists and is playable.
     */
    public boolean isPlayable(DirectorFile dirFile, CastMemberInfo memberInfo) {
        SoundChunk soundChunk = MemberResolver.findSoundForMember(dirFile, memberInfo.member());
        return soundChunk != null;
    }
}
