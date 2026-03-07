package com.libreshockwave.player.input;

import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.player.render.StageRenderer;

import java.util.List;

/**
 * Determines which sprite is under a given stage coordinate.
 * Tests from front-to-back (highest locZ/channel first).
 */
public final class HitTester {

    private HitTester() {}

    /**
     * Find the front-most visible sprite containing the given point.
     * @return the sprite's channel number, or 0 if no sprite hit
     */
    public static int hitTest(StageRenderer renderer, int frame, int stageX, int stageY) {
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);

        // Iterate back-to-front order from getSpritesForFrame, so we check last (front-most) first
        for (int i = sprites.size() - 1; i >= 0; i--) {
            RenderSprite sprite = sprites.get(i);
            if (!sprite.isVisible()) continue;
            if (sprite.getChannel() <= 0) continue;

            int left = sprite.getX();
            int top = sprite.getY();
            int right = left + sprite.getWidth();
            int bottom = top + sprite.getHeight();

            if (stageX >= left && stageX < right && stageY >= top && stageY < bottom) {
                return sprite.getChannel();
            }
        }

        return 0;
    }
}
