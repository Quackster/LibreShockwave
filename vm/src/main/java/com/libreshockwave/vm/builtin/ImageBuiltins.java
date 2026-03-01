package com.libreshockwave.vm.builtin;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Built-in functions for Director's image API.
 * Registers the image() constructor function.
 */
public final class ImageBuiltins {

    private ImageBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("image", ImageBuiltins::image);
    }

    /**
     * image(width, height, bitDepth)
     * Creates a new blank image with the specified dimensions and bit depth.
     * The image is filled with white (0xFFFFFFFF) by default.
     */
    private static Datum image(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) {
            return Datum.VOID;
        }

        int width = args.get(0).toInt();
        int height = args.get(1).toInt();
        int bitDepth = args.size() >= 3 ? args.get(2).toInt() : 32;

        if (width <= 0 || height <= 0) {
            return Datum.VOID;
        }

        Bitmap bmp = new Bitmap(width, height, bitDepth);
        bmp.fill(0xFFFFFFFF); // White background
        return new Datum.ImageRef(bmp);
    }
}
