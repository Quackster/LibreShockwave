package com.libreshockwave.player.render;

import com.libreshockwave.chunks.CastMemberChunk;

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
    private final int foreColor;
    private final int backColor;
    private final int ink;

    public RenderSprite(
            int channel,
            int x, int y,
            int width, int height,
            boolean visible,
            SpriteType type,
            CastMemberChunk castMember,
            int foreColor, int backColor, int ink) {
        this.channel = channel;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.visible = visible;
        this.type = type;
        this.castMember = castMember;
        this.foreColor = foreColor;
        this.backColor = backColor;
        this.ink = ink;
    }

    public int getChannel() { return channel; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isVisible() { return visible; }
    public SpriteType getType() { return type; }
    public CastMemberChunk getCastMember() { return castMember; }
    public int getForeColor() { return foreColor; }
    public int getBackColor() { return backColor; }
    public int getInk() { return ink; }

    /**
     * Get the cast member ID, or -1 if no member.
     */
    public int getCastMemberId() {
        return castMember != null ? castMember.id() : -1;
    }

    public enum SpriteType {
        BITMAP,
        SHAPE,
        TEXT,
        BUTTON,
        UNKNOWN
    }
}
