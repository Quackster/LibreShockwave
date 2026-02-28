package com.libreshockwave.player.behavior;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.score.ScoreBehaviorRef;
import com.libreshockwave.vm.Datum;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents an instantiated behavior script.
 * Holds the script reference, properties, and lifecycle state.
 */
public class BehaviorInstance {

    private static int nextId = 1;

    private final int id;
    private final ScriptChunk script;
    private final ScoreBehaviorRef behaviorRef;
    private final int spriteNum;  // 0 for frame behaviors, 1+ for sprite behaviors

    private final Map<String, Datum> properties;
    private boolean beginSpriteCalled = false;
    private boolean endSpriteCalled = false;

    public BehaviorInstance(ScriptChunk script, ScoreBehaviorRef behaviorRef, int spriteNum) {
        this.id = nextId++;
        this.script = script;
        this.behaviorRef = behaviorRef;
        this.spriteNum = spriteNum;
        this.properties = new HashMap<>();

        // Initialize spriteNum property
        properties.put("spriteNum", Datum.of(spriteNum));
    }

    public int getId() {
        return id;
    }

    public ScriptChunk getScript() {
        return script;
    }

    public ScoreBehaviorRef getBehaviorRef() {
        return behaviorRef;
    }

    public int getSpriteNum() {
        return spriteNum;
    }

    public boolean isFrameBehavior() {
        return spriteNum == 0;
    }

    // Property access

    public Datum getProperty(String name) {
        return properties.getOrDefault(name, Datum.VOID);
    }

    public void setProperty(String name, Datum value) {
        properties.put(name, value);
    }

    public Map<String, Datum> getProperties() {
        return properties;
    }

    // Lifecycle state

    public boolean isBeginSpriteCalled() {
        return beginSpriteCalled;
    }

    public void setBeginSpriteCalled(boolean called) {
        this.beginSpriteCalled = called;
    }

    public boolean isEndSpriteCalled() {
        return endSpriteCalled;
    }

    public void setEndSpriteCalled(boolean called) {
        this.endSpriteCalled = called;
    }

    /**
     * Convert to a Datum for use as a receiver in handler calls.
     */
    public Datum toDatum() {
        return new Datum.ScriptInstance(id, new HashMap<>(properties));
    }

    @Override
    public String toString() {
        return "BehaviorInstance{id=" + id + ", spriteNum=" + spriteNum +
               ", script=" + (script != null ? "\"" + script.getScriptName() + "\" #" + script.id() : "null") + "}";
    }
}
