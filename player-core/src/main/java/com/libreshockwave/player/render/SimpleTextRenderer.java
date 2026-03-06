package com.libreshockwave.player.render;

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

        // Check for PFR bitmap font — resolve bold/italic variants
        String style = fontStyle != null ? fontStyle.toLowerCase() : "";
        boolean wantBold = style.contains("bold");
        BitmapFont[] resolved = {null};
        boolean foundBoldVariant = resolveBitmapFont(fontName, fontSize, fontStyle, resolved);
        BitmapFont pfrFont = resolved[0];
        if (pfrFont != null) {
            // Synthetic bold only when bold requested but no dedicated bold font variant exists
            boolean syntheticBold = wantBold && !foundBoldVariant;
            return renderWithBitmapFont(pfrFont, text, width, height,
                    alignment, textColor, bgColor, wordWrap,
                    fixedLineSpace, topSpacing, syntheticBold);
        }

        // Fallback: render with built-in pixel font
        return renderWithBuiltinFont(text, width, height, fontSize,
                alignment, textColor, bgColor, wordWrap,
                fixedLineSpace, topSpacing);
    }

    @Override
    public int[] charPosToLoc(String text, int charIndex,
                              String fontName, int fontSize, String fontStyle,
                              int fixedLineSpace) {
        // Check PFR bitmap font first — resolve bold/italic variants
        BitmapFont[] resolved = {null};
        resolveBitmapFont(fontName, fontSize, fontStyle, resolved);
        BitmapFont pfrFont = resolved[0];
        if (pfrFont != null) {
            int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : pfrFont.getLineHeight();
            if (text == null || text.isEmpty() || charIndex <= 0) {
                return new int[]{0, lineHeight};
            }
            int idx = Math.min(charIndex, text.length());
            String[] lines = text.split("[\r\n]");
            int lineNum = 0, charsSoFar = 0;
            String lineText = lines.length > 0 ? lines[0] : "";
            for (int i = 0; i < lines.length; i++) {
                int lineLen = lines[i].length() + 1;
                if (charsSoFar + lineLen >= idx) {
                    lineNum = i; lineText = lines[i]; break;
                }
                charsSoFar += lineLen;
            }
            int charsOnLine = Math.min(idx - charsSoFar, lineText.length());
            int x = pfrFont.getStringWidth(lineText.substring(0, charsOnLine));
            int y = lineNum * lineHeight + pfrFont.getAscent();
            return new int[]{x, y};
        }

        // Fallback: approximate using built-in font metrics
        int charWidth = builtinCharWidth(fontSize);
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);

        if (text == null || text.isEmpty() || charIndex <= 0) {
            return new int[]{0, lineHeight};
        }

        String[] lines = text.split("[\r\n]");
        int charsSoFar = 0;
        int lineNum = 0;
        String lineText = lines.length > 0 ? lines[0] : "";
        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1;
            if (charsSoFar + lineLen >= charIndex) {
                lineNum = i;
                lineText = lines[i];
                break;
            }
            charsSoFar += lineLen;
        }

        int charsOnLine = Math.min(charIndex - charsSoFar, lineText.length());
        int x = charsOnLine * charWidth;
        int y = lineNum * lineHeight + builtinAscent(fontSize);
        return new int[]{x, y};
    }

    /**
     * Resolve a BitmapFont, applying bold/italic style variants.
     * In Director, bold variants use suffix "b" (e.g. "v" → "vb"),
     * italic uses "i", bold-italic uses "bi".
     * When fontStyle requests bold/italic, try suffixed names FIRST
     * to prefer dedicated bold/italic font variants over the base font.
     *
     * @param out single-element array to receive the resolved BitmapFont (or null)
     * @return true if a dedicated bold/italic variant font was found
     */
    private static boolean resolveBitmapFont(String fontName, int fontSize, String fontStyle, BitmapFont[] out) {
        if (fontName == null) { out[0] = null; return false; }
        String style = fontStyle != null ? fontStyle.toLowerCase() : "";
        boolean wantBold = style.contains("bold");
        boolean wantItalic = style.contains("italic");

        // When style is requested, try suffixed names FIRST
        if (wantBold && wantItalic) {
            BitmapFont font = FontRegistry.getBitmapFont(fontName + "bi", fontSize);
            if (font != null) { out[0] = font; return true; }
        }
        if (wantBold) {
            BitmapFont font = FontRegistry.getBitmapFont(fontName + "b", fontSize);
            if (font != null) { out[0] = font; return true; }
        }
        if (wantItalic) {
            BitmapFont font = FontRegistry.getBitmapFont(fontName + "i", fontSize);
            if (font != null) { out[0] = font; return true; }
        }

        // Try exact font name
        BitmapFont exact = FontRegistry.getBitmapFont(fontName, fontSize);
        if (exact != null) { out[0] = exact; return false; }

        // Font not found as PFR — fall back to default PFR font with style variant.
        // Window layouts may hardcode system font names (e.g. "Verdana" size 10) that aren't
        // available in WASM. Use the default registered PFR font instead.
        // Reduce size by 1 because system fonts (Verdana 10) are visually larger than
        // pixel fonts (Volter 9) at the same nominal size.
        String fallback = FontRegistry.getDefaultFontName();
        if (fallback != null) {
            int fbSize = fontSize > 1 ? fontSize - 1 : fontSize;
            if (wantBold && wantItalic) {
                BitmapFont font = FontRegistry.getBitmapFont(fallback + "bi", fbSize);
                if (font != null) { out[0] = font; return true; }
            }
            if (wantBold) {
                BitmapFont font = FontRegistry.getBitmapFont(fallback + "b", fbSize);
                if (font != null) { out[0] = font; return true; }
            }
            if (wantItalic) {
                BitmapFont font = FontRegistry.getBitmapFont(fallback + "i", fbSize);
                if (font != null) { out[0] = font; return true; }
            }
            out[0] = FontRegistry.getBitmapFont(fallback, fbSize);
        }
        return false;
    }

    private Bitmap renderWithBitmapFont(BitmapFont font, String text, int width, int height,
                                         String alignment, int textColor, int bgColor,
                                         boolean wordWrap, int fixedLineSpace, int topSpacing,
                                         boolean syntheticBold) {
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : font.getLineHeight();

        String[] rawLines = text.split("[\r\n]+");
        if (rawLines.length == 0) rawLines = new String[]{""};

        List<String> lines = new ArrayList<>();
        if (wordWrap) {
            for (String rawLine : rawLines) {
                wrapLinePfr(rawLine, font, width, lines);
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
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                font.drawChar(ch, pixels, width, height, x, y, textColor);
                if (syntheticBold) {
                    font.drawChar(ch, pixels, width, height, x + 1, y, textColor);
                }
                x += font.getCharWidth(ch);
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
                                          boolean wordWrap, int fixedLineSpace, int topSpacing) {
        int charW = builtinCharWidth(fontSize);
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);
        int ascent = builtinAscent(fontSize);
        int scale = builtinScale(fontSize);

        String[] rawLines = text.split("[\r\n]+");
        if (rawLines.length == 0) rawLines = new String[]{""};

        List<String> lines = new ArrayList<>();
        if (wordWrap) {
            for (String rawLine : rawLines) {
                wrapLineBuiltin(rawLine, charW, width, lines);
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
            for (int i = 0; i < line.length(); i++) {
                drawBuiltinChar(line.charAt(i), pixels, width, height, x, y + ascent, scale, textColor);
                x += charW;
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

    // --- Word wrap helpers ---

    private static void wrapLinePfr(String text, BitmapFont font, int maxWidth, List<String> out) {
        if (text.isEmpty()) { out.add(""); return; }
        if (font.getStringWidth(text) <= maxWidth) { out.add(text); return; }
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else {
                String candidate = current + " " + word;
                if (font.getStringWidth(candidate) <= maxWidth) {
                    current.append(" ").append(word);
                } else {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
        }
        if (current.length() > 0) out.add(current.toString());
    }

    private static void wrapLineBuiltin(String text, int charWidth, int maxWidth, List<String> out) {
        if (text.isEmpty()) { out.add(""); return; }
        int maxChars = Math.max(1, maxWidth / charWidth);
        if (text.length() <= maxChars) { out.add(text); return; }
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else {
                if (current.length() + 1 + word.length() <= maxChars) {
                    current.append(" ").append(word);
                } else {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
        }
        if (current.length() > 0) out.add(current.toString());
    }
}
