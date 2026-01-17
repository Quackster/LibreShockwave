package com.libreshockwave.player;

import com.libreshockwave.lingo.Datum;

/**
 * Represents a sprite on the stage.
 * Sprites are visual objects positioned in score channels.
 * Matches dirplayer-rs vm-rust/src/player/sprite.rs
 */
public class Sprite {

    private int channel;
    private String name = "";
    private int castLib;
    private int castMember;

    // Position and size
    private int locH;
    private int locV;
    private int locZ;  // Z-order/layer
    private int width;
    private int height;
    private int regPointH;
    private int regPointV;

    // Visual properties
    private int spriteType;
    private int ink;
    private int blend = 100;
    private boolean visible = true;
    private int foreColor;
    private int backColor;
    private int stretch;

    // Transformation properties
    private float rotation = 0.0f;
    private float skew = 0.0f;
    private boolean flipH = false;
    private boolean flipV = false;
    private int[] quad;  // [topLeftX, topLeftY, topRightX, topRightY, bottomRightX, bottomRightY, bottomLeftX, bottomLeftY]

    // Behavior properties
    private boolean puppet = false;  // If true, sprite is not controlled by score
    private boolean moveable = false;
    private boolean editable = false;
    private int cursor = 0;

    // Script instances (behaviors) attached to this sprite
    // Matches dirplayer-rs Sprite.script_instance_list
    private final java.util.List<Datum.ScriptInstanceRef> scriptInstanceList = new java.util.ArrayList<>();

    // Lifecycle flags (for beginSprite/endSprite events)
    private boolean entered = false;
    private boolean exited = false;

    // Span info
    private int startFrame;
    private int endFrame;

    public Sprite(int channel) {
        this.channel = channel;
        this.locZ = channel;  // Default z-order is channel number
    }

    // Getters and setters

    public int getChannel() { return channel; }
    public void setChannel(int channel) { this.channel = channel; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name != null ? name : ""; }

    public int getCastLib() { return castLib; }
    public void setCastLib(int castLib) { this.castLib = castLib; }

    public int getCastMember() { return castMember; }
    public void setCastMember(int castMember) { this.castMember = castMember; }

    public int getMemberNum() { return castMember; }
    public void setMemberNum(int memberNum) { this.castMember = memberNum; }

    public int getRegPointH() { return regPointH; }
    public void setRegPointH(int regPointH) { this.regPointH = regPointH; }

    public int getRegPointV() { return regPointV; }
    public void setRegPointV(int regPointV) { this.regPointV = regPointV; }

    public int getLocH() { return locH; }
    public void setLocH(int locH) { this.locH = locH; }

    public int getLocV() { return locV; }
    public void setLocV(int locV) { this.locV = locV; }

    public int getLocZ() { return locZ; }
    public void setLocZ(int locZ) { this.locZ = locZ; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public int getSpriteType() { return spriteType; }
    public void setSpriteType(int spriteType) { this.spriteType = spriteType; }

    public int getInk() { return ink; }
    public void setInk(int ink) { this.ink = ink; }

    public int getBlend() { return blend; }
    public void setBlend(int blend) { this.blend = blend; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public int getForeColor() { return foreColor; }
    public void setForeColor(int foreColor) { this.foreColor = foreColor; }

    public int getBackColor() { return backColor; }
    public void setBackColor(int backColor) { this.backColor = backColor; }

    public int getStretch() { return stretch; }
    public void setStretch(int stretch) { this.stretch = stretch; }

    public float getRotation() { return rotation; }
    public void setRotation(float rotation) { this.rotation = rotation; }

    public float getSkew() { return skew; }
    public void setSkew(float skew) { this.skew = skew; }

    public boolean isFlipH() { return flipH; }
    public void setFlipH(boolean flipH) { this.flipH = flipH; }

    public boolean isFlipV() { return flipV; }
    public void setFlipV(boolean flipV) { this.flipV = flipV; }

    public int[] getQuad() { return quad; }
    public void setQuad(int[] quad) { this.quad = quad; }

    public boolean isPuppet() { return puppet; }
    public void setPuppet(boolean puppet) { this.puppet = puppet; }

    public boolean isMoveable() { return moveable; }
    public void setMoveable(boolean moveable) { this.moveable = moveable; }

    public boolean isEditable() { return editable; }
    public void setEditable(boolean editable) { this.editable = editable; }

    public int getCursor() { return cursor; }
    public void setCursor(int cursor) { this.cursor = cursor; }

    /**
     * Get the list of script instances (behaviors) attached to this sprite.
     * Matches dirplayer-rs Sprite.script_instance_list
     */
    public java.util.List<Datum.ScriptInstanceRef> getScriptInstanceList() {
        return scriptInstanceList;
    }

    /**
     * Add a script instance (behavior) to this sprite.
     */
    public void addScriptInstance(Datum.ScriptInstanceRef instance) {
        scriptInstanceList.add(instance);
    }

    /**
     * Clear all script instances from this sprite.
     */
    public void clearScriptInstances() {
        scriptInstanceList.clear();
    }

    public boolean isEntered() { return entered; }
    public void setEntered(boolean entered) { this.entered = entered; }

    public boolean isExited() { return exited; }
    public void setExited(boolean exited) { this.exited = exited; }

    public int getStartFrame() { return startFrame; }
    public void setStartFrame(int startFrame) { this.startFrame = startFrame; }

    public int getEndFrame() { return endFrame; }
    public void setEndFrame(int endFrame) { this.endFrame = endFrame; }

    // Computed properties

    public Datum getLoc() {
        return new Datum.IntPoint(locH, locV);
    }

    public void setLoc(int h, int v) {
        this.locH = h;
        this.locV = v;
    }

    public Datum getRect() {
        return new Datum.IntRect(locH, locV, locH + width, locV + height);
    }

    public int getLeft() { return locH; }
    public int getTop() { return locV; }
    public int getRight() { return locH + width; }
    public int getBottom() { return locV + height; }

    public boolean hasMember() {
        return castMember > 0;
    }

    public boolean isActive() {
        return visible && hasMember();
    }

    public boolean containsPoint(int x, int y) {
        return x >= locH && x < locH + width && y >= locV && y < locV + height;
    }

    // Copy state from another sprite (for score updates)
    public void copyFrom(Sprite other) {
        this.name = other.name;
        this.castLib = other.castLib;
        this.castMember = other.castMember;
        this.locH = other.locH;
        this.locV = other.locV;
        this.locZ = other.locZ;
        this.width = other.width;
        this.height = other.height;
        this.spriteType = other.spriteType;
        this.ink = other.ink;
        this.blend = other.blend;
        this.visible = other.visible;
        this.foreColor = other.foreColor;
        this.backColor = other.backColor;
        this.stretch = other.stretch;
        this.rotation = other.rotation;
        this.skew = other.skew;
        this.flipH = other.flipH;
        this.flipV = other.flipV;
        this.quad = other.quad;
        this.puppet = other.puppet;
        this.moveable = other.moveable;
        this.editable = other.editable;
        this.cursor = other.cursor;
    }

    /**
     * Reset sprite to default state.
     * Matches dirplayer-rs Sprite::reset()
     */
    public void reset() {
        this.name = "";
        this.castLib = 0;
        this.castMember = 0;
        this.locH = 0;
        this.locV = 0;
        this.locZ = channel;
        this.width = 0;
        this.height = 0;
        this.spriteType = 0;
        this.ink = 0;
        this.blend = 100;
        this.visible = true;
        this.foreColor = 0;
        this.backColor = 0;
        this.stretch = 0;
        this.rotation = 0.0f;
        this.skew = 0.0f;
        this.flipH = false;
        this.flipV = false;
        this.quad = null;
        this.puppet = false;
        this.moveable = false;
        this.editable = false;
        this.cursor = 0;
        this.entered = false;
        this.exited = false;
    }

    @Override
    public String toString() {
        return String.format("Sprite[ch=%d, member=%d:%d, loc=(%d,%d), size=%dx%d, ink=%d]",
            channel, castLib, castMember, locH, locV, width, height, ink);
    }
}
