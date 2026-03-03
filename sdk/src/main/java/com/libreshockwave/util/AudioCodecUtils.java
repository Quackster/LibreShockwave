package com.libreshockwave.util;

/**
 * Shared audio codec detection utilities used by SoundChunk and MediaChunk.
 */
public class AudioCodecUtils {

    private AudioCodecUtils() {}

    /**
     * Search for an MP3 sync frame within the given byte array.
     * Scans up to {@code searchLimit} bytes for a valid MP3 frame header
     * (sync word 0xFFE0+, valid version/layer/bitrate/sampleRate fields).
     *
     * @param data the audio data bytes
     * @param searchLimit maximum number of bytes to scan (typically 512)
     * @return true if a valid MP3 frame header was found
     */
    public static boolean containsMp3SyncFrame(byte[] data, int searchLimit) {
        if (data == null || data.length < 4) {
            return false;
        }
        int limit = Math.min(data.length - 4, searchLimit);
        for (int i = 0; i < limit; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
                int version = (data[i + 1] >> 3) & 3;
                int layer = (data[i + 1] >> 1) & 3;
                int bitrateIdx = (data[i + 2] >> 4) & 0xF;
                int sampleRateIdx = (data[i + 2] >> 2) & 3;

                if (version != 1 && layer != 0 && bitrateIdx != 0 && bitrateIdx != 15 && sampleRateIdx != 3) {
                    return true;
                }
            }
        }
        return false;
    }
}
