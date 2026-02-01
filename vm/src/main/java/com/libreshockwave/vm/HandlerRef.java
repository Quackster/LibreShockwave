package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;

/**
 * Reference to a handler within a script.
 * Name resolution is handled by the ScriptChunk via its file reference.
 */
public record HandlerRef(ScriptChunk script, ScriptChunk.Handler handler) {
}
