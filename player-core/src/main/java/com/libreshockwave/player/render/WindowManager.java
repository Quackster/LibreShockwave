package com.libreshockwave.player.render;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.WindowProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Director windows with bitmap buffers.
 * Used by Lingo scripts (e.g., Loading Bar Class) for off-screen rendering.
 *
 * Each window has a bitmap buffer that scripts can draw on via image.fill()/draw().
 * The buffers are composited onto the stage during rendering.
 */
public class WindowManager implements WindowProvider {

    private static final int DEFAULT_WIDTH = 128;
    private static final int DEFAULT_HEIGHT = 128;

    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();
    private int stageWidth = 640;
    private int stageHeight = 480;

    public void setStageSize(int width, int height) {
        this.stageWidth = width;
        this.stageHeight = height;
    }

    @Override
    public void createWindow(String windowId, String template) {
        Bitmap bitmap = new Bitmap(DEFAULT_WIDTH, DEFAULT_HEIGHT, 32);
        bitmap.fill(0x00000000); // Transparent
        windows.put(windowId, new WindowState(bitmap, 0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT));
    }

    @Override
    public Datum getWindow(String windowId) {
        if (windows.containsKey(windowId)) {
            return new Datum.WindowRef(windowId);
        }
        return Datum.VOID;
    }

    @Override
    public void removeWindow(String windowId) {
        windows.remove(windowId);
    }

    @Override
    public void resizeWindow(String windowId, int width, int height) {
        WindowState state = windows.get(windowId);
        if (state == null || width <= 0 || height <= 0) return;

        Bitmap bitmap = new Bitmap(width, height, 32);
        // Fill with background color (opaque white by default, like Director's window buffer)
        bitmap.fill(0xFFFFFFFF);
        windows.put(windowId, new WindowState(bitmap, state.x, state.y, width, height));
    }

    @Override
    public void centerWindow(String windowId) {
        WindowState state = windows.get(windowId);
        if (state == null) return;

        int x = (stageWidth - state.width) / 2;
        int y = (stageHeight - state.height) / 2;
        windows.put(windowId, new WindowState(state.bitmap, x, y, state.width, state.height));
    }

    @Override
    public Bitmap getWindowBitmap(String windowId) {
        WindowState state = windows.get(windowId);
        return state != null ? state.bitmap : null;
    }

    @Override
    public Map<String, WindowBuffer> getWindowBuffers() {
        Map<String, WindowBuffer> buffers = new LinkedHashMap<>();
        for (Map.Entry<String, WindowState> entry : windows.entrySet()) {
            WindowState state = entry.getValue();
            buffers.put(entry.getKey(), new WindowBuffer(state.bitmap, state.x, state.y));
        }
        return buffers;
    }

    /**
     * Check if there are any active windows.
     */
    public boolean hasWindows() {
        return !windows.isEmpty();
    }

    private static class WindowState {
        final Bitmap bitmap;
        final int x, y, width, height;

        WindowState(Bitmap bitmap, int x, int y, int width, int height) {
            this.bitmap = bitmap;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
