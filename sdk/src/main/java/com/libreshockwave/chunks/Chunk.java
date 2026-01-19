package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.vm.LingoVM;

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

    DirectorFile file();

    ChunkType type();

    int id();
}
