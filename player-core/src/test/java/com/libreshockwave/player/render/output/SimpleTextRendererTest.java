package com.libreshockwave.player.render.output;

import com.libreshockwave.bitmap.Bitmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleTextRendererTest {

    @Test
    void underlineFitsWithinAutosizedBitmapFontText() {
        SimpleTextRenderer renderer = new SimpleTextRenderer();

        Bitmap plain = renderer.renderText("Open", 33, 0,
                "Verdana", 9, "plain",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);
        Bitmap underlined = renderer.renderText("Open", 33, 0,
                "Verdana", 9, "underline",
                "left", 0xFF000000, 0x00FFFFFF,
                false, false, 9, 1);

        int plainLastRow = countOpaquePixelsOnRow(plain, underlined.getHeight() - 1);
        int underlinedLastRow = countOpaquePixelsOnRow(underlined, underlined.getHeight() - 1);

        assertEquals(10, underlined.getHeight());
        assertEquals(plain.getHeight(), underlined.getHeight());
        assertTrue(underlinedLastRow > plainLastRow,
                "expected underline to add pixels on the last row, plain=" + plainLastRow
                        + " underlined=" + underlinedLastRow);
        assertTrue(underlinedLastRow >= 20,
                "expected visible underline coverage on last row, got " + underlinedLastRow);
    }

    private static int countOpaquePixelsOnRow(Bitmap bitmap, int y) {
        int count = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            if (((bitmap.getPixel(x, y) >>> 24) & 0xFF) != 0) {
                count++;
            }
        }
        return count;
    }
}
