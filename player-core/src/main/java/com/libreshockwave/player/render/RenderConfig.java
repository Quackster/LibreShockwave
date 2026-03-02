package com.libreshockwave.player.render;

/**
 * Global rendering configuration for LibreShockwave.
 * <p>
 * Controls anti-aliasing and interpolation behavior across all renderers
 * (Swing StagePanel, AWT text renderer, test harnesses, etc.).
 * <p>
 * Anti-aliasing is disabled by default to match Director's pixel-exact
 * rendering style. When enabled, the ink processor's graduated alpha
 * handles fringe artifacts from anti-aliased text on color-keyed backgrounds.
 */
public final class RenderConfig {

    private static boolean antialias = false;

    private RenderConfig() {}

    /** Returns true if anti-aliasing / smooth rendering is enabled globally. */
    public static boolean isAntialias() {
        return antialias;
    }

    /** Sets the global anti-aliasing flag. Affects text rendering, stage compositing, and tests. */
    public static void setAntialias(boolean enabled) {
        antialias = enabled;
    }
}
