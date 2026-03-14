package com.libreshockwave.editor.score;

import java.awt.*;

/**
 * Maps Director cast member types to cell colors, matching Director MX 2004's Score appearance.
 */
public class ScoreColors {

    // Director MX 2004 Score cell colors by member type
    public static final Color BITMAP = new Color(153, 204, 255);     // Light blue
    public static final Color TEXT = new Color(255, 255, 153);       // Light yellow
    public static final Color SHAPE = new Color(153, 255, 153);     // Light green
    public static final Color SCRIPT = new Color(204, 153, 255);    // Light purple
    public static final Color SOUND = new Color(255, 204, 153);     // Light orange
    public static final Color FILM_LOOP = new Color(153, 255, 255); // Light cyan
    public static final Color BUTTON = new Color(255, 204, 204);    // Light pink
    public static final Color FONT = new Color(204, 204, 204);      // Light gray
    public static final Color PALETTE = new Color(204, 255, 204);   // Pale green
    public static final Color FIELD = new Color(255, 255, 204);     // Pale yellow
    public static final Color TRANSITION = new Color(204, 204, 255); // Pale blue
    public static final Color XTRA = new Color(255, 153, 153);      // Salmon
    public static final Color UNKNOWN = new Color(200, 200, 200);   // Gray

    public static final Color EMPTY = null;
    public static final Color SPAN_CONTINUATION = new Color(230, 230, 230); // Light gray for span

    /**
     * Get the color for a member type name.
     */
    public static Color getColor(String memberType) {
        if (memberType == null) return EMPTY;
        return switch (memberType.toUpperCase()) {
            case "BITMAP" -> BITMAP;
            case "TEXT" -> TEXT;
            case "SHAPE" -> SHAPE;
            case "SCRIPT" -> SCRIPT;
            case "SOUND" -> SOUND;
            case "FILM_LOOP", "FILMLOOP" -> FILM_LOOP;
            case "BUTTON" -> BUTTON;
            case "FONT" -> FONT;
            case "PALETTE" -> PALETTE;
            case "FIELD" -> FIELD;
            case "TRANSITION" -> TRANSITION;
            case "XTRA" -> XTRA;
            default -> UNKNOWN;
        };
    }
}
