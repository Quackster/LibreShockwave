package com.libreshockwave.id;

/**
 * 1-based Lctx script context index.
 * This is the index into the ScriptContextChunk's script entries,
 * NOT the ScriptChunk's chunk resource ID.
 */
public record ScriptContextId(int value) implements TypedId, Comparable<ScriptContextId> {

    public ScriptContextId {
        if (value < 1) {
            throw new IllegalArgumentException("ScriptContextId must be >= 1, got " + value);
        }
    }

    @Override
    public int compareTo(ScriptContextId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "ScriptContextId(" + value + ")";
    }
}
