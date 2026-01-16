package com.libreshockwave.player;

import com.libreshockwave.lingo.Datum;

/**
 * Represents a sprite on the stage.
 * Sprites are visual objects positioned in score channels.
 */
public class Sprite {

    private int channel;
    private int castLib;
    private int castMember;

    // Position and size
    private int locH;
    private int locV;
    private int width;
    private int height;
    private int regPointH;
    private int regPointV;

    // Visual properties
    private int ink;
    private int blend = 100;
    private boolean visible = true;
    private int foreColor;
    private int backColor;

    // Behavior properties
    private boolean moveable = false;
    private boolean editable = false;
    private int cursor = 0;

    // Span info
    private int startFrame;
    private int endFrame;

    public Sprite(int channel) {
        this.channel = channel;
    }

    // Getters and setters

    public int getChannel() { return channel; }
    public void setChannel(int channel) { this.channel = channel; }

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

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

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

    public boolean isMoveable() { return moveable; }
    public void setMoveable(boolean moveable) { this.moveable = moveable; }

    public boolean isEditable() { return editable; }
    public void setEditable(boolean editable) { this.editable = editable; }

    public int getCursor() { return cursor; }
    public void setCursor(int cursor) { this.cursor = cursor; }

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
        this.castLib = other.castLib;
        this.castMember = other.castMember;
        this.locH = other.locH;
        this.locV = other.locV;
        this.width = other.width;
        this.height = other.height;
        this.ink = other.ink;
        this.blend = other.blend;
        this.visible = other.visible;
        this.foreColor = other.foreColor;
        this.backColor = other.backColor;
        this.moveable = other.moveable;
        this.editable = other.editable;
    }

    @Override
    public String toString() {
        return String.format("Sprite[ch=%d, member=%d:%d, loc=(%d,%d), size=%dx%d, ink=%d]",
            channel, castLib, castMember, locH, locV, width, height, ink);
    }
}
