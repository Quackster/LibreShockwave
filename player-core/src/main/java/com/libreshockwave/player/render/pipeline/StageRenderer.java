package com.libreshockwave.player.render.pipeline;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.cast.ShapeInfo;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.SpriteRegistry;
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

    // Last baked sprites from FrameSnapshot — used for ink-aware hit testing
    private volatile List<RenderSprite> lastBakedSprites = List.of();

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

    /** Store baked sprites from last rendered frame for hit testing. */
    public void setLastBakedSprites(List<RenderSprite> sprites) {
        this.lastBakedSprites = sprites;
    }

    /** Get baked sprites from last rendered frame (for ink-aware hit testing). */
    public List<RenderSprite> getLastBakedSprites() {
        return lastBakedSprites;
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
                    if (entry.frameIndex().value() == frameIndex) {
                        int channel = entry.channelIndex().value();
                        SpriteState state = spriteRegistry.get(channel);

                        // Check if this channel has a dynamic member override
                        if (state != null && state.hasDynamicMember()) {
                            RenderSprite sprite = createDynamicRenderSprite(state);
                            if (sprite != null) {
                                sprites.add(sprite);
                                renderedChannels.add(channel);
                            }
                        } else {
                            RenderSprite sprite = createRenderSprite(entry.channelIndex().value(), entry.data());
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
        List<SpriteState> dynSprites = spriteRegistry.getDynamicSprites();
        for (SpriteState state : dynSprites) {
            int channel = state.getChannel();
            if (!renderedChannels.contains(channel)
                    && (state.hasDynamicMember() || state.isPuppet())) {
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

        // Apply registration point offset (scaled for stretched sprites per ScummVM behavior)
        if (member != null) {
            int[] reg = scaledRegPoint(member, width, height);
            x -= reg[0];
            y -= reg[1];
        }

        RenderSprite.SpriteType type = member != null ? determineSpriteTypeFromMember(member) : RenderSprite.SpriteType.UNKNOWN;

        // Score spriteType 2-8 are tool-palette shapes (rect, oval, line).
        // Only promote to SHAPE if the cast member is actually a Shape type.
        // Flash (SWF) members, scripts, and other non-shape types that happen to
        // be on shape sprite channels must NOT be rendered as solid fills.
        if (type == RenderSprite.SpriteType.UNKNOWN && data.spriteType() >= 2 && data.spriteType() <= 8
                && member != null && member.memberType() == com.libreshockwave.cast.MemberType.SHAPE) {
            type = RenderSprite.SpriteType.SHAPE;
        }

        return new RenderSprite(
            channel, x, y, width, height, locZ, visible, type, member, null,
            state.hasForeColor() ? state.getForeColor() : data.resolvedForeColor(),
            state.hasBackColor() ? state.getBackColor() : data.backColor(),
            state.hasForeColor(), state.hasBackColor(),
            data.ink(), state.getBlend(),
            state.isFlipH(), state.isFlipV(), null,
            state.hasScriptBehaviors()
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
            // Puppeted sprites without a member can still render if they have
            // bgColor set (e.g., window system color swatches). In Director,
            // a visible sprite with bgColor fills its rect with that color.
            if (state.isPuppet() && state.hasBackColor()) {
                int[] pos = state.snapshotPosition();
                int w = pos[3], h = pos[4];
                if (w > 0 && h > 0) {
                    // Use bgColor as fill color (Director fills puppet sprite rects
                    // with bgColor when no member bitmap is set)
                    int fillColor = state.getBackColor();
                    return new RenderSprite(
                        state.getChannel(),
                        pos[0], pos[1], w, h,
                        pos[2], true,
                        RenderSprite.SpriteType.SHAPE,
                        null, null,
                        fillColor, state.getBackColor(),
                        true, state.hasBackColor(),
                        0, state.getBlend(), // COPY ink for solid fill
                        state.isFlipH(), state.isFlipV(),
                        null, state.hasScriptBehaviors());
                }
            }
            return null;
        }

        // Snapshot position atomically to prevent torn reads from VM thread
        int[] pos = state.snapshotPosition();
        int x = pos[0];
        int y = pos[1];
        int locZ = pos[2];
        int width = pos[3];
        int height = pos[4];

        // Look up the cast member — try CastLibManager first for dynamic sprites,
        // since the castLib/castMember values are runtime numbers from the VM
        // (which may differ from the DCR file's internal cast numbering).
        CastMemberChunk member = null;
        if (castLibManager != null) {
            member = castLibManager.getCastMember(castLib, castMember);
        }
        if (member == null && file != null) {
            member = file.getCastMemberByIndex(castLib, castMember);
        }

        // Also resolve the runtime CastMember — needed to detect Lingo-modified bitmaps
        CastMember dynamicMember = null;
        if (castLibManager != null) {
            dynamicMember = castLibManager.getDynamicMember(castLib, castMember);
        }

        RenderSprite.SpriteType type = RenderSprite.SpriteType.UNKNOWN;
        if (member != null) {
            type = determineSpriteTypeFromMember(member);
            // Apply registration point offset (scaled for stretched sprites)
            int[] reg = scaledRegPoint(member, width, height);
            x -= reg[0];
            y -= reg[1];
            // Fallback auto-size: if sprite still has 0x0 dimensions, derive from member
            if (width == 0 && height == 0 && member.isBitmap()
                    && member.specificData() != null && member.specificData().length >= 10) {
                var bi = com.libreshockwave.cast.BitmapInfo.parse(member.specificData());
                width = bi.width();
                height = bi.height();
            }
        } else if (dynamicMember != null) {
            type = determineSpriteTypeFromDynamic(dynamicMember);
            // Apply registration point offset from dynamic member
            x -= dynamicMember.getRegPointX();
            y -= dynamicMember.getRegPointY();
            // Fallback auto-size for dynamic members
            if (width == 0 && height == 0) {
                int dw = dynamicMember.getProp("width").toInt();
                int dh = dynamicMember.getProp("height").toInt();
                if (dw > 0 && dh > 0) {
                    width = dw;
                    height = dh;
                }
            }
        }

        return new RenderSprite(
            state.getChannel(),
            x, y,
            width, height,
            locZ,
            state.isVisible(),
            type, member, dynamicMember,
            state.getForeColor(), state.getBackColor(),
            state.hasForeColor(), state.hasBackColor(),
            state.getInk(), state.getBlend(),
            state.isFlipH(), state.isFlipV(), null,
            state.hasScriptBehaviors()
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
            case FLASH -> {
                byte[] sd = member.specificData();
                if (sd != null && sd.length >= 14) {
                    ShapeInfo si = ShapeInfo.parse(sd);
                    if (si.shapeType() != ShapeInfo.ShapeType.UNKNOWN) {
                        yield RenderSprite.SpriteType.SHAPE;
                    }
                }
                yield RenderSprite.SpriteType.UNKNOWN;
            }
            case TEXT -> RenderSprite.SpriteType.TEXT;
            case BUTTON -> RenderSprite.SpriteType.BUTTON;
            default -> RenderSprite.SpriteType.UNKNOWN;
        };
    }

    /**
     * Scale registration point proportionally when sprite dimensions differ from bitmap dimensions.
     * Director (confirmed via ScummVM) scales regPoint by spriteSize/bitmapSize for stretched sprites.
     * @return int array {scaledRegX, scaledRegY}
     */
    private int[] scaledRegPoint(CastMemberChunk member, int spriteWidth, int spriteHeight) {
        int regX = member.regPointX();
        int regY = member.regPointY();
        if ((spriteWidth > 0 || spriteHeight > 0) && member.isBitmap()
                && member.specificData() != null && member.specificData().length >= 10) {
            var bi = com.libreshockwave.cast.BitmapInfo.parse(member.specificData());
            int bmpW = bi.width();
            int bmpH = bi.height();
            if (bmpW > 0 && bmpW != spriteWidth) {
                regX = regX * spriteWidth / bmpW;
            }
            if (bmpH > 0 && bmpH != spriteHeight) {
                regY = regY * spriteHeight / bmpH;
            }
        }
        return new int[]{ regX, regY };
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
            if (entry.frameIndex().value() == frameIndex) {
                int channel = entry.channelIndex().value();
                if (spriteRegistry.contains(channel)) {
                    spriteRegistry.updateFromScore(channel, entry.data());
                }
            }
        }
    }
}
