package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;

/**
 * Base interface for all Director file chunks.
 */
public sealed interface Chunk permits
        ConfigChunk,
        CastListChunk,
        CastChunk,
        CastMemberChunk,
        KeyTableChunk,
        ScriptContextChunk,
        ScriptNamesChunk,
        ScriptChunk,
        ScoreChunk,
        FrameLabelsChunk,
        BitmapChunk,
        PaletteChunk,
        TextChunk,
        SoundChunk,
        RawChunk {

    ChunkType type();

    int id();
}
