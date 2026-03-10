package com.libreshockwave.player.render.output;

import com.libreshockwave.bitmap.Bitmap;

/**
 * Platform-agnostic interface for text rendering and measurement.
 * Desktop player provides an AWT implementation; WASM provides a simple stub.
 * <p>
 * Used by CastMember to render text members to bitmap images and
 * to implement charPosToLoc() measurements.
 */
public interface TextRenderer {

    /**
     * Render text content to a bitmap image.
     *
     * @return rendered bitmap, or null if rendering is not supported
     */
    Bitmap renderText(String text, int width, int height,
                      String fontName, int fontSize, String fontStyle,
                      String alignment, int textColor, int bgColor,
                      boolean wordWrap, boolean antialias,
                      int fixedLineSpace, int topSpacing);

    /**
     * Compute the pixel position of a character in text.
     * Used for Director's charPosToLoc() method.
     *
     * @return int array {x, y} in pixels
     */
    int[] charPosToLoc(String text, int charIndex,
                       String fontName, int fontSize, String fontStyle,
                       int fixedLineSpace);
}
