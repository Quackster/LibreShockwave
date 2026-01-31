package com.libreshockwave.tools.preview;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.FilmLoopInfo;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.cast.ShapeInfo;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.PaletteChunk;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.tools.model.CastMemberInfo;
import com.libreshockwave.tools.model.FrameAppearance;
import com.libreshockwave.tools.scanning.MemberResolver;
import com.libreshockwave.tools.score.FrameAppearanceFinder;

import java.util.List;

/**
 * Generates preview content for generic/unsupported member types.
 */
public class GenericPreview {

    private final FrameAppearanceFinder appearanceFinder = new FrameAppearanceFinder();

    /**
     * Formats generic member details for display.
     */
    public String format(DirectorFile dirFile, CastMemberInfo memberInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(memberInfo.memberType().getName().toUpperCase())
          .append(": ").append(memberInfo.name()).append(" ===\n\n");
        sb.append("Member ID: ").append(memberInfo.memberNum()).append("\n");
        sb.append("Type: ").append(memberInfo.memberType().getName())
          .append(" (").append(memberInfo.memberType().getCode()).append(")\n");

        if (!memberInfo.details().isEmpty()) {
            sb.append("Details: ").append(memberInfo.details()).append("\n");
        }

        // Type-specific info
        CastMemberChunk member = memberInfo.member();
        if (member.specificData().length > 0) {
            sb.append("\nSpecific Data: ").append(member.specificData().length).append(" bytes\n");
            formatTypeSpecificData(sb, dirFile, memberInfo);
        }

        // Show frame appearances from score
        if (dirFile.hasScore()) {
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
                        sb.append(String.format("  Frame %d, %s at (%d, %d)",
                                app.frame(), app.channelName(), app.posX(), app.posY()));
                        if (app.frameLabel() != null) {
                            sb.append(" [").append(app.frameLabel()).append("]");
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    private void formatTypeSpecificData(StringBuilder sb, DirectorFile dirFile, CastMemberInfo memberInfo) {
        CastMemberChunk member = memberInfo.member();

        switch (memberInfo.memberType()) {
            case SHAPE -> {
                try {
                    ShapeInfo shapeInfo = ShapeInfo.parse(member.specificData());
                    sb.append("\n--- Shape Info ---\n");
                    sb.append("Shape Type: ").append(shapeInfo.shapeType()).append("\n");
                    sb.append("Dimensions: ").append(shapeInfo.width()).append("x").append(shapeInfo.height()).append("\n");
                    sb.append("Reg Point: (").append(shapeInfo.regX()).append(", ").append(shapeInfo.regY()).append(")\n");
                    sb.append("Color: ").append(shapeInfo.color()).append("\n");
                } catch (Exception e) {
                    sb.append("Error parsing shape info: ").append(e.getMessage()).append("\n");
                }
            }
            case FILM_LOOP -> {
                try {
                    FilmLoopInfo loopInfo = FilmLoopInfo.parse(member.specificData());
                    sb.append("\n--- Film Loop Info ---\n");
                    sb.append("Dimensions: ").append(loopInfo.width()).append("x").append(loopInfo.height()).append("\n");
                    sb.append("Reg Point: (").append(loopInfo.regX()).append(", ").append(loopInfo.regY()).append(")\n");
                } catch (Exception e) {
                    sb.append("Error parsing film loop info: ").append(e.getMessage()).append("\n");
                }
            }
            case RTE -> {
                sb.append("\n[Rich Text member - content not decoded]\n");
            }
            case SOUND -> {
                SoundChunk soundChunk = MemberResolver.findSoundForMember(dirFile, member);
                if (soundChunk != null) {
                    sb.append("\n--- Sound Info ---\n");
                    sb.append("Codec: ").append(soundChunk.isMp3() ? "MP3" : "PCM").append("\n");
                    sb.append("Sample Rate: ").append(soundChunk.sampleRate()).append(" Hz\n");
                    sb.append("Bits Per Sample: ").append(soundChunk.bitsPerSample()).append("\n");
                    sb.append("Channels: ").append(soundChunk.channelCount()).append("\n");
                    sb.append("Duration: ").append(String.format("%.2f", soundChunk.durationSeconds())).append(" seconds\n");
                    sb.append("Audio Data Size: ").append(soundChunk.audioData().length).append(" bytes\n");
                } else {
                    sb.append("\n[Sound data not found]\n");
                }
            }
            case PALETTE -> {
                PaletteChunk paletteChunk = MemberResolver.findPaletteForMember(dirFile, member);
                if (paletteChunk != null) {
                    int[] colors = paletteChunk.colors();
                    sb.append("\n--- Palette Info ---\n");
                    sb.append("Color Count: ").append(colors.length).append("\n");
                    sb.append("\n--- Colors ---\n");
                    for (int i = 0; i < colors.length; i++) {
                        int c = colors[i];
                        int r = (c >> 16) & 0xFF;
                        int g = (c >> 8) & 0xFF;
                        int b = c & 0xFF;
                        sb.append(String.format("[%3d] #%02X%02X%02X (R:%3d G:%3d B:%3d)\n", i, r, g, b, r, g, b));
                    }
                } else {
                    sb.append("\n[Palette data not found]\n");
                }
            }
            case DIGITAL_VIDEO, FLASH -> {
                sb.append("\n[Video/Flash member]\n");
            }
            default -> {
                // Show hex dump of first 256 bytes
                sb.append("\n--- Hex Dump (first 256 bytes) ---\n");
                byte[] data = member.specificData();
                int len = Math.min(data.length, 256);
                for (int i = 0; i < len; i += 16) {
                    sb.append(String.format("%04X: ", i));
                    for (int j = 0; j < 16 && i + j < len; j++) {
                        sb.append(String.format("%02X ", data[i + j] & 0xFF));
                    }
                    sb.append("\n");
                }
            }
        }
    }
}
