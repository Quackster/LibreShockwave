package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.ChannelId;
import com.libreshockwave.id.InkMode;
import com.libreshockwave.player.cast.CastMember;

/**
 * Represents a sprite to be rendered on the stage.
 * Contains all information needed by a renderer to draw the sprite.
 */
public final class RenderSprite {

    private final ChannelId channelId;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int locZ;
    private final boolean visible;
    private final SpriteType type;
    private final CastMemberChunk castMember;
    private final CastMember dynamicMember; // For runtime-created members (window system, etc.)
    private final int foreColor;
    private final int backColor;
    private final boolean hasForeColor;
    private final boolean hasBackColor;
    private final InkMode inkMode;
    private final int blend;
    private final boolean flipH;
    private final boolean flipV;
    private final Bitmap bakedBitmap;
    private final boolean hasBehaviors;

    public RenderSprite(
            int channel,
            int x, int y,
            int width, int height,
            boolean visible,
            SpriteType type,
            CastMemberChunk castMember,
            int foreColor, int backColor, int ink, int blend) {
        this(channel, x, y, width, height, 0, visible, type, castMember, null,
             foreColor, backColor, false, false, ink, blend, false, false, null, false);
    }

    public RenderSprite(
            int channel,
            int x, int y,
            int width, int height,
            int locZ,
            boolean visible,
            SpriteType type,
            CastMemberChunk castMember,
            CastMember dynamicMember,
            int foreColor, int backColor,
            boolean hasForeColor, boolean hasBackColor,
            int ink, int blend,
            boolean flipH, boolean flipV,
            Bitmap bakedBitmap,
            boolean hasBehaviors) {
        this.channelId = new ChannelId(channel);
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.locZ = locZ;
        this.visible = visible;
        this.type = type;
        this.castMember = castMember;
        this.dynamicMember = dynamicMember;
        this.foreColor = foreColor;
        this.backColor = backColor;
        this.hasForeColor = hasForeColor;
        this.hasBackColor = hasBackColor;
        this.inkMode = InkMode.fromCode(ink);
        this.blend = blend;
        this.flipH = flipH;
        this.flipV = flipV;
        this.bakedBitmap = bakedBitmap;
        this.hasBehaviors = hasBehaviors;
    }

    public ChannelId getChannelId() { return channelId; }
    public int getChannel() { return channelId.value(); }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getLocZ() { return locZ; }
    public boolean isVisible() { return visible; }
    public SpriteType getType() { return type; }
    public CastMemberChunk getCastMember() { return castMember; }
    public CastMember getDynamicMember() { return dynamicMember; }
    public int getForeColor() { return foreColor; }
    public int getBackColor() { return backColor; }
    public boolean hasForeColor() { return hasForeColor; }
    public boolean hasBackColor() { return hasBackColor; }
    public InkMode getInkMode() { return inkMode; }
    public int getInk() { return inkMode.code(); }
    public int getBlend() { return blend; }
    public boolean isFlipH() { return flipH; }
    public boolean isFlipV() { return flipV; }
    public Bitmap getBakedBitmap() { return bakedBitmap; }
    public boolean hasBehaviors() { return hasBehaviors; }

    /**
     * Return a new RenderSprite with all fields copied but with the given baked bitmap.
     */
    public RenderSprite withBakedBitmap(Bitmap baked) {
        return new RenderSprite(channelId.value(), x, y, width, height, locZ, visible, type,
            castMember, dynamicMember, foreColor, backColor, hasForeColor, hasBackColor,
            inkMode.code(), blend, flipH, flipV, baked, hasBehaviors);
    }

    /**
     * Get the cast member ID, or -1 if no member.
     */
    public int getCastMemberId() {
        if (castMember != null) return castMember.id().value();
        if (dynamicMember != null) return dynamicMember.getMemberNumber();
        return -1;
    }

    /**
     * Get the member name, or null if no member.
     */
    public String getMemberName() {
        if (castMember != null) return castMember.name();
        if (dynamicMember != null) return dynamicMember.getName();
        return null;
    }

    public enum SpriteType {
        BITMAP,
        SHAPE,
        TEXT,
        BUTTON,
        UNKNOWN
    }
}
