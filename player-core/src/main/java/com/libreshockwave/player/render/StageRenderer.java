package com.libreshockwave.player.render;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.sprite.SpriteState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes what sprites to render for the current frame.
 * Supports both Score-based sprites and dynamically puppeted sprites.
 */
public class StageRenderer {

    private final DirectorFile file;
    private final SpriteRegistry spriteRegistry;
    private CastLibManager castLibManager;

    private int backgroundColor = 0xFFFFFF;  // White default

    public StageRenderer(DirectorFile file) {
        this.file = file;
        this.spriteRegistry = new SpriteRegistry();
    }

    public void setCastLibManager(CastLibManager castLibManager) {
        this.castLibManager = castLibManager;
    }

    public SpriteRegistry getSpriteRegistry() {
        return spriteRegistry;
    }

    public int getStageWidth() {
        return file != null ? file.getStageWidth() : 640;
    }

    public int getStageHeight() {
        return file != null ? file.getStageHeight() : 480;
    }

    public int getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(int color) {
        this.backgroundColor = color;
    }

    /**
     * Get all sprites to render for the given frame.
     * Includes both Score-based sprites and dynamically created/puppeted sprites.
     */
    public List<RenderSprite> getSpritesForFrame(int frame) {
        List<RenderSprite> sprites = new ArrayList<>();
        Set<Integer> renderedChannels = new HashSet<>();

        // 1. Collect Score-based sprites
        if (file != null) {
            ScoreChunk score = file.getScoreChunk();
            if (score != null) {
                int frameIndex = frame - 1;  // Convert to 0-indexed

                for (ScoreChunk.FrameChannelEntry entry : score.frameData().frameChannelData()) {
                    if (entry.frameIndex() == frameIndex) {
                        int channel = entry.channelIndex();
                        SpriteState state = spriteRegistry.get(channel);

                        // Check if this channel has a dynamic member override
                        if (state != null && state.hasDynamicMember()) {
                            RenderSprite sprite = createDynamicRenderSprite(state);
                            if (sprite != null) {
                                sprites.add(sprite);
                                renderedChannels.add(channel);
                            }
                        } else {
                            RenderSprite sprite = createRenderSprite(entry.channelIndex(), entry.data());
                            if (sprite != null) {
                                sprites.add(sprite);
                                renderedChannels.add(channel);
                            }
                        }
                    }
                }
            }
        }

        // 2. Add dynamically created/puppeted sprites not in the Score
        for (SpriteState state : spriteRegistry.getDynamicSprites()) {
            int channel = state.getChannel();
            if (!renderedChannels.contains(channel) && state.hasDynamicMember()) {
                RenderSprite sprite = createDynamicRenderSprite(state);
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
     * Create a RenderSprite from Score channel data.
     */
    private RenderSprite createRenderSprite(int channel, ScoreChunk.ChannelData data) {
        // Skip empty sprites
        if (data.isEmpty() || data.spriteType() == 0) {
            return null;
        }

        // Get or create runtime state
        SpriteState state = spriteRegistry.getOrCreate(channel, data);

        int x = state.getLocH();
        int y = state.getLocV();
        int width = state.getWidth();
        int height = state.getHeight();
        boolean visible = state.isVisible();

        // Get cast member
        CastMemberChunk member = file.getCastMemberByIndex(data.castLib(), data.castMember());

        RenderSprite.SpriteType type = determineSpriteType(member, data);

        return new RenderSprite(
            channel, x, y, width, height, visible, type, member,
            data.foreColor(), data.backColor(), data.ink(), state.getBlend()
        );
    }

    /**
     * Create a RenderSprite from a dynamically modified sprite state.
     */
    private RenderSprite createDynamicRenderSprite(SpriteState state) {
        if (!state.isVisible()) {
            return null;
        }

        int castLib = state.getEffectiveCastLib();
        int castMember = state.getEffectiveCastMember();

        if (castMember <= 0) {
            return null;
        }

        // Look up the cast member - try original DCR first, then external casts
        CastMemberChunk member = file != null
            ? file.getCastMemberByIndex(castLib, castMember) : null;
        if (member == null && castLibManager != null) {
            member = castLibManager.getCastMember(castLib, castMember);
        }

        // If still not found, check dynamic members (created at runtime by window system, etc.)
        CastMember dynamicMember = null;
        RenderSprite.SpriteType type = RenderSprite.SpriteType.UNKNOWN;
        if (member != null) {
            type = determineSpriteTypeFromMember(member);
        } else if (castLibManager != null) {
            dynamicMember = castLibManager.getDynamicMember(castLib, castMember);
            if (dynamicMember != null) {
                type = determineSpriteTypeFromDynamic(dynamicMember);
            }
        }

        return new RenderSprite(
            state.getChannel(),
            state.getLocH(), state.getLocV(),
            state.getWidth(), state.getHeight(),
            state.isVisible(),
            type, member, dynamicMember,
            state.getForeColor(), state.getBackColor(), state.getInk(), state.getBlend()
        );
    }

    /**
     * Determine sprite type from a dynamic CastMember (runtime-created member).
     */
    private RenderSprite.SpriteType determineSpriteTypeFromDynamic(CastMember member) {
        MemberType memberType = member.getMemberType();
        if (memberType == null) {
            return RenderSprite.SpriteType.UNKNOWN;
        }
        return switch (memberType) {
            case BITMAP -> RenderSprite.SpriteType.BITMAP;
            case SHAPE -> RenderSprite.SpriteType.SHAPE;
            case TEXT -> RenderSprite.SpriteType.TEXT;
            case BUTTON -> RenderSprite.SpriteType.BUTTON;
            default -> RenderSprite.SpriteType.UNKNOWN;
        };
    }

    private RenderSprite.SpriteType determineSpriteType(CastMemberChunk member, ScoreChunk.ChannelData data) {
        if (member == null) {
            return RenderSprite.SpriteType.UNKNOWN;
        }
        return determineSpriteTypeFromMember(member);
    }

    private RenderSprite.SpriteType determineSpriteTypeFromMember(CastMemberChunk member) {
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

    public void reset() {
        spriteRegistry.clear();
    }

    public void onSpriteEnd(int channel) {
        spriteRegistry.remove(channel);
    }

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
