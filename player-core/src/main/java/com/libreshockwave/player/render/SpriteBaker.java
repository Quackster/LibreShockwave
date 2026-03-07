package com.libreshockwave.player.render;

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
        // Only applied when Lingo has explicitly set foreColor or backColor
        // on the sprite (via sprite.color, sprite.foreColor, sprite.backColor, etc.).
        if (baked != null && sprite.getType() != RenderSprite.SpriteType.SHAPE
                && (sprite.hasForeColor() || sprite.hasBackColor())
                && InkProcessor.allowsColorize(sprite.getInk())) {
            baked = InkProcessor.applyForeColorRemap(baked, sprite.getForeColor(), sprite.getBackColor());
        }

        return sprite.withBakedBitmap(baked);
    }

    /**
     * Bake a BITMAP sprite: decode + ink-process via BitmapCache.
     */
    private Bitmap bakeBitmap(RenderSprite sprite) {
        Bitmap b = null;
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

        if (member == null) {
            return null;
        }

        Bitmap textImage = member.renderTextToImage();
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
