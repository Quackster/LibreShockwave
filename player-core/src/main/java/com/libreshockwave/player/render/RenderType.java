package com.libreshockwave.player.render;

/**
 * Rendering backend for frame compositing.
 */
public enum RenderType {
    /** AWT Graphics2D rendering (Swing player, headless simulator). */
    AWT,
    /** Pure int[] software compositing (WASM, no AWT dependency). */
    SOFTWARE
}
