package com.libreshockwave.audio;

import com.libreshockwave.chunks.SoundChunk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility for converting Director sound data to standard audio formats.
 *
 * Director stores audio data in various formats, typically big-endian PCM.
 * This converter can produce standard WAV files from SoundChunk data.
 */
public class SoundConverter {

    /**
     * Convert a SoundChunk to WAV format bytes.
     * Handles endianness conversion (Director = big-endian, WAV = little-endian for 16-bit).
     * Automatically detects header size (64, 96, or 128 bytes) and strips trailing padding.
     *
     * @param sound The SoundChunk to convert
     * @return WAV file bytes ready to be written to a file or played
     */
    public static byte[] toWav(SoundChunk sound) {
        byte[] fullData = sound.audioData();
        int headerSize = detectHeaderSize(fullData);
        int endOffset = stripTrailingPadding(fullData);

        if (endOffset <= headerSize) {
            return createEmptyWav(sound.sampleRate(), sound.bitsPerSample(), sound.channelCount());
        }

        byte[] pcmData = new byte[endOffset - headerSize];
        System.arraycopy(fullData, headerSize, pcmData, 0, pcmData.length);
        return toWav(pcmData, sound.sampleRate(), sound.bitsPerSample(),
                     sound.channelCount(), true);
    }

    /**
     * Extract MP3 data from a SoundChunk.
     * Automatically detects header size and strips trailing padding.
     *
     * @param sound The SoundChunk containing MP3 data
     * @return MP3 file bytes ready to be written to a file, or null if not MP3
     */
    public static byte[] extractMp3(SoundChunk sound) {
        if (!sound.isMp3()) {
            return null;
        }

        byte[] fullData = sound.audioData();
        int headerSize = detectHeaderSize(fullData);
        int endOffset = stripTrailingPadding(fullData);

        if (endOffset <= headerSize) {
            return null;
        }

        byte[] mp3Data = new byte[endOffset - headerSize];
        System.arraycopy(fullData, headerSize, mp3Data, 0, mp3Data.length);
        return mp3Data;
    }

    /**
     * Detect the snd chunk header size using dirplayer-rs algorithm.
     * Looks for audio data patterns (values 0x70-0x90) to determine where audio starts.
     */
    private static int detectHeaderSize(byte[] data) {
        if (data.length >= 128) {
            // Check if bytes 64-128 contain typical audio patterns
            for (int i = 64; i < 128 && i < data.length; i++) {
                int b = data[i] & 0xFF;
                if (b >= 0x70 && b <= 0x90) {
                    return 64;
                }
            }
            // Check bytes 96-128
            for (int i = 96; i < 128 && i < data.length; i++) {
                int b = data[i] & 0xFF;
                if (b >= 0x70 && b <= 0x90) {
                    return 96;
                }
            }
            return 128;
        } else if (data.length >= 64) {
            return 64;
        }
        return 0;
    }

    /**
     * Find where trailing 0xFF padding ends (returns the offset of last non-0xFF byte + 1).
     */
    private static int stripTrailingPadding(byte[] data) {
        int endOffset = data.length;
        while (endOffset > 0 && (data[endOffset - 1] & 0xFF) == 0xFF) {
            endOffset--;
        }
        return endOffset;
    }

    /**
     * Convert raw audio data to WAV format.
     *
     * @param audioData Raw PCM audio data
     * @param sampleRate Sample rate in Hz (e.g., 22050, 44100)
     * @param bitsPerSample Bits per sample (8 or 16)
     * @param channelCount Number of channels (1=mono, 2=stereo)
     * @param bigEndian Whether the input data is big-endian (Director format)
     * @return WAV file bytes
     */
    public static byte[] toWav(byte[] audioData, int sampleRate, int bitsPerSample,
                               int channelCount, boolean bigEndian) {
        if (audioData == null || audioData.length == 0) {
            return createEmptyWav(sampleRate, bitsPerSample, channelCount);
        }

        // Convert audio data to little-endian if needed
        byte[] wavAudioData;
        if (bitsPerSample == 16 && bigEndian) {
            wavAudioData = swapEndianness16(audioData);
        } else if (bitsPerSample == 8) {
            // 8-bit audio in Director may be signed, WAV expects unsigned
            // Convert signed (-128 to 127) to unsigned (0 to 255)
            wavAudioData = convertSignedToUnsigned8(audioData);
        } else {
            wavAudioData = audioData;
        }

        return buildWav(wavAudioData, sampleRate, bitsPerSample, channelCount);
    }

    /**
     * Convert raw audio data to WAV assuming standard Director format.
     *
     * @param audioData Raw PCM audio data (big-endian)
     * @param sampleRate Sample rate in Hz
     * @param bitsPerSample Bits per sample
     * @param channelCount Number of channels
     * @return WAV file bytes
     */
    public static byte[] toWav(byte[] audioData, int sampleRate, int bitsPerSample, int channelCount) {
        return toWav(audioData, sampleRate, bitsPerSample, channelCount, true);
    }

    /**
     * Swap endianness of 16-bit samples from big-endian to little-endian.
     */
    private static byte[] swapEndianness16(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i + 1 < data.length; i += 2) {
            result[i] = data[i + 1];
            result[i + 1] = data[i];
        }
        return result;
    }

    /**
     * Convert signed 8-bit samples to unsigned for WAV format.
     * Director may store 8-bit audio as signed (-128 to 127),
     * but WAV expects unsigned (0 to 255).
     */
    private static byte[] convertSignedToUnsigned8(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            // Add 128 to convert from signed to unsigned
            result[i] = (byte) ((data[i] & 0xFF) ^ 0x80);
        }
        return result;
    }

    /**
     * Build a complete WAV file from audio data.
     */
    private static byte[] buildWav(byte[] audioData, int sampleRate, int bitsPerSample, int channelCount) {
        int bytesPerSample = bitsPerSample / 8;
        int byteRate = sampleRate * channelCount * bytesPerSample;
        int blockAlign = channelCount * bytesPerSample;

        ByteBuffer buffer = ByteBuffer.allocate(44 + audioData.length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put((byte) 'R');
        buffer.put((byte) 'I');
        buffer.put((byte) 'F');
        buffer.put((byte) 'F');
        buffer.putInt(36 + audioData.length); // File size - 8

        // WAVE format
        buffer.put((byte) 'W');
        buffer.put((byte) 'A');
        buffer.put((byte) 'V');
        buffer.put((byte) 'E');

        // fmt subchunk
        buffer.put((byte) 'f');
        buffer.put((byte) 'm');
        buffer.put((byte) 't');
        buffer.put((byte) ' ');
        buffer.putInt(16);              // Subchunk1 size (16 for PCM)
        buffer.putShort((short) 1);     // Audio format (1 = PCM)
        buffer.putShort((short) channelCount);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort((short) blockAlign);
        buffer.putShort((short) bitsPerSample);

        // data subchunk
        buffer.put((byte) 'd');
        buffer.put((byte) 'a');
        buffer.put((byte) 't');
        buffer.put((byte) 'a');
        buffer.putInt(audioData.length);
        buffer.put(audioData);

        return buffer.array();
    }

    /**
     * Create an empty/silent WAV file.
     */
    private static byte[] createEmptyWav(int sampleRate, int bitsPerSample, int channelCount) {
        return buildWav(new byte[0], sampleRate, bitsPerSample, channelCount);
    }

    /**
     * IMA ADPCM step table.
     */
    private static final int[] STEP_TABLE = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    /**
     * IMA ADPCM index table.
     */
    private static final int[] INDEX_TABLE = {
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    };

    /**
     * Decode IMA ADPCM compressed audio to 16-bit PCM.
     *
     * @param adpcmData Compressed IMA ADPCM data
     * @param initialPredictor Initial predictor value (from header)
     * @param initialIndex Initial step index (from header)
     * @return Decoded 16-bit PCM samples (little-endian)
     */
    public static byte[] decodeImaAdpcm(byte[] adpcmData, int initialPredictor, int initialIndex) {
        if (adpcmData == null || adpcmData.length == 0) {
            return new byte[0];
        }

        // Each byte contains 2 4-bit ADPCM samples
        int sampleCount = adpcmData.length * 2;
        short[] samples = new short[sampleCount];

        int predictor = initialPredictor;
        int stepIndex = Math.clamp(initialIndex, 0, 88);

        int sampleIdx = 0;
        for (byte b : adpcmData) {
            // Process low nibble first, then high nibble
            for (int nibbleIdx = 0; nibbleIdx < 2; nibbleIdx++) {
                int nibble = (nibbleIdx == 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);

                int step = STEP_TABLE[stepIndex];

                // Calculate difference
                int diff = step >> 3;
                if ((nibble & 1) != 0) diff += step >> 2;
                if ((nibble & 2) != 0) diff += step >> 1;
                if ((nibble & 4) != 0) diff += step;
                if ((nibble & 8) != 0) diff = -diff;

                // Update predictor
                predictor += diff;
                predictor = Math.clamp(predictor, -32768, 32767);

                samples[sampleIdx++] = (short) predictor;

                // Update step index
                stepIndex += INDEX_TABLE[nibble];
                stepIndex = Math.clamp(stepIndex, 0, 88);
            }
        }

        // Convert short array to byte array (little-endian)
        byte[] result = new byte[sampleCount * 2];
        ByteBuffer buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return result;
    }

    /**
     * Decode IMA ADPCM to WAV format.
     *
     * @param adpcmData Compressed IMA ADPCM data
     * @param sampleRate Sample rate
     * @param channelCount Number of channels
     * @param initialPredictor Initial predictor value
     * @param initialIndex Initial step index
     * @return WAV file bytes
     */
    public static byte[] imaAdpcmToWav(byte[] adpcmData, int sampleRate, int channelCount,
                                        int initialPredictor, int initialIndex) {
        byte[] pcmData = decodeImaAdpcm(adpcmData, initialPredictor, initialIndex);
        return buildWav(pcmData, sampleRate, 16, channelCount);
    }

    /**
     * Check if the audio data appears to be MP3 by looking for sync bytes.
     *
     * @param data Audio data to check
     * @return true if MP3 sync bytes are found
     */
    public static boolean isMp3(byte[] data) {
        return findMp3Start(data) >= 0;
    }

    /**
     * Find the start of MP3 data by looking for sync bytes (0xFF 0xE0+).
     *
     * @param data Audio data to search
     * @return Offset of MP3 start, or -1 if not found
     */
    public static int findMp3Start(byte[] data) {
        if (data == null || data.length < 4) {
            return -1;
        }

        for (int i = 0; i < data.length - 1; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
                // Found potential MP3 sync, validate with more frames
                if (validateMp3Sequence(data, i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Validate that we have a real MP3 sequence (not just random bytes).
     */
    private static boolean validateMp3Sequence(byte[] data, int offset) {
        int validFrames = 0;
        int pos = offset;

        while (pos < data.length - 4 && validFrames < 3) {
            if ((data[pos] & 0xFF) != 0xFF || (data[pos + 1] & 0xE0) != 0xE0) {
                break;
            }

            // Extract frame info
            int header = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16) |
                        ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);

            int version = (header >> 19) & 3;
            int layer = (header >> 17) & 3;
            int bitrateIndex = (header >> 12) & 0xF;
            int sampleRateIndex = (header >> 10) & 3;
            int padding = (header >> 9) & 1;

            // Invalid indices
            if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 ||
                sampleRateIndex == 3) {
                break;
            }

            // Calculate frame size
            int frameSize = calculateMp3FrameSize(version, layer, bitrateIndex, sampleRateIndex, padding);
            if (frameSize < 0 || frameSize > data.length - pos) {
                break;
            }

            validFrames++;
            pos += frameSize;
        }

        return validFrames >= 2;
    }

    /**
     * Calculate MP3 frame size.
     */
    private static int calculateMp3FrameSize(int version, int layer, int bitrateIndex,
                                              int sampleRateIndex, int padding) {
        // Bitrate tables (kbps)
        int[][] bitrates = {
            // MPEG2/2.5 Layer III
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0},
            // MPEG1 Layer III
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
        };

        // Sample rate tables (Hz)
        int[][] sampleRates = {
            {11025, 12000, 8000},   // MPEG2.5
            {0, 0, 0},              // Reserved
            {22050, 24000, 16000},  // MPEG2
            {44100, 48000, 32000}   // MPEG1
        };

        int bitrate = bitrates[version == 3 ? 1 : 0][bitrateIndex];
        int sampleRate = sampleRates[version][sampleRateIndex];

        if (bitrate == 0 || sampleRate == 0) {
            return -1;
        }

        // Frame size formula for Layer III
        int frameSize = (144 * bitrate * 1000 / sampleRate) + padding;
        return frameSize;
    }

    /**
     * Get audio duration in seconds.
     *
     * @param sound The SoundChunk
     * @return Duration in seconds
     */
    public static double getDuration(SoundChunk sound) {
        return sound.durationSeconds();
    }

    /**
     * Get audio duration from raw data parameters.
     *
     * @param dataLength Length of audio data in bytes
     * @param sampleRate Sample rate in Hz
     * @param bitsPerSample Bits per sample
     * @param channelCount Number of channels
     * @return Duration in seconds
     */
    public static double getDuration(int dataLength, int sampleRate, int bitsPerSample, int channelCount) {
        if (sampleRate == 0 || bitsPerSample == 0 || channelCount == 0) {
            return 0;
        }
        int bytesPerSample = bitsPerSample / 8;
        int sampleCount = dataLength / (bytesPerSample * channelCount);
        return (double) sampleCount / sampleRate;
    }
}
