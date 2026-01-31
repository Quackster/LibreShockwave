package com.libreshockwave.tools.preview;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.PaletteChunk;
import com.libreshockwave.tools.model.CastMemberInfo;
import com.libreshockwave.tools.scanning.MemberResolver;

import java.awt.image.BufferedImage;

/**
 * Generates palette preview content.
 */
public class PalettePreview {

    /**
     * Result of palette preview generation.
     */
    public record PaletteResult(BufferedImage swatchImage, int colorCount) {}

    /**
     * Generates a palette swatch image.
     */
    public PaletteResult generateSwatch(DirectorFile dirFile, CastMemberInfo memberInfo) {
        PaletteChunk paletteChunk = MemberResolver.findPaletteForMember(dirFile, memberInfo.member());
        if (paletteChunk != null) {
            int[] colors = paletteChunk.colors();
            Bitmap swatch = Bitmap.createPaletteSwatch(colors, 16, 16);
            return new PaletteResult(swatch.toBufferedImage(), colors.length);
        }
        return null;
    }

    /**
     * Formats palette details as text.
     */
    public String format(DirectorFile dirFile, CastMemberInfo memberInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PALETTE: ").append(memberInfo.name()).append(" ===\n\n");
        sb.append("Member ID: ").append(memberInfo.memberNum()).append("\n\n");

        PaletteChunk paletteChunk = MemberResolver.findPaletteForMember(dirFile, memberInfo.member());
        if (paletteChunk != null) {
            int[] colors = paletteChunk.colors();
            sb.append("--- Palette Info ---\n");
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
            sb.append("[Palette data not found]\n");
        }

        return sb.toString();
    }
}
