package com.libreshockwave.player;

import com.libreshockwave.cast.CastMember;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.cast.ShapeInfo;
import com.libreshockwave.player.Palette.InkMode;
import com.libreshockwave.player.bitmap.Bitmap;
import com.libreshockwave.player.bitmap.Drawing;

import java.util.*;

/**
 * Renders the Director stage, including sprites, film loops, and other visual elements.
 * Supports recursive rendering for film loops.
 */
public class StageRenderer {

    private final int stageWidth;
    private final int stageHeight;
    private final int stageColor;

    private final Map<Integer, Bitmap> bitmapCache;
    private final Map<Integer, FilmLoop> filmLoopCache;
    private final Map<Integer, CastMember> memberCache;

    private int maxRecursionDepth = 10;

    public StageRenderer(int width, int height, int stageColor) {
        this.stageWidth = width;
        this.stageHeight = height;
        this.stageColor = stageColor;
        this.bitmapCache = new HashMap<>();
        this.filmLoopCache = new HashMap<>();
        this.memberCache = new HashMap<>();
    }

    /**
     * Create a new stage bitmap filled with the stage color.
     */
    public Bitmap createStageBitmap() {
        Bitmap stage = new Bitmap(stageWidth, stageHeight, 32);
        stage.fill(stageColor);
        return stage;
    }

    /**
     * Render a list of sprites to a bitmap.
     */
    public Bitmap renderFrame(List<Sprite> sprites) {
        Bitmap stage = createStageBitmap();
        renderSprites(stage, sprites, 0);
        return stage;
    }

    /**
     * Render sprites to an existing bitmap.
     */
    public void renderSprites(Bitmap target, List<Sprite> sprites, int recursionDepth) {
        if (recursionDepth > maxRecursionDepth) {
            return; // Prevent infinite recursion
        }

        // Sort sprites by channel (z-order)
        List<Sprite> sorted = new ArrayList<>(sprites);
        sorted.sort(Comparator.comparingInt(Sprite::getChannel));

        for (Sprite sprite : sorted) {
            if (!sprite.isVisible()) continue;

            renderSprite(target, sprite, recursionDepth);
        }
    }

    /**
     * Render a single sprite to the target bitmap.
     */
    public void renderSprite(Bitmap target, Sprite sprite, int recursionDepth) {
        CastMember member = getMember(sprite.getCastLib(), sprite.getMemberNum());
        if (member == null) return;

        MemberType type = member.getMemberType();

        switch (type) {
            case BITMAP -> renderBitmapSprite(target, sprite, member);
            case FILM_LOOP -> renderFilmLoopSprite(target, sprite, member, recursionDepth);
            case SHAPE -> renderShapeSprite(target, sprite, member);
            case TEXT -> renderTextSprite(target, sprite, member);
            case BUTTON -> renderButtonSprite(target, sprite, member);
            default -> {
                // Other member types not rendered
            }
        }
    }

    /**
     * Render a bitmap cast member.
     */
    private void renderBitmapSprite(Bitmap target, Sprite sprite, CastMember member) {
        Bitmap sourceBitmap = getBitmap(member.getCastLib(), member.getMemberNum());
        if (sourceBitmap == null) return;

        int destX = sprite.getLocH() - sprite.getRegPointH();
        int destY = sprite.getLocV() - sprite.getRegPointV();

        InkMode ink = InkMode.fromCode(sprite.getInk());
        int blend = sprite.getBlend() * 255 / 100;

        Drawing.copyPixels(target, sourceBitmap, destX, destY, ink, blend);
    }

    /**
     * Render a film loop cast member (recursive).
     */
    private void renderFilmLoopSprite(Bitmap target, Sprite sprite, CastMember member, int recursionDepth) {
        FilmLoop filmLoop = getFilmLoop(member.getCastLib(), member.getMemberNum());
        if (filmLoop == null) return;

        FilmLoop.Frame frame = filmLoop.getCurrentFrameData();
        if (frame == null) return;

        // Calculate offset for the film loop
        int offsetX = sprite.getLocH() - sprite.getRegPointH();
        int offsetY = sprite.getLocV() - sprite.getRegPointV();

        // Render film loop to a temporary bitmap first
        Bitmap filmBitmap = new Bitmap(filmLoop.getWidth(), filmLoop.getHeight(), 32);
        filmBitmap.fill(0x00FFFFFF); // Transparent white

        // Convert film loop sprites to regular sprites with offsets
        List<Sprite> filmSprites = new ArrayList<>();
        for (FilmLoop.SpriteState state : frame.getSprites()) {
            Sprite s = new Sprite(state.getChannel());
            s.setMemberNum(state.getMemberNum());
            s.setCastLib(state.getCastLib());
            s.setLocH(state.getLocH());
            s.setLocV(state.getLocV());
            s.setWidth(state.getWidth());
            s.setHeight(state.getHeight());
            s.setInk(state.getInk());
            s.setBlend(state.getBlend());
            s.setVisible(state.isVisible());
            filmSprites.add(s);
        }

        // Recursively render the film loop contents
        renderSprites(filmBitmap, filmSprites, recursionDepth + 1);

        // Draw the film loop bitmap to the target
        InkMode ink = InkMode.fromCode(sprite.getInk());
        int blend = sprite.getBlend() * 255 / 100;
        Drawing.copyPixels(target, filmBitmap, offsetX, offsetY, ink, blend);
    }

    /**
     * Render a shape cast member.
     */
    private void renderShapeSprite(Bitmap target, Sprite sprite, CastMember member) {
        int x = sprite.getLocH() - sprite.getRegPointH();
        int y = sprite.getLocV() - sprite.getRegPointV();
        int w = sprite.getWidth();
        int h = sprite.getHeight();

        int color = 0xFF000000 | sprite.getForeColor();

        // Get shape type from member info
        ShapeInfo.ShapeType shapeType = ShapeInfo.ShapeType.RECT;
        if (member.getShapeInfo() != null) {
            shapeType = member.getShapeInfo().shapeType();
        }

        switch (shapeType) {
            case RECT -> Drawing.fillRect(target, x, y, w, h, color);
            case OVAL_RECT -> Drawing.drawRect(target, x, y, w, h, color); // Simplified
            case OVAL -> Drawing.fillEllipse(target, x + w/2, y + h/2, w/2, h/2, color);
            case LINE -> Drawing.drawLine(target, x, y, x + w, y + h, color);
            case UNKNOWN -> Drawing.fillRect(target, x, y, w, h, color);
        }
    }

    /**
     * Render a text/field cast member (simplified).
     */
    private void renderTextSprite(Bitmap target, Sprite sprite, CastMember member) {
        // Text rendering would require font support
        // For now, just draw a placeholder rectangle
        int x = sprite.getLocH() - sprite.getRegPointH();
        int y = sprite.getLocV() - sprite.getRegPointV();
        int w = sprite.getWidth();
        int h = sprite.getHeight();

        int bgColor = 0xFFFFFFFF; // White background
        Drawing.fillRect(target, x, y, w, h, bgColor);
        Drawing.drawRect(target, x, y, w, h, 0xFF000000); // Black border
    }

    /**
     * Render a button cast member.
     */
    private void renderButtonSprite(Bitmap target, Sprite sprite, CastMember member) {
        int x = sprite.getLocH() - sprite.getRegPointH();
        int y = sprite.getLocV() - sprite.getRegPointV();
        int w = sprite.getWidth();
        int h = sprite.getHeight();

        // Simple 3D button effect
        Drawing.fillRect(target, x, y, w, h, 0xFFCCCCCC); // Gray fill
        Drawing.drawLine(target, x, y, x + w - 1, y, 0xFFFFFFFF); // Top highlight
        Drawing.drawLine(target, x, y, x, y + h - 1, 0xFFFFFFFF); // Left highlight
        Drawing.drawLine(target, x + w - 1, y, x + w - 1, y + h - 1, 0xFF888888); // Right shadow
        Drawing.drawLine(target, x, y + h - 1, x + w - 1, y + h - 1, 0xFF888888); // Bottom shadow
    }

    // Cache management

    public void cacheBitmap(int castLib, int memberNum, Bitmap bitmap) {
        int key = (castLib << 16) | memberNum;
        bitmapCache.put(key, bitmap);
    }

    public Bitmap getBitmap(int castLib, int memberNum) {
        int key = (castLib << 16) | memberNum;
        return bitmapCache.get(key);
    }

    public void cacheFilmLoop(int castLib, int memberNum, FilmLoop filmLoop) {
        int key = (castLib << 16) | memberNum;
        filmLoopCache.put(key, filmLoop);
    }

    public FilmLoop getFilmLoop(int castLib, int memberNum) {
        int key = (castLib << 16) | memberNum;
        return filmLoopCache.get(key);
    }

    public void cacheMember(CastMember member) {
        int key = (member.getCastLib() << 16) | member.getMemberNum();
        memberCache.put(key, member);
    }

    public CastMember getMember(int castLib, int memberNum) {
        int key = (castLib << 16) | memberNum;
        return memberCache.get(key);
    }

    public void clearCache() {
        bitmapCache.clear();
        filmLoopCache.clear();
        memberCache.clear();
    }

    // Configuration

    public void setMaxRecursionDepth(int depth) {
        this.maxRecursionDepth = depth;
    }

    public int getStageWidth() { return stageWidth; }
    public int getStageHeight() { return stageHeight; }
    public int getStageColor() { return stageColor; }
}
