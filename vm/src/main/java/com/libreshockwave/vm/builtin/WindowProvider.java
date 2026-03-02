package com.libreshockwave.vm.builtin;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.vm.Datum;

import java.util.Map;

/**
 * Interface for Director window management.
 * Implemented by Player in player-core to provide window creation/access.
 *
 * Director windows are used by Lingo scripts for off-screen rendering buffers,
 * such as the loading bar which draws to a window buffer.
 */
public interface WindowProvider {

    /**
     * Create a new window with the given ID and template.
     * @param windowId The window identifier
     * @param template The window template name (e.g., "system.window")
     */
    void createWindow(String windowId, String template);

    /**
     * Get a window reference by ID.
     * @param windowId The window identifier
     * @return A WindowRef datum, or VOID if not found
     */
    Datum getWindow(String windowId);

    /**
     * Remove a window by ID.
     * @param windowId The window identifier
     */
    void removeWindow(String windowId);

    /**
     * Resize a window's bitmap buffer.
     * @param windowId The window identifier
     * @param width New width
     * @param height New height
     */
    void resizeWindow(String windowId, int width, int height);

    /**
     * Center a window on the stage.
     * @param windowId The window identifier
     */
    void centerWindow(String windowId);

    /**
     * Get the bitmap buffer for a window.
     * @param windowId The window identifier
     * @return The window's bitmap buffer, or null if not found
     */
    Bitmap getWindowBitmap(String windowId);

    /**
     * Get all active window buffers and their positions for rendering.
     * @return Map of window ID to WindowBuffer (bitmap + position)
     */
    Map<String, WindowBuffer> getWindowBuffers();

    /**
     * Window buffer data for rendering.
     */
    record WindowBuffer(Bitmap bitmap, int x, int y) {}

    // Thread-local provider for VM access
    ThreadLocal<WindowProvider> CURRENT = new ThreadLocal<>();

    static void setProvider(WindowProvider provider) {
        CURRENT.set(provider);
    }

    static void clearProvider() {
        CURRENT.remove();
    }

    static WindowProvider getProvider() {
        return CURRENT.get();
    }
}
