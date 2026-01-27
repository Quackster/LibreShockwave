package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Sound chunk (snd ).
 * Contains audio data in Director/Shockwave format.
 *
 * The format has a variable-size header (64, 96, or 128 bytes) followed by audio data.
 * Audio can be 16-bit big-endian PCM, MP3, or IMA ADPCM.
 * Sample rate is read from offset 0x16 (22050Hz) or 0x2A (44100Hz).
 */
public record SoundChunk(
    DirectorFile file,
    int id,
    int sampleRate,
    int sampleCount,
    int bitsPerSample,
    int channelCount,
    byte[] audioData,
    String codec
) implements Chunk {

    // Convenience constructor without codec
    public SoundChunk(DirectorFile file, int id, int sampleRate, int sampleCount,
                      int bitsPerSample, int channelCount, byte[] audioData) {
        this(file, id, sampleRate, sampleCount, bitsPerSample, channelCount, audioData, "raw_pcm");
    }

    @Override
    public ChunkType type() {
        return ChunkType.snd_;
    }

    public boolean isMp3() {
        return "mp3".equals(codec);
    }

    public boolean isAdpcm() {
        return "ima_adpcm".equals(codec);
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

    /**
     * Detect header size. Director MX 2004+ uses a 64-byte header.
     * For MP3 data, the header is always 64 bytes.
     * For PCM data, we check for audio patterns to determine if header extends further.
     */
    private static int detectHeaderSize(byte[] data) {
        if (data.length < 64) {
            return data.length;
        }

        // Check if data at offset 64 looks like MP3 (0xFF 0xFx or 0xFF 0xEx)
        if (data.length >= 66) {
            int b0 = data[64] & 0xFF;
            int b1 = data[65] & 0xFF;
            if (b0 == 0xFF && (b1 & 0xE0) == 0xE0) {
                return 64; // MP3 starts at offset 64
            }
        }

        // For PCM, check if audio patterns exist at offset 64
        // PCM audio typically has varied byte values
        if (data.length >= 128) {
            // Check if bytes 64-80 look like audio (not all zeros or header-like patterns)
            boolean looksLikeAudio = false;
            int nonZeroCount = 0;
            for (int i = 64; i < 80 && i < data.length; i++) {
                if (data[i] != 0) nonZeroCount++;
            }
            looksLikeAudio = nonZeroCount > 4;

            if (looksLikeAudio) {
                return 64;
            }

            // Check offset 96
            nonZeroCount = 0;
            for (int i = 96; i < 112 && i < data.length; i++) {
                if (data[i] != 0) nonZeroCount++;
            }
            if (nonZeroCount > 4) {
                return 96;
            }

            return 128;
        }

        return 64;
    }

    /**
     * Detect codec type from audio data.
     * Searches up to 512 bytes for MP3 sync pattern since there may be
     * additional metadata before the actual audio data.
     */
    private static String detectCodec(byte[] data) {
        if (data == null || data.length < 4) {
            return "raw_pcm";
        }

        // Search for MP3 sync bytes (0xFF 0xFx or 0xFF 0xEx) up to 512 bytes in
        int searchLimit = Math.min(data.length - 4, 512);
        for (int i = 0; i < searchLimit; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
                // Validate it looks like a real MP3 frame
                int version = (data[i + 1] >> 3) & 3;
                int layer = (data[i + 1] >> 1) & 3;
                int bitrateIdx = (data[i + 2] >> 4) & 0xF;
                int sampleRateIdx = (data[i + 2] >> 2) & 3;

                // Valid MP3: version != 1, layer != 0, bitrate != 0/15, sampleRate != 3
                if (version != 1 && layer != 0 && bitrateIdx != 0 && bitrateIdx != 15 && sampleRateIdx != 3) {
                    return "mp3";
                }
            }
        }

        return "raw_pcm";
    }

    public static SoundChunk read(DirectorFile file, BinaryReader reader, int id) {
        int startPos = reader.getPosition();
        int totalSize = reader.bytesLeft();

        if (totalSize < 64) {
            // Too small for header, return empty
            return new SoundChunk(file, id, 22050, 0, 16, 1, new byte[0], "raw_pcm");
        }

        // Read all data first to detect header size
        byte[] allData = reader.readBytes(totalSize);

        // Detect header size using audio pattern detection
        int headerSize = detectHeaderSize(allData);

        // Read header fields in big-endian
        ByteOrder originalOrder = reader.getOrder();

        // Parse sample rates from header
        int rateA = 22050; // default
        int rateB = 0;
        int rateC = 0;

        if (allData.length >= 8) {
            // Offset 0x04: Encoded sample rate (for 16000 Hz detection)
            int encodedRate = ((allData[4] & 0xFF) << 24) | ((allData[5] & 0xFF) << 16) |
                             ((allData[6] & 0xFF) << 8) | (allData[7] & 0xFF);
            rateA = (int) Math.round(encodedRate / 6.144);
            if (rateA > 15990 && rateA < 16020) {
                rateA = 16000;
            }
        }

        if (allData.length >= 0x18) {
            // Offset 0x16: Sample rate u16 (typically 22050)
            rateB = ((allData[0x16] & 0xFF) << 8) | (allData[0x17] & 0xFF);
        }

        if (allData.length >= 0x2C) {
            // Offset 0x2A: Sample rate u16 (typically 44100)
            rateC = ((allData[0x2A] & 0xFF) << 8) | (allData[0x2B] & 0xFF);
        }

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

        // Extract audio data after header
        byte[] audioData;
        if (headerSize > 0 && headerSize < allData.length) {
            audioData = new byte[allData.length - headerSize];
            System.arraycopy(allData, headerSize, audioData, 0, audioData.length);
        } else {
            audioData = new byte[0];
        }

        // Detect codec
        String codec = detectCodec(audioData);

        int bitsPerSample = 16;
        int channelCount = 1;

        // Calculate sample count for PCM
        int sampleCount = 0;
        if (!"mp3".equals(codec) && audioData.length > 0) {
            int bytesPerSample = bitsPerSample / 8;
            sampleCount = audioData.length / (bytesPerSample * channelCount);
        }

        return new SoundChunk(file, id, sampleRate, sampleCount, bitsPerSample, channelCount, audioData, codec);
    }
}
