package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Sound chunk (snd ).
 * Contains audio data in Director/Shockwave format.
 *
 * The format has a 64-byte header followed by 16-bit big-endian PCM audio data.
 * Sample rate is read from offset 0x16 (22050Hz) or 0x2A (44100Hz).
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

    private static final int HEADER_SIZE = 64;

    @Override
    public ChunkType type() {
        return ChunkType.snd_;
    }

    public double durationSeconds() {
        if (sampleRate == 0) return 0;
        if (sampleCount > 0) {
            return (double) sampleCount / sampleRate;
        }
        // Calculate from audio data size
        int bytesPerSample = bitsPerSample / 8;
        if (bytesPerSample == 0) bytesPerSample = 2; // Default to 16-bit
        int samples = audioData.length / (bytesPerSample * channelCount);
        return (double) samples / sampleRate;
    }

    public static SoundChunk read(DirectorFile file, BinaryReader reader, int id) {
        // Director MX 2004+ snd chunk format:
        // - 64-byte header with sample rate at specific offsets
        // - Audio data is 16-bit big-endian PCM

        int startPos = reader.getPosition();
        int totalSize = reader.bytesLeft();

        if (totalSize < HEADER_SIZE) {
            // Too small for header, return empty
            return new SoundChunk(file, id, 22050, 0, 16, 1, new byte[0]);
        }

        // Read header in big-endian to extract sample rates
        ByteOrder originalOrder = reader.getOrder();
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        // Skip first 4 bytes
        reader.skip(4);

        // Offset 0x04: Encoded sample rate (for 16000 Hz detection)
        int encodedRate = reader.readI32();
        int rateA = (int) Math.round(encodedRate / 6.144);
        if (rateA > 15990 && rateA < 16020) {
            rateA = 16000;
        }

        // Skip to offset 0x16 (we're at 0x08, skip 14 bytes)
        reader.skip(14);

        // Offset 0x16: Sample rate u16 (typically 22050)
        int rateB = reader.readU16();

        // Skip to offset 0x2A (we're at 0x18, skip 18 bytes)
        reader.skip(18);

        // Offset 0x2A: Sample rate u16 (typically 44100)
        int rateC = reader.readU16();

        // Determine final sample rate with priority
        int sampleRate;
        if (rateB == 22050) {
            sampleRate = 22050;
        } else if (rateC == 44100) {
            sampleRate = 44100;
        } else if (rateA >= 8000 && rateA <= 48000) {
            sampleRate = rateA;
        } else {
            sampleRate = 22050; // Default fallback
        }

        // Skip remaining header (we're at 0x2C, need to get to 0x40)
        reader.skip(HEADER_SIZE - 0x2C);

        // Read audio data (16-bit big-endian PCM)
        byte[] audioData = reader.readBytes(reader.bytesLeft());

        reader.setOrder(originalOrder);

        // Check for MP3 sync bytes
        boolean isMp3 = audioData.length >= 2 &&
            (audioData[0] & 0xFF) == 0xFF &&
            (audioData[1] & 0xF0) == 0xF0;

        int bitsPerSample = 16;
        int channelCount = 1;

        // Calculate sample count for PCM
        int sampleCount = 0;
        if (!isMp3 && audioData.length > 0) {
            int bytesPerSample = bitsPerSample / 8;
            sampleCount = audioData.length / (bytesPerSample * channelCount);
        }

        return new SoundChunk(file, id, sampleRate, sampleCount, bitsPerSample, channelCount, audioData);
    }
}
