package com.libreshockwave.player.render;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.sprite.SpriteState;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes what sprites to render for the current frame.
 * This is the main rendering API for player-core that UI implementations can use.
 *
 * Usage:
 * <pre>
 *   StageRenderer renderer = player.getStageRenderer();
 *   List<RenderSprite> sprites = renderer.getSpritesForFrame(currentFrame);
 *   for (RenderSprite sprite : sprites) {
 *       // Draw sprite using your UI framework
 *   }
 * </pre>
 */
public class StageRenderer {

    private final DirectorFile file;
    private final SpriteRegistry spriteRegistry;

    private int backgroundColor = 0xFFFFFF;  // White default

    public StageRenderer(DirectorFile file) {
        this.file = file;
        this.spriteRegistry = new SpriteRegistry();
    }

    /**
     * Get the sprite registry for runtime state access.
     */
    public SpriteRegistry getSpriteRegistry() {
        return spriteRegistry;
    }

    /**
     * Get the stage width from the Director file.
     */
    public int getStageWidth() {
        return file != null ? file.getStageWidth() : 640;
    }

    /**
     * Get the stage height from the Director file.
     */
    public int getStageHeight() {
        return file != null ? file.getStageHeight() : 480;
    }

    /**
     * Get the background color (RGB).
     */
    public int getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Set the background color (RGB).
     */
    public void setBackgroundColor(int color) {
        this.backgroundColor = color;
    }

    /**
     * Get all sprites to render for the given frame.
     * Sprites are returned in channel order (lower channels draw first).
     *
     * @param frame The 1-indexed frame number
     * @return List of sprites to render
     */
    public List<RenderSprite> getSpritesForFrame(int frame) {
        List<RenderSprite> sprites = new ArrayList<>();

        if (file == null) {
            return sprites;
        }

        ScoreChunk score = file.getScoreChunk();
        if (score == null) {
            return sprites;
        }

        int frameIndex = frame - 1;  // Convert to 0-indexed

        // Collect all channel data for this frame
        for (ScoreChunk.FrameChannelEntry entry : score.frameData().frameChannelData()) {
            if (entry.frameIndex() == frameIndex) {
                RenderSprite sprite = createRenderSprite(entry.channelIndex(), entry.data());
                if (sprite != null) {
                    sprites.add(sprite);
                }
            }
        }

        // Sort by channel (lower channels draw first/behind)
        sprites.sort((a, b) -> Integer.compare(a.getChannel(), b.getChannel()));

        return sprites;
    }

    /**
     * Create a RenderSprite from channel data.
     */
    private RenderSprite createRenderSprite(int channel, ScoreChunk.ChannelData data) {
        // Skip empty sprites
        if (data.isEmpty() || data.spriteType() == 0) {
            return null;
        }

        // Get or create runtime state
        SpriteState state = spriteRegistry.getOrCreate(channel, data);

        // Use runtime position (may be modified by scripts)
        int x = state.getLocH();
        int y = state.getLocV();
        int width = state.getWidth();
        int height = state.getHeight();
        boolean visible = state.isVisible();

        // Get cast member
        CastMemberChunk member = file.getCastMemberByIndex(data.castLib(), data.castMember());

        // Determine sprite type
        RenderSprite.SpriteType type = determineSpriteType(member, data);

        return new RenderSprite(
            channel,
            x, y,
            width, height,
            visible,
            type,
            member,
            data.foreColor(),
            data.backColor(),
            data.ink()
        );
    }

    /**
     * Determine the sprite type from the cast member.
     */
    private RenderSprite.SpriteType determineSpriteType(CastMemberChunk member, ScoreChunk.ChannelData data) {
        if (member == null) {
            return RenderSprite.SpriteType.UNKNOWN;
        }

        if (member.isBitmap()) {
            return RenderSprite.SpriteType.BITMAP;
        }

        MemberType memberType = member.memberType();
        if (memberType == null) {
            return RenderSprite.SpriteType.UNKNOWN;
        }

        return switch (memberType) {
            case SHAPE -> RenderSprite.SpriteType.SHAPE;
            case TEXT -> RenderSprite.SpriteType.TEXT;
            case BUTTON -> RenderSprite.SpriteType.BUTTON;
            default -> RenderSprite.SpriteType.UNKNOWN;
        };
    }

    /**
     * Clear all sprite states (called on movie stop/reset).
     */
    public void reset() {
        spriteRegistry.clear();
    }

    /**
     * Called when a sprite leaves the stage.
     */
    public void onSpriteEnd(int channel) {
        spriteRegistry.remove(channel);
    }

    /**
     * Called when entering a new frame to update sprite positions from score.
     */
    public void onFrameEnter(int frame) {
        if (file == null) return;

        ScoreChunk score = file.getScoreChunk();
        if (score == null) return;

        int frameIndex = frame - 1;

        for (ScoreChunk.FrameChannelEntry entry : score.frameData().frameChannelData()) {
            if (entry.frameIndex() == frameIndex) {
                int channel = entry.channelIndex();
                if (spriteRegistry.contains(channel)) {
                    spriteRegistry.updateFromScore(channel, entry.data());
                }
            }
        }
    }
}
