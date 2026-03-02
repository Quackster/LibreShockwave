package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;

/**
 * Simple text renderer that creates placeholder images without AWT dependencies.
 * Used in TeaVM/WASM environments where java.awt is not available.
 * Actual text rendering in the browser happens via Canvas 2D on the JavaScript side.
 */
public class SimpleTextRenderer implements TextRenderer {

    @Override
    public Bitmap renderText(String text, int width, int height,
                             String fontName, int fontSize, String fontStyle,
                             String alignment, int textColor, int bgColor,
                             boolean wordWrap, boolean antialias,
                             int fixedLineSpace, int topSpacing) {
        if (width <= 0) width = 200;
        if (height <= 0) height = 20;

        Bitmap bmp = new Bitmap(width, height, 32);
        bmp.fill(bgColor);
        return bmp;
    }

    @Override
    public int[] charPosToLoc(String text, int charIndex,
                              String fontName, int fontSize, String fontStyle,
                              int fixedLineSpace) {
        // Approximate: assume ~60% of fontSize per character width
        int approxCharWidth = Math.max(1, fontSize * 6 / 10);
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : (int) (fontSize * 1.2);

        if (text == null || text.isEmpty() || charIndex <= 0) {
            return new int[]{0, lineHeight};
        }

        // Find which line the character is on
        String[] lines = text.split("[\r\n]");
        int charsSoFar = 0;
        int lineNum = 0;
        String lineText = lines.length > 0 ? lines[0] : "";
        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1; // +1 for line break
            if (charsSoFar + lineLen >= charIndex) {
                lineNum = i;
                lineText = lines[i];
                break;
            }
            charsSoFar += lineLen;
        }

        int charsOnLine = Math.min(charIndex - charsSoFar, lineText.length());
        int x = charsOnLine * approxCharWidth;
        int y = lineNum * lineHeight + (int) (fontSize * 0.8); // approximate ascent
        return new int[]{x, y};
    }
}
