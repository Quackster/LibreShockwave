package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

/**
 * Sound chunk (snd ).
 * Contains audio data.
 */
public record SoundChunk(
    DirectorFile file,
    int id,
    int sampleRate,
    int sampleCount,
    int bitsPerSample,
    int channelCount,
    byte[] audioData
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.snd_;
    }

    public double durationSeconds() {
        if (sampleRate == 0) return 0;
        return (double) sampleCount / sampleRate;
    }

    public static SoundChunk read(DirectorFile file, BinaryReader reader, int id) {
        // Parse snd resource format
        int format = reader.readI16();
        int dataFormatCount = reader.readI16();

        int sampleRate = 22050;
        int bitsPerSample = 8;
        int channelCount = 1;
        int sampleCount = 0;

        if (dataFormatCount > 0) {
            int dataFormat = reader.readI16();
            int initOptions = reader.readI32();

            if (dataFormat == 1) {
                // Standard sampled sound header
                reader.skip(4); // pointer
                sampleCount = reader.readI32();
                sampleRate = reader.readI32() >> 16; // Fixed point
                reader.skip(4); // loop start
                reader.skip(4); // loop end
                int encoding = reader.readU8();
                reader.skip(1); // base frequency

                if (encoding == 0xFE) {
                    // Extended header
                    channelCount = reader.readI32();
                    bitsPerSample = reader.readI16();
                }
            }
        }

        byte[] audioData = reader.readBytes(reader.bytesLeft());

        return new SoundChunk(file, id, sampleRate, sampleCount, bitsPerSample, channelCount, audioData);
    }
}
