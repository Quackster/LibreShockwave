package com.libreshockwave.player;

/**
 * Events that the player dispatches to scripts.
 * Corresponds to Director's built-in events.
 */
public enum PlayerEvent {
    // Movie lifecycle events
    PREPARE_MOVIE("prepareMovie"),
    START_MOVIE("startMovie"),
    STOP_MOVIE("stopMovie"),

    // Frame events
    PREPARE_FRAME("prepareFrame"),
    ENTER_FRAME("enterFrame"),
    EXIT_FRAME("exitFrame"),
    STEP_FRAME("stepFrame"),

    // Sprite events
    BEGIN_SPRITE("beginSprite"),
    END_SPRITE("endSprite"),

    // Idle event
    IDLE("idle"),

    // Mouse events
    MOUSE_DOWN("mouseDown"),
    MOUSE_UP("mouseUp"),
    MOUSE_ENTER("mouseEnter"),
    MOUSE_LEAVE("mouseLeave"),
    MOUSE_WITHIN("mouseWithin"),
    MOUSE_UP_OUTSIDE("mouseUpOutside"),
    RIGHT_MOUSE_DOWN("rightMouseDown"),
    RIGHT_MOUSE_UP("rightMouseUp"),

    // Keyboard events
    KEY_DOWN("keyDown"),
    KEY_UP("keyUp");

    private final String handlerName;

    PlayerEvent(String handlerName) {
        this.handlerName = handlerName;
    }

    public String getHandlerName() {
        return handlerName;
    }
}
