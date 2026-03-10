package com.libreshockwave.player.render.output;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.font.BitmapFont;
import com.libreshockwave.player.cast.FontRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple text renderer that creates bitmap images without AWT dependencies.
 * Used in TeaVM/WASM environments where java.awt is not available.
 * Supports PFR bitmap fonts (from XMED chunks) for proper pixel font rendering,
 * with a built-in fallback font for when PFR fonts aren't available yet.
 */
public class SimpleTextRenderer implements TextRenderer {

    @Override
    public Bitmap renderText(String text, int width, int height,
                             String fontName, int fontSize, String fontStyle,
                             String alignment, int textColor, int bgColor,
                             boolean wordWrap, boolean antialias,
                             int fixedLineSpace, int topSpacing) {
        if (text == null) text = "";
        if (width <= 0) width = 200;
        if (height <= 0) height = 20;

        String style = fontStyle != null ? fontStyle.toLowerCase() : "";
        boolean syntheticBold = style.contains("bold");
        boolean underline = style.contains("underline");

        // Check for PFR bitmap font
        BitmapFont pfrFont = resolveBitmapFont(fontName, fontSize);
        if (pfrFont != null) {
            return renderWithBitmapFont(pfrFont, text, width, height,
                    alignment, textColor, bgColor, wordWrap,
                    fixedLineSpace, topSpacing, syntheticBold, underline);
        }

        // Fallback: render with built-in pixel font
        return renderWithBuiltinFont(text, width, height, fontSize,
                alignment, textColor, bgColor, wordWrap,
                fixedLineSpace, topSpacing, underline);
    }

    @Override
    public int[] charPosToLoc(String text, int charIndex,
                              String fontName, int fontSize, String fontStyle,
                              int fixedLineSpace) {
        // Check PFR bitmap font first
        BitmapFont pfrFont = resolveBitmapFont(fontName, fontSize);
        if (pfrFont != null) {
            int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : pfrFont.getLineHeight();
            if (text == null || text.isEmpty() || charIndex <= 0) {
                return new int[]{0, lineHeight};
            }
            int[] lineInfo = TextRenderer.findCharLine(text, charIndex);
            String[] lines = text.split("[\r\n]");
            String lineSubstr = (lineInfo[0] < lines.length) ? lines[lineInfo[0]].substring(0, lineInfo[1]) : "";
            int x = pfrFont.getStringWidth(lineSubstr);
            int y = lineInfo[0] * lineHeight + pfrFont.getAscent();
            return new int[]{x, y};
        }

        // Fallback: approximate using built-in font metrics
        int charWidth = builtinCharWidth(fontSize);
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);

        if (text == null || text.isEmpty() || charIndex <= 0) {
            return new int[]{0, lineHeight};
        }

        int[] lineInfo = TextRenderer.findCharLine(text, charIndex);
        int x = lineInfo[1] * charWidth;
        int y = lineInfo[0] * lineHeight + builtinAscent(fontSize);
        return new int[]{x, y};
    }

    /**
     * Resolve a BitmapFont using multi-strategy lookup.
     * 1. Exact font name
     * 2. Canonical/fuzzy match via FontRegistry.resolveFont()
     * 3. Last resort: first registered PFR font with fontSize - 1
     *
     * @return the resolved BitmapFont, or null if no PFR fonts are registered
     */
    private static BitmapFont resolveBitmapFont(String fontName, int fontSize) {
        if (fontName == null) return null;

        // 1. Try exact font name
        BitmapFont exact = FontRegistry.getBitmapFont(fontName, fontSize);
        if (exact != null) return exact;

        // 2. Try canonical/fuzzy match
        String resolved = FontRegistry.resolveFont(fontName);
        if (resolved != null) {
            BitmapFont font = FontRegistry.getBitmapFont(resolved, fontSize);
            if (font != null) return font;
        }

        // 3. Last resort: first registered font with size - 1
        // (system fonts are visually larger than pixel fonts at the same nominal size)
        String fallback = FontRegistry.getFirstRegisteredFont();
        if (fallback != null) {
            int fbSize = fontSize > 1 ? fontSize - 1 : fontSize;
            return FontRegistry.getBitmapFont(fallback, fbSize);
        }

        return null;
    }

    private Bitmap renderWithBitmapFont(BitmapFont font, String text, int width, int height,
                                         String alignment, int textColor, int bgColor,
                                         boolean wordWrap, int fixedLineSpace, int topSpacing,
                                         boolean syntheticBold, boolean underline) {
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : font.getLineHeight();

        String[] rawLines = text.split("[\r\n]+");
        if (rawLines.length == 0) rawLines = new String[]{""};

        List<String> lines = new ArrayList<>();
        if (wordWrap) {
            for (String rawLine : rawLines) {
                TextRenderer.wrapLine(rawLine, font::getStringWidth, width, lines);
            }
        } else {
            for (String rawLine : rawLines) {
                lines.add(rawLine);
            }
        }

        int neededHeight = lines.size() * lineHeight + topSpacing;
        if (neededHeight > height) height = neededHeight;

        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bgColor;

        int y = topSpacing;
        for (String line : lines) {
            if (y >= height) break;
            int x = 0;
            switch (alignment) {
                case "center" -> x = (width - font.getStringWidth(line)) / 2;
                case "right" -> x = width - font.getStringWidth(line);
            }
            int lineStartX = x;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                font.drawChar(ch, pixels, width, height, x, y, textColor);
                if (syntheticBold) {
                    font.drawChar(ch, pixels, width, height, x + 1, y, textColor);
                }
                x += font.getCharWidth(ch);
            }
            if (underline && line.length() > 0) {
                int ulY = y + font.getLineHeight() - 1;
                if (ulY >= 0 && ulY < height) {
                    for (int ux = Math.max(0, lineStartX); ux < Math.min(width, x); ux++) {
                        pixels[ulY * width + ux] = textColor;
                    }
                }
            }
            y += lineHeight;
        }

        return new Bitmap(width, height, 32, pixels);
    }

    /**
     * Render text using the built-in 5x7 pixel font.
     * Used as fallback when PFR/TTF fonts are not yet loaded.
     */
    private Bitmap renderWithBuiltinFont(String text, int width, int height, int fontSize,
                                          String alignment, int textColor, int bgColor,
                                          boolean wordWrap, int fixedLineSpace, int topSpacing,
                                          boolean underline) {
        int charW = builtinCharWidth(fontSize);
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);
        int ascent = builtinAscent(fontSize);
        int scale = builtinScale(fontSize);

        String[] rawLines = text.split("[\r\n]+");
        if (rawLines.length == 0) rawLines = new String[]{""};

        List<String> lines = new ArrayList<>();
        if (wordWrap) {
            for (String rawLine : rawLines) {
                TextRenderer.wrapLine(rawLine, s -> s.length() * charW, width, lines);
            }
        } else {
            for (String rawLine : rawLines) {
                lines.add(rawLine);
            }
        }

        int neededHeight = lines.size() * lineHeight + topSpacing;
        if (neededHeight > height) height = neededHeight;

        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = bgColor;

        int y = topSpacing;
        for (String line : lines) {
            if (y >= height) break;
            int x = 0;
            switch (alignment) {
                case "center" -> x = (width - line.length() * charW) / 2;
                case "right" -> x = width - line.length() * charW;
            }
            int lineStartX = x;
            for (int i = 0; i < line.length(); i++) {
                drawBuiltinChar(line.charAt(i), pixels, width, height, x, y + ascent, scale, textColor);
                x += charW;
            }
            if (underline && line.length() > 0) {
                int ulY = y + ascent + 1;
                if (ulY >= 0 && ulY < height) {
                    for (int ux = Math.max(0, lineStartX); ux < Math.min(width, x); ux++) {
                        pixels[ulY * width + ux] = textColor;
                    }
                }
            }
            y += lineHeight;
        }

        return new Bitmap(width, height, 32, pixels);
    }

    // --- Built-in font metrics ---

    private static int builtinScale(int fontSize) {
        return Math.max(1, fontSize / 8);
    }

    private static int builtinCharWidth(int fontSize) {
        int scale = builtinScale(fontSize);
        return 6 * scale; // 5px char + 1px spacing
    }

    private static int builtinLineHeight(int fontSize) {
        int scale = builtinScale(fontSize);
        return 9 * scale; // 7px char + 2px spacing
    }

    private static int builtinAscent(int fontSize) {
        int scale = builtinScale(fontSize);
        return 7 * scale;
    }

    // --- Built-in 5x7 pixel font ---

    /**
     * Draw a character using the built-in 5x7 pixel font.
     * Each character is encoded as 5 bytes (columns), each byte has 7 bits (rows).
     */
    private static void drawBuiltinChar(char ch, int[] pixels, int imgW, int imgH,
                                         int x, int baselineY, int scale, int color) {
        int idx = ch - 32;
        if (idx < 0 || idx >= FONT_5X7.length) idx = 0; // space for unknown

        byte[] glyph = FONT_5X7[idx];
        int topY = baselineY - 7 * scale;

        for (int col = 0; col < 5; col++) {
            int bits = glyph[col] & 0xFF;
            for (int row = 0; row < 7; row++) {
                if ((bits & (1 << row)) != 0) {
                    // Draw scaled pixel
                    for (int sy = 0; sy < scale; sy++) {
                        for (int sx = 0; sx < scale; sx++) {
                            int px = x + col * scale + sx;
                            int py = topY + row * scale + sy;
                            if (px >= 0 && px < imgW && py >= 0 && py < imgH) {
                                pixels[py * imgW + px] = color;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 5x7 pixel font data for ASCII 32-126.
     * Each character is 5 bytes (columns left to right).
     * Each byte: bit 0 = top row, bit 6 = bottom row.
     */
    private static final byte[][] FONT_5X7 = {
        {0x00, 0x00, 0x00, 0x00, 0x00}, // 32 space
        {0x00, 0x00, 0x5F, 0x00, 0x00}, // 33 !
        {0x00, 0x07, 0x00, 0x07, 0x00}, // 34 "
        {0x14, 0x7F, 0x14, 0x7F, 0x14}, // 35 #
        {0x24, 0x2A, 0x7F, 0x2A, 0x12}, // 36 $
        {0x23, 0x13, 0x08, 0x64, 0x62}, // 37 %
        {0x36, 0x49, 0x55, 0x22, 0x50}, // 38 &
        {0x00, 0x05, 0x03, 0x00, 0x00}, // 39 '
        {0x00, 0x1C, 0x22, 0x41, 0x00}, // 40 (
        {0x00, 0x41, 0x22, 0x1C, 0x00}, // 41 )
        {0x14, 0x08, 0x3E, 0x08, 0x14}, // 42 *
        {0x08, 0x08, 0x3E, 0x08, 0x08}, // 43 +
        {0x00, 0x50, 0x30, 0x00, 0x00}, // 44 ,
        {0x08, 0x08, 0x08, 0x08, 0x08}, // 45 -
        {0x00, 0x60, 0x60, 0x00, 0x00}, // 46 .
        {0x20, 0x10, 0x08, 0x04, 0x02}, // 47 /
        {0x3E, 0x51, 0x49, 0x45, 0x3E}, // 48 0
        {0x00, 0x42, 0x7F, 0x40, 0x00}, // 49 1
        {0x42, 0x61, 0x51, 0x49, 0x46}, // 50 2
        {0x21, 0x41, 0x45, 0x4B, 0x31}, // 51 3
        {0x18, 0x14, 0x12, 0x7F, 0x10}, // 52 4
        {0x27, 0x45, 0x45, 0x45, 0x39}, // 53 5
        {0x3C, 0x4A, 0x49, 0x49, 0x30}, // 54 6
        {0x01, 0x71, 0x09, 0x05, 0x03}, // 55 7
        {0x36, 0x49, 0x49, 0x49, 0x36}, // 56 8
        {0x06, 0x49, 0x49, 0x29, 0x1E}, // 57 9
        {0x00, 0x36, 0x36, 0x00, 0x00}, // 58 :
        {0x00, 0x56, 0x36, 0x00, 0x00}, // 59 ;
        {0x08, 0x14, 0x22, 0x41, 0x00}, // 60 <
        {0x14, 0x14, 0x14, 0x14, 0x14}, // 61 =
        {0x00, 0x41, 0x22, 0x14, 0x08}, // 62 >
        {0x02, 0x01, 0x51, 0x09, 0x06}, // 63 ?
        {0x32, 0x49, 0x79, 0x41, 0x3E}, // 64 @
        {0x7E, 0x11, 0x11, 0x11, 0x7E}, // 65 A
        {0x7F, 0x49, 0x49, 0x49, 0x36}, // 66 B
        {0x3E, 0x41, 0x41, 0x41, 0x22}, // 67 C
        {0x7F, 0x41, 0x41, 0x22, 0x1C}, // 68 D
        {0x7F, 0x49, 0x49, 0x49, 0x41}, // 69 E
        {0x7F, 0x09, 0x09, 0x09, 0x01}, // 70 F
        {0x3E, 0x41, 0x49, 0x49, 0x7A}, // 71 G
        {0x7F, 0x08, 0x08, 0x08, 0x7F}, // 72 H
        {0x00, 0x41, 0x7F, 0x41, 0x00}, // 73 I
        {0x20, 0x40, 0x41, 0x3F, 0x01}, // 74 J
        {0x7F, 0x08, 0x14, 0x22, 0x41}, // 75 K
        {0x7F, 0x40, 0x40, 0x40, 0x40}, // 76 L
        {0x7F, 0x02, 0x0C, 0x02, 0x7F}, // 77 M
        {0x7F, 0x04, 0x08, 0x10, 0x7F}, // 78 N
        {0x3E, 0x41, 0x41, 0x41, 0x3E}, // 79 O
        {0x7F, 0x09, 0x09, 0x09, 0x06}, // 80 P
        {0x3E, 0x41, 0x51, 0x21, 0x5E}, // 81 Q
        {0x7F, 0x09, 0x19, 0x29, 0x46}, // 82 R
        {0x46, 0x49, 0x49, 0x49, 0x31}, // 83 S
        {0x01, 0x01, 0x7F, 0x01, 0x01}, // 84 T
        {0x3F, 0x40, 0x40, 0x40, 0x3F}, // 85 U
        {0x1F, 0x20, 0x40, 0x20, 0x1F}, // 86 V
        {0x3F, 0x40, 0x38, 0x40, 0x3F}, // 87 W
        {0x63, 0x14, 0x08, 0x14, 0x63}, // 88 X
        {0x07, 0x08, 0x70, 0x08, 0x07}, // 89 Y
        {0x61, 0x51, 0x49, 0x45, 0x43}, // 90 Z
        {0x00, 0x7F, 0x41, 0x41, 0x00}, // 91 [
        {0x02, 0x04, 0x08, 0x10, 0x20}, // 92 backslash
        {0x00, 0x41, 0x41, 0x7F, 0x00}, // 93 ]
        {0x04, 0x02, 0x01, 0x02, 0x04}, // 94 ^
        {0x40, 0x40, 0x40, 0x40, 0x40}, // 95 _
        {0x00, 0x01, 0x02, 0x04, 0x00}, // 96 `
        {0x20, 0x54, 0x54, 0x54, 0x78}, // 97 a
        {0x7F, 0x48, 0x44, 0x44, 0x38}, // 98 b
        {0x38, 0x44, 0x44, 0x44, 0x20}, // 99 c
        {0x38, 0x44, 0x44, 0x48, 0x7F}, // 100 d
        {0x38, 0x54, 0x54, 0x54, 0x18}, // 101 e
        {0x08, 0x7E, 0x09, 0x01, 0x02}, // 102 f
        {0x0C, 0x52, 0x52, 0x52, 0x3E}, // 103 g
        {0x7F, 0x08, 0x04, 0x04, 0x78}, // 104 h
        {0x00, 0x44, 0x7D, 0x40, 0x00}, // 105 i
        {0x20, 0x40, 0x44, 0x3D, 0x00}, // 106 j
        {0x7F, 0x10, 0x28, 0x44, 0x00}, // 107 k
        {0x00, 0x41, 0x7F, 0x40, 0x00}, // 108 l
        {0x7C, 0x04, 0x18, 0x04, 0x78}, // 109 m
        {0x7C, 0x08, 0x04, 0x04, 0x78}, // 110 n
        {0x38, 0x44, 0x44, 0x44, 0x38}, // 111 o
        {0x7C, 0x14, 0x14, 0x14, 0x08}, // 112 p
        {0x08, 0x14, 0x14, 0x18, 0x7C}, // 113 q
        {0x7C, 0x08, 0x04, 0x04, 0x08}, // 114 r
        {0x48, 0x54, 0x54, 0x54, 0x20}, // 115 s
        {0x04, 0x3F, 0x44, 0x40, 0x20}, // 116 t
        {0x3C, 0x40, 0x40, 0x20, 0x7C}, // 117 u
        {0x1C, 0x20, 0x40, 0x20, 0x1C}, // 118 v
        {0x3C, 0x40, 0x30, 0x40, 0x3C}, // 119 w
        {0x44, 0x28, 0x10, 0x28, 0x44}, // 120 x
        {0x0C, 0x50, 0x50, 0x50, 0x3C}, // 121 y
        {0x44, 0x64, 0x54, 0x4C, 0x44}, // 122 z
        {0x00, 0x08, 0x36, 0x41, 0x00}, // 123 {
        {0x00, 0x00, 0x7F, 0x00, 0x00}, // 124 |
        {0x00, 0x41, 0x36, 0x08, 0x00}, // 125 }
        {0x10, 0x08, 0x08, 0x10, 0x08}, // 126 ~
    };

}
