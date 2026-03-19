package com.libreshockwave.bitmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DrawingMatteTest {

    @Test
    void createMatteRecoversAntialiasedFringeAlpha() {
        Bitmap src = new Bitmap(4, 1, 32, new int[] {
            0xFFFFFFFF,
            0x00DDDDDD,
            0xFF000000,
            0xFFFFFFFF
        });

        Bitmap matte = Drawing.createMatte(src);

        assertEquals(0x00000000, matte.getPixel(0, 0));
        assertEquals(0x00000000, matte.getPixel(1, 0));
        assertEquals(0xFFFFFFFF, matte.getPixel(2, 0));
        assertEquals(0x00000000, matte.getPixel(3, 0));
    }

    @Test
    void matteCopyPixelsKeepsSolidColoredTileContent() {
        Bitmap dest = new Bitmap(1, 1, 32);
        Bitmap src = new Bitmap(1, 1, 32, new int[] { 0xFFD4DDE1 });

        Drawing.copyPixels(dest, src, 0, 0, 0, 0, 1, 1, Palette.InkMode.MATTE, 255);

        assertEquals(0xFFD4DDE1, dest.getPixel(0, 0));
    }
}
