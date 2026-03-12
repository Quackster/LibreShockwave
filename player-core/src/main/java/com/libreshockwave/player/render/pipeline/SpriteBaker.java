package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized sprite-to-bitmap baking pipeline.
 * Converts all sprite types (BITMAP, TEXT, SHAPE) into pre-rendered bitmaps
 * so renderers only need to blit pixels — no type-specific draw logic needed.
 */
public class SpriteBaker {

    private final BitmapCache bitmapCache;
    private final CastLibManager castLibManager;
    private final Player player;

    public SpriteBaker(BitmapCache bitmapCache, CastLibManager castLibManager, Player player) {
        this.bitmapCache = bitmapCache;
        this.castLibManager = castLibManager;
        this.player = player;
    }

    /**
     * Bake all sprites in the list, returning a new list with baked bitmaps attached.
     */
    public List<RenderSprite> bakeSprites(List<RenderSprite> sprites) {
        List<RenderSprite> result = new ArrayList<>(sprites.size());
        for (RenderSprite sprite : sprites) {
            result.add(bake(sprite));
        }
        return result;
    }

    /**
     * Bake a single sprite: dispatch by type, apply colorization, attach bitmap.
     */
    public RenderSprite bake(RenderSprite sprite) {
        Bitmap baked = switch (sprite.getType()) {
            case BITMAP -> bakeBitmap(sprite);
            case TEXT, BUTTON -> bakeText(sprite);
            case SHAPE -> bakeShape(sprite);
            default -> null;
        };

        // Apply Director's sprite-level foreColor/backColor colorization.
        int ch = sprite.getChannel();
        boolean hasColor = sprite.hasForeColor() || sprite.hasBackColor();
        boolean colorizeOk = InkProcessor.allowsColorize(sprite.getInk());
        boolean scriptMod = isScriptModifiedSprite(sprite);
        if (baked != null && sprite.getType() != RenderSprite.SpriteType.SHAPE && hasColor && colorizeOk) {
            if (scriptMod) {
                // Script-modified bitmaps (window system buffers, copyPixels results):
                // Apply simple bgColor replacement — Director replaces white pixels with
                // backColor for COPY ink sprites. This is safe for colored bitmaps because
                // only exact white (0xFFFFFF) pixels are replaced, preserving existing content.
                int bgColor = sprite.getBackColor() & 0xFFFFFF;
                if (sprite.hasBackColor() && bgColor != 0xFFFFFF) {
                    baked = InkProcessor.remapExactColor(baked, 0xFFFFFF, bgColor);
                }
            } else {
                // File-loaded member bitmaps (1-bit icons, etc.): apply full grayscale remap
                // where white→backColor, black→foreColor, gray→interpolated
                baked = InkProcessor.applyForeColorRemap(baked, sprite.getForeColor(), sprite.getBackColor());
            }
        }

        return sprite.withBakedBitmap(baked);
    }

    private boolean isScriptModifiedSprite(RenderSprite sprite) {
        if (sprite.getDynamicMember() == null) return false;
        Bitmap bmp = sprite.getDynamicMember().getBitmap();
        return bmp != null && bmp.isScriptModified();
    }

    /**
     * Bake a BITMAP sprite: decode + ink-process via BitmapCache.
     */
    private Bitmap bakeBitmap(RenderSprite sprite) {
        Bitmap b = null;

        // Check if the runtime CastMember's bitmap was modified by Lingo (fill, copyPixels, etc.)
        // If so, use the live bitmap directly instead of the stale BitmapCache entry.
        if (sprite.getDynamicMember() != null) {
            Bitmap liveBmp = sprite.getDynamicMember().getBitmap();
            if (liveBmp != null && liveBmp.isScriptModified()) {
                if (InkProcessor.shouldProcessInk(sprite.getInk())) {
                    return InkProcessor.applyInk(liveBmp, sprite.getInk(),
                            sprite.getBackColor(), false, null);
                }
                return liveBmp;
            }
        }

        if (sprite.getCastMember() != null) {
            // Check for runtime palette override (palette swap animation)
            PaletteOverrideInfo palInfo = resolvePaletteOverride(sprite);
            Palette paletteOverride = null;
            if (palInfo != null) {
                // Only invalidate cache when palette actually changed
                bitmapCache.invalidateIfPaletteChanged(
                        sprite.getCastMember().id().value(), palInfo.version);
                paletteOverride = palInfo.palette;
            }
            b = bitmapCache.getProcessed(sprite.getCastMember(), sprite.getInk(),
                    sprite.getBackColor(), player, paletteOverride);
        }
        if (b == null && sprite.getDynamicMember() != null) {
            b = bitmapCache.getProcessedDynamic(sprite.getDynamicMember(),
                    sprite.getInk(), sprite.getBackColor());
        }
        return b;
    }

    private record PaletteOverrideInfo(Palette palette, int version) {}

    /**
     * Resolve a palette override for a sprite's bitmap member.
     * Returns null if no palette override is set.
     */
    private PaletteOverrideInfo resolvePaletteOverride(RenderSprite sprite) {
        if (castLibManager == null || sprite.getCastMember() == null) {
            return null;
        }

        // Find the runtime CastMember for this sprite's bitmap by name
        String name = sprite.getCastMember().name();
        if (name == null || name.isEmpty()) {
            return null;
        }

        CastMember member = castLibManager.findCastMemberByName(name);
        if (member == null || !member.hasPaletteOverride()) {
            return null;
        }

        // Resolve the palette member to a Palette object
        int palCastLib = member.getPaletteRefCastLib();
        int palMemberNum = member.getPaletteRefMemberNum();

        CastLib paletteCastLib = castLibManager.getCastLib(palCastLib);
        if (paletteCastLib == null) {
            return null;
        }

        DirectorFile palFile = paletteCastLib.getSourceFile();
        if (palFile == null) {
            return null;
        }

        Palette palette = palFile.resolvePaletteByMemberNumber(palMemberNum);
        return palette != null ? new PaletteOverrideInfo(palette, member.getPaletteVersion()) : null;
    }

    /**
     * Bake a TEXT or BUTTON sprite: resolve member, render text to bitmap.
     */
    private Bitmap bakeText(RenderSprite sprite) {
        // Try dynamic member first (runtime-created text/field members)
        CastMember member = sprite.getDynamicMember();

        // Fall back to file-loaded member lookup by name
        if (member == null && sprite.getCastMember() != null && castLibManager != null) {
            String memberName = sprite.getCastMember().name();
            if (memberName != null && !memberName.isEmpty()) {
                member = castLibManager.findCastMemberByName(memberName);
            }
        }

        // Fall back to runtime member lookup by chunk ID (for nameless members
        // like XTRA text members whose text may have been set by Lingo)
        if (member == null && sprite.getCastMember() != null && castLibManager != null) {
            member = castLibManager.findRuntimeMember(sprite.getCastMember());
        }

        Bitmap textImage = null;

        if (member != null && member.hasDynamicText()) {
            // Lingo set member.text — render using sprite dimensions
            var renderer = CastMember.getTextRendererStatic();
            if (renderer != null) {
                int width = sprite.getWidth() > 0 ? sprite.getWidth() : 200;
                int height = sprite.getHeight() > 0 ? sprite.getHeight() : 20;
                int textColor = 0xFF000000 | (sprite.getForeColor() & 0xFFFFFF);
                int bgColor = 0xFF000000 | (sprite.getBackColor() & 0xFFFFFF);
                textImage = renderer.renderText(
                        member.getTextContent(), width, height,
                        member.getTextFont(), member.getTextFontSize(), "",
                        "left", textColor, bgColor,
                        true, false, 0, 0);
            }
        } else if (member != null) {
            textImage = member.renderTextToImage();
        }

        // Fall back to rendering directly from the file's STXT/XMED chunk
        // (for score-placed text sprites that don't have a runtime CastMember,
        // or when renderTextToImage returned null)
        if (textImage == null && sprite.getCastMember() != null) {
            textImage = bakeTextFromFile(sprite);
        }

        if (textImage == null) {
            return null;
        }

        // Apply ink processing if needed (e.g., Background Transparent for text)
        if (InkProcessor.shouldProcessInk(sprite.getInk())) {
            textImage = InkProcessor.applyInk(textImage, sprite.getInk(),
                    sprite.getBackColor(), false, null);
        }

        return textImage;
    }

    /**
     * Render text directly from file data (CASt specificData + STXT/XMED chunk).
     * Used for score-placed text sprites that don't have a runtime CastMember.
     * Handles both regular STXT text and Director 7+ Text Asset Xtras (XMED chunks).
     */
    private Bitmap bakeTextFromFile(RenderSprite sprite) {
        var castMember = sprite.getCastMember();
        var file = castMember.file();
        if (file == null) return null;

        // Try XMED text first (Director 7+ Text Asset Xtra)
        if (castMember.isTextXtra()) {
            return bakeTextFromXmed(sprite, file, castMember);
        }

        // Look up the STXT chunk via KEY* table
        var textChunk = file.getTextForMember(castMember);
        if (textChunk == null || textChunk.text() == null) return null;

        // Parse text formatting from specificData
        var textInfo = com.libreshockwave.cast.TextInfo.parse(castMember.specificData());

        // Get font info from STXT formatting runs (first run determines primary font)
        String fontName = "Arial";
        int fontSize = 12;
        int fontStyle = 0;
        if (!textChunk.runs().isEmpty()) {
            var run = textChunk.runs().get(0);
            fontSize = run.fontSize();
            fontStyle = run.fontStyle();
        }

        String styleStr = "";
        if ((fontStyle & 1) != 0) styleStr += "bold";
        if ((fontStyle & 2) != 0) styleStr += (styleStr.isEmpty() ? "" : ",") + "italic";
        if ((fontStyle & 4) != 0) styleStr += (styleStr.isEmpty() ? "" : ",") + "underline";

        String alignment = switch (textInfo.textAlign()) {
            case 1 -> "center";
            case -1 -> "right";
            default -> "left";
        };

        int width = textInfo.width() > 0 ? textInfo.width() : sprite.getWidth();
        int height = textInfo.height() > 0 ? textInfo.height() : sprite.getHeight();
        if (width <= 0) width = 200;
        if (height <= 0) height = 20;

        var renderer = CastMember.getTextRendererStatic();
        if (renderer == null) return null;

        // Use sprite foreColor for text color; ARGB format with full alpha
        int textColor = 0xFF000000 | (sprite.getForeColor() & 0xFFFFFF);
        int bgColor = 0xFF000000 | ((textInfo.bgRed() << 16) | (textInfo.bgGreen() << 8) | textInfo.bgBlue());

        return renderer.renderText(
                textChunk.text(), width, height,
                fontName, fontSize, styleStr,
                alignment, textColor, bgColor,
                textInfo.isWordWrap(), false,
                0, 0);
    }

    /**
     * Render text from XMED chunk data (Director 7+ Text Asset Xtra).
     */
    private Bitmap bakeTextFromXmed(RenderSprite sprite, com.libreshockwave.DirectorFile file,
                                     com.libreshockwave.chunks.CastMemberChunk castMember) {
        var xmedText = file.getXmedTextForMember(castMember);
        if (xmedText == null || xmedText.text() == null || xmedText.text().isEmpty()) {
            return null;
        }

        var renderer = CastMember.getTextRendererStatic();
        if (renderer == null) return null;

        int width = sprite.getWidth() > 0 ? sprite.getWidth() : 200;
        int height = sprite.getHeight() > 0 ? sprite.getHeight() : 20;

        // ARGB format — text color from sprite foreColor, background from backColor
        int textColor = 0xFF000000 | (sprite.getForeColor() & 0xFFFFFF);
        int bgColor = 0xFF000000 | (sprite.getBackColor() & 0xFFFFFF);

        return renderer.renderText(
                xmedText.text(), width, height,
                xmedText.fontName(), xmedText.fontSize(), "",
                "left", textColor, bgColor,
                true, false,
                0, 0);
    }

    /**
     * Bake a SHAPE sprite: create a solid-color bitmap filled with the sprite's foreColor.
     */
    private Bitmap bakeShape(RenderSprite sprite) {
        int w = sprite.getWidth() > 0 ? sprite.getWidth() : 50;
        int h = sprite.getHeight() > 0 ? sprite.getHeight() : 50;
        int fc = sprite.getForeColor();

        Bitmap shape = new Bitmap(w, h, 32);
        int argb = 0xFF000000 | (fc & 0xFFFFFF);
        int[] pixels = shape.getPixels();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = argb;
        }

        return shape;
    }
}
