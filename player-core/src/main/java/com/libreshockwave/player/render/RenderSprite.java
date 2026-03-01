package com.libreshockwave.player.render;

import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.cast.CastMember;

/**
 * Represents a sprite to be rendered on the stage.
 * Contains all information needed by a renderer to draw the sprite.
 */
public final class RenderSprite {

    private final int channel;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final boolean visible;
    private final SpriteType type;
    private final CastMemberChunk castMember;
    private final CastMember dynamicMember; // For runtime-created members (window system, etc.)
    private final int foreColor;
    private final int backColor;
    private final int ink;
    private final int blend;

    public RenderSprite(
            int channel,
            int x, int y,
            int width, int height,
            boolean visible,
            SpriteType type,
            CastMemberChunk castMember,
            int foreColor, int backColor, int ink, int blend) {
        this(channel, x, y, width, height, visible, type, castMember, null,
             foreColor, backColor, ink, blend);
    }

    public RenderSprite(
            int channel,
            int x, int y,
            int width, int height,
            boolean visible,
            SpriteType type,
            CastMemberChunk castMember,
            CastMember dynamicMember,
            int foreColor, int backColor, int ink, int blend) {
        this.channel = channel;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.visible = visible;
        this.type = type;
        this.castMember = castMember;
        this.dynamicMember = dynamicMember;
        this.foreColor = foreColor;
        this.backColor = backColor;
        this.ink = ink;
        this.blend = blend;
    }

    public int getChannel() { return channel; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isVisible() { return visible; }
    public SpriteType getType() { return type; }
    public CastMemberChunk getCastMember() { return castMember; }
    public CastMember getDynamicMember() { return dynamicMember; }
    public int getForeColor() { return foreColor; }
    public int getBackColor() { return backColor; }
    public int getInk() { return ink; }
    public int getBlend() { return blend; }

    /**
     * Get the cast member ID, or -1 if no member.
     */
    public int getCastMemberId() {
        if (castMember != null) return castMember.id();
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
