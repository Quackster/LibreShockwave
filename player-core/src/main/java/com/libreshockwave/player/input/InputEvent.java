package com.libreshockwave.player.input;

/**
 * An input event queued for processing during the next tick.
 * Created by the UI thread, consumed by the VM thread.
 */
public record InputEvent(
    Type type,
    int stageX,
    int stageY,
    int button,        // 0=none, 1=left, 2=middle, 3=right
    int keyCode,       // Director Mac virtual keycode
    String keyChar     // character string for keyboard events
) {
    public enum Type {
        MOUSE_DOWN,
        MOUSE_UP,
        RIGHT_MOUSE_DOWN,
        RIGHT_MOUSE_UP,
        KEY_DOWN,
        KEY_UP
    }

    public static InputEvent mouseDown(int x, int y) {
        return new InputEvent(Type.MOUSE_DOWN, x, y, 1, 0, "");
    }

    public static InputEvent mouseUp(int x, int y) {
        return new InputEvent(Type.MOUSE_UP, x, y, 1, 0, "");
    }

    public static InputEvent rightMouseDown(int x, int y) {
        return new InputEvent(Type.RIGHT_MOUSE_DOWN, x, y, 3, 0, "");
    }

    public static InputEvent rightMouseUp(int x, int y) {
        return new InputEvent(Type.RIGHT_MOUSE_UP, x, y, 3, 0, "");
    }

    public static InputEvent keyDown(int directorKeyCode, String ch) {
        return new InputEvent(Type.KEY_DOWN, 0, 0, 0, directorKeyCode, ch);
    }

    public static InputEvent keyUp(int directorKeyCode, String ch) {
        return new InputEvent(Type.KEY_UP, 0, 0, 0, directorKeyCode, ch);
    }
}
