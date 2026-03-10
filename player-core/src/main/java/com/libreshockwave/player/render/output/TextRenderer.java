package com.libreshockwave.player.render.output;

import com.libreshockwave.bitmap.Bitmap;

import java.util.List;
import java.util.function.ToIntFunction;

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

    /**
     * Word-wrap a single line of text into multiple lines that fit within maxWidth.
     * Shared by all TextRenderer implementations.
     *
     * @param text         the line to wrap
     * @param measureWidth function that returns the pixel width of a string
     * @param maxWidth     maximum pixel width per line
     * @param out          list to append wrapped lines to
     */
    /**
     * Find which line a character index falls on in multi-line text.
     * Shared by charPosToLoc implementations.
     *
     * @param text      the full text content
     * @param charIndex 1-based character index
     * @return int array {lineNum, charsOnLine} (0-based lineNum, clamped charsOnLine)
     */
    static int[] findCharLine(String text, int charIndex) {
        int idx = Math.min(charIndex, text.length());
        String[] lines = text.split("[\r\n]");
        int lineNum = 0;
        int charsSoFar = 0;
        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1; // +1 for line break
            if (charsSoFar + lineLen >= idx) {
                lineNum = i;
                break;
            }
            charsSoFar += lineLen;
        }
        int charsOnLine = Math.min(idx - charsSoFar,
                lineNum < lines.length ? lines[lineNum].length() : 0);
        return new int[]{ lineNum, charsOnLine };
    }

    static void wrapLine(String text, ToIntFunction<String> measureWidth, int maxWidth, List<String> out) {
        if (text.isEmpty()) {
            out.add("");
            return;
        }
        if (measureWidth.applyAsInt(text) <= maxWidth) {
            out.add(text);
            return;
        }
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else {
                String candidate = current + " " + word;
                if (measureWidth.applyAsInt(candidate) <= maxWidth) {
                    current.append(" ").append(word);
                } else {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
    }
}
