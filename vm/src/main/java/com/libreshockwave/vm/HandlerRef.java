package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;

/**
 * Reference to a handler within a script.
 */
public record HandlerRef(ScriptChunk script, ScriptChunk.Handler handler) {}
