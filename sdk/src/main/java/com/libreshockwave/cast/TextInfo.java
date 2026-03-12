package com.libreshockwave.cast;

/**
 * Parsed text member properties from CASt specificData.
 * Used for Score-placed text members (STXT-based) to extract formatting info.
 */
public record TextInfo(
    int textAlign,
    int bgRed, int bgGreen, int bgBlue,
    int width, int height,
    boolean isWordWrap
) {
    /**
     * Parse TextInfo from CASt specificData bytes.
     * Director text member specificData layout varies by version.
     */
    public static TextInfo parse(byte[] data) {
        if (data == null || data.length < 4) {
            return new TextInfo(0, 255, 255, 255, 0, 0, true);
        }

        int textAlign = 0;
        int bgR = 255, bgG = 255, bgB = 255;
        int width = 0, height = 0;
        boolean wordWrap = true;

        // Text member specificData: alignment at offset 0 (i16 BE)
        if (data.length >= 2) {
            textAlign = (short) (((data[0] & 0xFF) << 8) | (data[1] & 0xFF));
        }

        // Background color at offset 2-7 (RGB, 2 bytes each in Director format)
        if (data.length >= 8) {
            bgR = data[2] & 0xFF;
            bgG = data[4] & 0xFF;
            bgB = data[6] & 0xFF;
        }

        // Width/height at varying offsets depending on version
        if (data.length >= 48) {
            // D7+ text member: width at offset 38, height at offset 40
            width = ((data[38] & 0xFF) << 8) | (data[39] & 0xFF);
            height = ((data[40] & 0xFF) << 8) | (data[41] & 0xFF);
        }

        return new TextInfo(textAlign, bgR, bgG, bgB, width, height, wordWrap);
    }
}
