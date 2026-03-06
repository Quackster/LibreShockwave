package com.libreshockwave.player.cast;

import com.libreshockwave.font.BitmapFont;
import com.libreshockwave.font.Pfr1Font;
import com.libreshockwave.font.Pfr1TtfConverter;
import com.libreshockwave.font.TtfBitmapRasterizer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping font names to PFR1 bitmap font data.
 * When cast libraries load, OLE members with XMED/PFR1 chunks
 * are parsed and registered here. Text renderers check this
 * registry first before falling back to system fonts.
 */
public class FontRegistry {

    /** Font name (lowercase) -> parsed PFR1 font */
    private static final ConcurrentHashMap<String, Pfr1Font> parsedFonts = new ConcurrentHashMap<>();

    /** Font name (lowercase) -> TTF byte array (for AWT Font.createFont) */
    private static final ConcurrentHashMap<String, byte[]> ttfCache = new ConcurrentHashMap<>();

    /** Cache: "fontName:size" -> rasterized BitmapFont (for SimpleTextRenderer) */
    private static final ConcurrentHashMap<String, BitmapFont> rasterizedCache = new ConcurrentHashMap<>();

    /** Shortest registered font name — used as default fallback for system fonts */
    private static volatile String defaultFontName;

    /**
     * Register a PFR1 font parsed from XMED chunk data.
     * @param memberName the cast member name (e.g. "v", "vb")
     * @param pfrData    raw PFR1 data from XMED chunk
     */
    public static void registerPfr1Font(String memberName, byte[] pfrData) {
        if (memberName == null || pfrData == null) return;

        Pfr1Font font = Pfr1Font.parse(pfrData);
        if (font == null) return;

        String key = memberName.toLowerCase();
        parsedFonts.put(key, font);

        // Track shortest name as default fallback
        String cur = defaultFontName;
        if (cur == null || key.length() < cur.length()) {
            defaultFontName = key;
        }

        // Convert to TTF bytes for AWT rendering
        try {
            String ttfName = font.fontName.isEmpty() ? memberName : font.fontName;
            byte[] ttfBytes = Pfr1TtfConverter.convert(font, ttfName);
            ttfCache.put(key, ttfBytes);
            if (!font.fontName.isEmpty() && !font.fontName.equalsIgnoreCase(memberName)) {
                ttfCache.put(font.fontName.toLowerCase(), ttfBytes);
            }
        } catch (Exception e) {
            // TTF conversion failed — BitmapFont fallback still available
        }

        // Also register under the internal font ID if different
        if (!font.fontName.isEmpty() && !font.fontName.equalsIgnoreCase(memberName)) {
            parsedFonts.put(font.fontName.toLowerCase(), font);
        }
    }

    /**
     * Get TTF byte array for the given font name.
     * Returns null if no PFR font is registered or TTF conversion failed.
     */
    public static byte[] getTtfBytes(String fontName) {
        if (fontName == null) return null;
        return ttfCache.get(fontName.toLowerCase());
    }

    /**
     * Get a rasterized bitmap font for the given name and size.
     * Returns null if no PFR font is registered for this name.
     * Used by SimpleTextRenderer where AWT may not be available.
     *
     * On desktop (AWT available): renders glyphs using AWT Graphics2D for pixel-perfect
     * match with AwtTextRenderer. On WASM: falls back to TtfBitmapRasterizer.
     */
    public static BitmapFont getBitmapFont(String fontName, int fontSize) {
        if (fontName == null) return null;

        String key = fontName.toLowerCase();

        // Check rasterized cache
        String cacheKey = key + ":" + fontSize;
        BitmapFont cached = rasterizedCache.get(cacheKey);
        if (cached != null) return cached;

        // TTF rasterizer (pure Java — works on both desktop and WASM)
        byte[] ttfBytes = ttfCache.get(key);
        if (ttfBytes != null) {
            BitmapFont rasterized = TtfBitmapRasterizer.rasterize(ttfBytes, fontSize, fontName);
            if (rasterized != null) {
                rasterizedCache.put(cacheKey, rasterized);
                return rasterized;
            }
        }

        // Last resort: PFR1 direct rasterization
        Pfr1Font parsed = parsedFonts.get(key);
        if (parsed == null) return null;
        BitmapFont rasterized = BitmapFont.fromPfr1(parsed, fontSize);
        if (rasterized != null) rasterizedCache.put(cacheKey, rasterized);
        return rasterized;
    }

    /**
     * Get the default (shortest-named) registered PFR font name.
     * Used as fallback when a requested system font (e.g. "Verdana") isn't available as PFR.
     */
    public static String getDefaultFontName() {
        return defaultFontName;
    }

    /**
     * Check if a font name is registered as a PFR bitmap font.
     */
    public static boolean hasPfrFont(String fontName) {
        if (fontName == null) return false;
        return parsedFonts.containsKey(fontName.toLowerCase());
    }

    /**
     * Clear all registered fonts (used on reset/reload).
     */
    public static void clear() {
        parsedFonts.clear();
        ttfCache.clear();
        rasterizedCache.clear();
        defaultFontName = null;
    }
}
