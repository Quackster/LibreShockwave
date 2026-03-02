package com.libreshockwave.player.render;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
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

    // Stage image buffer - used by (the stage).image for direct pixel drawing
    private Bitmap stageImage;

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
     * Get the stage image buffer for direct pixel drawing.
     * Creates the buffer on first access, sized to the stage dimensions.
     * Filled with background color - Lingo scripts expect an opaque stage image.
     */
    public Bitmap getStageImage() {
        if (stageImage == null) {
            int w = getStageWidth();
            int h = getStageHeight();
            stageImage = new Bitmap(w, h, 32);
            // Fill with background color (opaque) - Director's stage image is opaque
            stageImage.fill(0xFF000000 | (backgroundColor & 0xFFFFFF));
        }
        return stageImage;
    }

    /**
     * Check whether the stage image buffer has been created (i.e., scripts have used it).
     */
    public boolean hasStageImage() {
        return stageImage != null;
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

        // Sort by locZ first, then channel (lower values draw first/behind)
        sprites.sort((a, b) -> {
            int cmp = Integer.compare(a.getLocZ(), b.getLocZ());
            return cmp != 0 ? cmp : Integer.compare(a.getChannel(), b.getChannel());
        });

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

        // Snapshot position atomically to prevent torn reads from VM thread
        int[] pos = state.snapshotPosition();
        int x = pos[0];
        int y = pos[1];
        int locZ = pos[2];
        int width = pos[3];
        int height = pos[4];
        boolean visible = state.isVisible();

        // Get cast member
        CastMemberChunk member = file.getCastMemberByIndex(data.castLib(), data.castMember());

        // Apply registration point offset
        if (member != null) {
            x -= member.regPointX();
            y -= member.regPointY();
        }

        RenderSprite.SpriteType type = determineSpriteType(member, data);

        return new RenderSprite(
            channel, x, y, width, height, locZ, visible, type, member, null,
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

        // Snapshot position atomically to prevent torn reads from VM thread
        int[] pos = state.snapshotPosition();
        int x = pos[0];
        int y = pos[1];
        int locZ = pos[2];
        int width = pos[3];
        int height = pos[4];

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
            // Apply registration point offset from file-loaded member
            x -= member.regPointX();
            y -= member.regPointY();
        } else if (castLibManager != null) {
            dynamicMember = castLibManager.getDynamicMember(castLib, castMember);
            if (dynamicMember != null) {
                type = determineSpriteTypeFromDynamic(dynamicMember);
                // Apply registration point offset from dynamic member
                x -= dynamicMember.getRegPointX();
                y -= dynamicMember.getRegPointY();
            }
        }

        return new RenderSprite(
            state.getChannel(),
            x, y,
            width, height,
            locZ,
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
