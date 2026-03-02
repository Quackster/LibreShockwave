package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;

import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * AWT-based text renderer for desktop environments.
 * Uses Graphics2D for proper text layout, font resolution, and anti-aliased rendering.
 */
public class AwtTextRenderer implements TextRenderer {

    /** Cache of available system font names (lowercase -> actual name) */
    private static volatile Map<String, String> systemFontCache;

    @Override
    public Bitmap renderText(String text, int width, int height,
                             String fontName, int fontSize, String fontStyle,
                             String alignment, int textColor, int bgColor,
                             boolean wordWrap, boolean antialias,
                             int fixedLineSpace, int topSpacing) {
        if (text == null) text = "";
        if (width <= 0) width = 200;
        if (height <= 0) height = 20;

        // Create a temporary BufferedImage to render text with AWT
        BufferedImage bufImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = bufImg.createGraphics();

        // Set rendering hints
        if (antialias) {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Fill background
        g2d.setColor(new Color(bgColor, true));
        g2d.fillRect(0, 0, width, height);

        // Set font
        int fontStyleAwt = Font.PLAIN;
        String style = fontStyle.toLowerCase();
        if (style.contains("bold")) fontStyleAwt |= Font.BOLD;
        if (style.contains("italic")) fontStyleAwt |= Font.ITALIC;
        Font font = resolveDirectorFont(fontName, fontStyleAwt, fontSize);
        if (style.contains("underline")) {
            Map<TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
            font = font.deriveFont(attrs);
        }
        g2d.setFont(font);

        // Set text color
        g2d.setColor(new Color(textColor, true));

        FontMetrics fm = g2d.getFontMetrics();
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : fm.getHeight();
        int ascent = fm.getAscent();

        // Split text into lines
        String[] rawLines = text.split("[\r\n]+");
        if (rawLines.length == 0) rawLines = new String[]{""};

        // Word wrap if enabled
        List<String> lines = new ArrayList<>();
        if (wordWrap) {
            for (String rawLine : rawLines) {
                wrapLine(rawLine, fm, width, lines);
            }
        } else {
            for (String rawLine : rawLines) {
                lines.add(rawLine);
            }
        }

        // Compute needed height
        int neededHeight = lines.size() * lineHeight + topSpacing;
        if (neededHeight > height) {
            g2d.dispose();
            bufImg = new BufferedImage(width, neededHeight, BufferedImage.TYPE_INT_ARGB);
            g2d = bufImg.createGraphics();
            if (antialias) {
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            g2d.setColor(new Color(bgColor, true));
            g2d.fillRect(0, 0, width, neededHeight);
            g2d.setFont(font);
            g2d.setColor(new Color(textColor, true));
            height = neededHeight;
        }

        // Draw lines
        int y = ascent + topSpacing;
        for (String line : lines) {
            if (y > height) break;

            int x = 0;
            switch (alignment) {
                case "center" -> x = (width - fm.stringWidth(line)) / 2;
                case "right" -> x = width - fm.stringWidth(line);
                default -> x = 0; // left
            }

            g2d.drawString(line, x, y);
            y += lineHeight;
        }

        g2d.dispose();

        // Convert BufferedImage to our Bitmap format
        int[] pixels = bufImg.getRGB(0, 0, bufImg.getWidth(), bufImg.getHeight(),
                null, 0, bufImg.getWidth());
        return new Bitmap(bufImg.getWidth(), bufImg.getHeight(), 32, pixels);
    }

    @Override
    public int[] charPosToLoc(String text, int charIndex,
                              String fontName, int fontSize, String fontStyle,
                              int fixedLineSpace) {
        if (text == null || text.isEmpty() || charIndex <= 0) {
            return new int[]{0, 0};
        }

        int fontStyleAwt = Font.PLAIN;
        String style = fontStyle.toLowerCase();
        if (style.contains("bold")) fontStyleAwt |= Font.BOLD;
        if (style.contains("italic")) fontStyleAwt |= Font.ITALIC;
        Font font = resolveDirectorFont(fontName, fontStyleAwt, fontSize);

        BufferedImage tmpImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = tmpImg.createGraphics();
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();

        int idx = Math.min(charIndex, text.length());

        // Find which line the character is on
        String[] lines = text.split("[\r\n]");
        int lineNum = 0;
        int charsSoFar = 0;
        String lineText = lines.length > 0 ? lines[0] : "";
        for (int i = 0; i < lines.length; i++) {
            int lineLen = lines[i].length() + 1; // +1 for line break
            if (charsSoFar + lineLen >= idx) {
                lineNum = i;
                lineText = lines[i];
                break;
            }
            charsSoFar += lineLen;
        }

        int charsOnLine = idx - charsSoFar;
        if (charsOnLine > lineText.length()) charsOnLine = lineText.length();
        String lineSubstr = lineText.substring(0, charsOnLine);
        int x = fm.stringWidth(lineSubstr);
        int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : fm.getHeight();
        int y = lineNum * lineHeight + fm.getAscent();

        g2d.dispose();
        return new int[]{x, y};
    }

    // --- Font resolution (extracted from CastMember) ---

    private static Font resolveDirectorFont(String directorName, int style, int size) {
        if (directorName == null || directorName.isEmpty()) {
            return new Font("SansSerif", style, size);
        }

        String resolved = resolveDirectorFontName(directorName);

        int extraStyle = extractFontStyle(directorName);
        if (extraStyle != Font.PLAIN) {
            style |= extraStyle;
        }

        return new Font(resolved, style, size);
    }

    private static Map<String, String> getSystemFontCache() {
        if (systemFontCache == null) {
            synchronized (AwtTextRenderer.class) {
                if (systemFontCache == null) {
                    Map<String, String> cache = new HashMap<>();
                    for (String fontName : GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames()) {
                        cache.put(fontName.toLowerCase(), fontName);
                    }
                    systemFontCache = cache;
                }
            }
        }
        return systemFontCache;
    }

    private static String resolveDirectorFontName(String name) {
        var cache = getSystemFontCache();

        // 1. Exact match (case-insensitive)
        String exact = cache.get(name.toLowerCase());
        if (exact != null) return exact;

        // 2. Strip trailing style indicators (b=bold, i=italic, bi=bold-italic)
        String baseName = name.replaceAll("(?i)[bi]+$", "");
        if (!baseName.isEmpty() && !baseName.equals(name)) {
            exact = cache.get(baseName.toLowerCase());
            if (exact != null) return exact;
        }

        // 3. Try prefix matching for very short names
        if (name.length() <= 3) {
            String prefix = baseName.isEmpty() ? name.toLowerCase() : baseName.toLowerCase();
            String[] commonFonts = {
                "Verdana", "Arial", "Tahoma", "Times New Roman", "Courier New",
                "Georgia", "Helvetica", "Trebuchet MS", "Comic Sans MS"
            };
            for (String candidate : commonFonts) {
                if (candidate.toLowerCase().startsWith(prefix)) {
                    String resolved = cache.get(candidate.toLowerCase());
                    if (resolved != null) return resolved;
                }
            }
            for (var entry : cache.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    return entry.getValue();
                }
            }
        }

        return "SansSerif";
    }

    private static int extractFontStyle(String name) {
        if (name.length() <= 1) return Font.PLAIN;

        String baseName = name.replaceAll("(?i)[bi]+$", "");
        if (baseName.length() == name.length()) return Font.PLAIN;

        String suffix = name.substring(baseName.length()).toLowerCase();
        int style = Font.PLAIN;
        if (suffix.contains("b")) style |= Font.BOLD;
        if (suffix.contains("i")) style |= Font.ITALIC;
        return style;
    }

    private static void wrapLine(String text, FontMetrics fm, int maxWidth, List<String> out) {
        if (text.isEmpty()) {
            out.add("");
            return;
        }
        if (fm.stringWidth(text) <= maxWidth) {
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
                if (fm.stringWidth(candidate) <= maxWidth) {
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
