package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Media chunk (ediM).
 * Contains audio data in newer Director/Shockwave format.
 * Can contain MP3, IMA ADPCM, or raw PCM audio.
 */
public record MediaChunk(
    DirectorFile file,
    int id,
    int sampleRate,
    int dataSizeField,
    byte[] guid,
    byte[] audioData,
    String codec
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.ediM;
    }

    public boolean isMp3() {
        return "mp3".equals(codec);
    }

    public boolean isAdpcm() {
        return "ima_adpcm".equals(codec);
    }

    /**
     * Convert this MediaChunk to a SoundChunk for unified handling.
     */
    public SoundChunk toSoundChunk() {
        int bitsPerSample = 16;
        int channelCount = isMp3() ? 2 : 1; // MP3 is typically stereo
        int sampleCount = 0;

        // For PCM, calculate sample count
        if (!isMp3() && !isAdpcm() && audioData.length > 0) {
            int bytesPerSample = bitsPerSample / 8;
            sampleCount = audioData.length / (bytesPerSample * channelCount);
        }

        return new SoundChunk(file, id, sampleRate, sampleCount, bitsPerSample, channelCount, audioData, codec);
    }

    /**
     * Detect codec type from audio data.
     * Searches for MP3 sync bytes which may be after ID3 tags.
     */
    private static String detectCodec(byte[] data, byte[] guid, int dataSizeField) {
        if (data == null || data.length < 4) {
            return "raw_pcm";
        }

        // Check GUID for IMA ADPCM (5A08CD40-535B-11D0-A8BB-00A0C9008A48)
        if (guid != null && guid.length >= 8) {
            if (guid[0] == 0x5A && guid[1] == 0x08 && guid[2] == (byte)0xCD && guid[3] == 0x40 &&
                guid[4] == 0x53 && guid[5] == 0x5B && guid[6] == 0x11 && guid[7] == (byte)0xD0) {
                return "ima_adpcm";
            }
        }

        // Check for ID3 tag at the start (MP3 with ID3 metadata)
        if (data.length >= 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            // It's an MP3 with ID3 tags
            return "mp3";
        }

        // Search for MP3 sync bytes (0xFF 0xEx or 0xFF 0xFx) in first 512 bytes
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

        // Check for IMA ADPCM by compression ratio
        if (data.length > 0 && dataSizeField > 0) {
            float compressionRatio = (float) dataSizeField / data.length;
            if (compressionRatio > 2.0f) {
                return "ima_adpcm";
            }
        }

        return "raw_pcm";
    }

    public static MediaChunk read(DirectorFile file, BinaryReader reader, int id) {
        int totalSize = reader.bytesLeft();

        if (totalSize < 4) {
            byte[] data = reader.readBytes(totalSize);
            return new MediaChunk(file, id, 22050, 0, null, data, detectCodec(data, null, 0));
        }

        // Peek at first 4 bytes to detect format
        int startPos = reader.getPosition();
        byte[] peek = reader.readBytes(Math.min(4, totalSize));
        reader.setPosition(startPos);

        // Check if this is raw MP3 data (starts with ID3 tag or MP3 sync)
        boolean isRawMp3 = false;
        if (peek.length >= 3 && peek[0] == 'I' && peek[1] == 'D' && peek[2] == '3') {
            isRawMp3 = true; // ID3 tag
        } else if (peek.length >= 2 && (peek[0] & 0xFF) == 0xFF && (peek[1] & 0xE0) == 0xE0) {
            isRawMp3 = true; // MP3 sync
        }

        if (isRawMp3) {
            // Raw MP3 file - use entire data as audio
            byte[] audioData = reader.readBytes(totalSize);
            return new MediaChunk(file, id, 22050, 0, null, audioData, "mp3");
        }

        // Standard MediaChunk format with header
        if (totalSize < 24) {
            byte[] data = reader.readBytes(totalSize);
            return new MediaChunk(file, id, 22050, 0, null, data, detectCodec(data, null, 0));
        }

        // Save original endian and switch to big-endian for header
        ByteOrder originalOrder = reader.getOrder();
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        int headerSize = reader.readI32();

        // Sanity check header size - if it's larger than total size, this isn't a valid header
        if (headerSize > totalSize || headerSize < 24) {
            reader.setOrder(originalOrder);
            reader.setPosition(startPos);
            byte[] audioData = reader.readBytes(totalSize);
            return new MediaChunk(file, id, 22050, 0, null, audioData, detectCodec(audioData, null, 0));
        }

        int unknown1 = reader.readI32();
        int sampleRate = reader.readI32();
        int sampleRate2 = reader.readI32();
        int unknown2 = reader.readI32();
        int dataSizeField = reader.readI32();

        // Calculate remaining header bytes
        int bytesRead = 24;
        int skipBytes = Math.max(0, headerSize - bytesRead);

        // Read GUID if present (16 bytes)
        byte[] guid = null;
        if (skipBytes >= 16) {
            guid = reader.readBytes(16);
            skipBytes -= 16;
        }

        // Skip remaining header padding
        if (skipBytes > 0 && reader.bytesLeft() >= skipBytes) {
            reader.skip(skipBytes);
        }

        // Read all remaining data as audio data
        byte[] audioData = reader.readBytes(reader.bytesLeft());

        // Restore original endian
        reader.setOrder(originalOrder);

        // Validate sample rate
        if (sampleRate <= 0 || sampleRate > 96000) {
            sampleRate = 22050; // Default fallback
        }

        String codec = detectCodec(audioData, guid, dataSizeField);

        return new MediaChunk(file, id, sampleRate, dataSizeField, guid, audioData, codec);
    }
}
