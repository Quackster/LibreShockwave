package com.libreshockwave.player.input;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Tracks mouse and keyboard input state for the Director player.
 * Updated by the UI thread (Swing EDT or browser JS), read by the VM thread.
 * All fields are volatile for safe cross-thread reads.
 */
public class InputState {

    // Mouse position (stage coordinates)
    private volatile int mouseH;
    private volatile int mouseV;

    // Mouse button state
    private volatile boolean mouseDown;
    private volatile boolean rightMouseDown;

    // Last click info
    private volatile int clickOnSprite;  // sprite channel of last click
    private volatile int clickLocH;
    private volatile int clickLocV;

    // Rollover tracking
    private volatile int rolloverSprite;  // sprite channel mouse is currently over

    // Keyboard state
    private volatile String lastKey = "";     // character of last key pressed
    private volatile int lastKeyCode;         // Director Mac keycode
    private volatile boolean shiftDown;
    private volatile boolean controlDown;     // Ctrl on Windows = command on Mac
    private volatile boolean altDown;         // Alt on Windows = option on Mac

    // Keyboard focus
    private volatile int keyboardFocusSprite;

    // Selection state (for text fields)
    private volatile int selStart;
    private volatile int selEnd;

    // Event queue — input events queued by UI thread, processed by VM thread during tick.
    // Using LinkedList instead of ConcurrentLinkedQueue for TeaVM WASM compatibility.
    // WASM is single-threaded; on desktop, queue/poll don't race on the same element.
    private final Queue<InputEvent> eventQueue = new LinkedList<>();

    // --- Mouse position ---

    public int getMouseH() { return mouseH; }
    public int getMouseV() { return mouseV; }

    public void setMousePosition(int h, int v) {
        this.mouseH = h;
        this.mouseV = v;
    }

    // --- Mouse buttons ---

    public boolean isMouseDown() { return mouseDown; }
    public boolean isRightMouseDown() { return rightMouseDown; }

    public void setMouseDown(boolean down) { this.mouseDown = down; }
    public void setRightMouseDown(boolean down) { this.rightMouseDown = down; }

    // --- Click info ---

    public int getClickOnSprite() { return clickOnSprite; }
    public void setClickOnSprite(int channel) { this.clickOnSprite = channel; }

    public int getClickLocH() { return clickLocH; }
    public int getClickLocV() { return clickLocV; }
    public void setClickLoc(int h, int v) { this.clickLocH = h; this.clickLocV = v; }

    // --- Rollover ---

    public int getRolloverSprite() { return rolloverSprite; }
    public void setRolloverSprite(int channel) { this.rolloverSprite = channel; }

    // --- Keyboard ---

    public String getLastKey() { return lastKey; }
    public void setLastKey(String key) { this.lastKey = key != null ? key : ""; }

    public int getLastKeyCode() { return lastKeyCode; }
    public void setLastKeyCode(int code) { this.lastKeyCode = code; }

    public boolean isShiftDown() { return shiftDown; }
    public void setShiftDown(boolean down) { this.shiftDown = down; }

    public boolean isControlDown() { return controlDown; }
    public void setControlDown(boolean down) { this.controlDown = down; }

    public boolean isAltDown() { return altDown; }
    public void setAltDown(boolean down) { this.altDown = down; }

    // --- Keyboard focus ---

    public int getKeyboardFocusSprite() { return keyboardFocusSprite; }
    public void setKeyboardFocusSprite(int channel) { this.keyboardFocusSprite = channel; }

    // --- Selection ---

    public int getSelStart() { return selStart; }
    public void setSelStart(int pos) { this.selStart = pos; }

    public int getSelEnd() { return selEnd; }
    public void setSelEnd(int pos) { this.selEnd = pos; }

    // --- Event queue ---

    public void queueEvent(InputEvent event) {
        eventQueue.add(event);
    }

    public InputEvent pollEvent() {
        return eventQueue.poll();
    }

    public boolean hasEvents() {
        return !eventQueue.isEmpty();
    }
}
