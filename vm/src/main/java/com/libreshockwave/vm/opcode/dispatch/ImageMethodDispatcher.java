package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Drawing;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.vm.Datum;

import java.util.List;

/**
 * Handles method calls on ImageRef objects.
 * Implements Director's image API: fill, draw, copyPixels, duplicate, etc.
 */
public final class ImageMethodDispatcher {

    private ImageMethodDispatcher() {}

    public static Datum dispatch(Datum.ImageRef imageRef, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        Bitmap bmp = imageRef.bitmap();

        return switch (method) {
            case "fill" -> fill(bmp, args);
            case "draw" -> draw(bmp, args);
            case "copypixels" -> copyPixels(bmp, args);
            case "duplicate" -> new Datum.ImageRef(bmp.copy());
            case "crop" -> crop(bmp, args);
            case "getat" -> {
                // getAt(index) on image - some scripts use this
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(bmp.getWidth());
                    case 2 -> Datum.of(bmp.getHeight());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Get a property from an ImageRef.
     */
    public static Datum getProperty(Datum.ImageRef imageRef, String propName) {
        Bitmap bmp = imageRef.bitmap();
        return switch (propName.toLowerCase()) {
            case "rect" -> new Datum.Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            case "width" -> Datum.of(bmp.getWidth());
            case "height" -> Datum.of(bmp.getHeight());
            case "depth" -> Datum.of(bmp.getBitDepth());
            case "ilk" -> Datum.symbol("image");
            default -> Datum.VOID;
        };
    }

    /**
     * image.fill(rect, color) - Fill a rectangular region with a color.
     * In Director: image.fill(destRect, color)
     * Also supports: image.fill(left, top, right, bottom, color)
     */
    private static Datum fill(Bitmap bmp, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;

        Datum firstArg = args.get(0);

        int left, top, right, bottom;
        int colorArgb;

        if (firstArg instanceof Datum.Rect rect) {
            // fill(rect, color)
            left = rect.left();
            top = rect.top();
            right = rect.right();
            bottom = rect.bottom();
            colorArgb = datumToArgb(args.get(1));
        } else if (args.size() >= 5) {
            // fill(left, top, right, bottom, color)
            left = args.get(0).toInt();
            top = args.get(1).toInt();
            right = args.get(2).toInt();
            bottom = args.get(3).toInt();
            colorArgb = datumToArgb(args.get(4));
        } else {
            return Datum.VOID;
        }

        int w = right - left;
        int h = bottom - top;
        if (w > 0 && h > 0) {
            bmp.fillRect(left, top, w, h, colorArgb);
        }

        return Datum.VOID;
    }

    /**
     * image.draw(rect, propList) - Draw a shape outline.
     * In Director: image.draw(destRect, [#color: color, #shapeType: #rect])
     * Also supports: image.draw(left, top, right, bottom, propList)
     */
    private static Datum draw(Bitmap bmp, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;

        Datum firstArg = args.get(0);

        int left, top, right, bottom;
        Datum propsArg;

        if (firstArg instanceof Datum.Rect rect) {
            // draw(rect, propList)
            left = rect.left();
            top = rect.top();
            right = rect.right();
            bottom = rect.bottom();
            propsArg = args.get(1);
        } else if (args.size() >= 5) {
            // draw(left, top, right, bottom, propList)
            left = args.get(0).toInt();
            top = args.get(1).toInt();
            right = args.get(2).toInt();
            bottom = args.get(3).toInt();
            propsArg = args.get(4);
        } else {
            return Datum.VOID;
        }

        // Extract color from propList
        int colorArgb = 0xFF000000; // default black
        String shapeType = "rect";

        if (propsArg instanceof Datum.PropList pl) {
            Datum colorDatum = pl.properties().getOrDefault("color",
                    pl.properties().getOrDefault("Color", Datum.VOID));
            if (!colorDatum.isVoid()) {
                colorArgb = datumToArgb(colorDatum);
            }
            Datum shapeDatum = pl.properties().getOrDefault("shapeType",
                    pl.properties().getOrDefault("shapetype", Datum.VOID));
            if (shapeDatum instanceof Datum.Symbol s) {
                shapeType = s.name().toLowerCase();
            }
        } else {
            // Second arg is a color directly
            colorArgb = datumToArgb(propsArg);
        }

        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) return Datum.VOID;

        switch (shapeType) {
            case "rect" -> Drawing.drawRect(bmp, left, top, w, h, colorArgb);
            case "oval", "ellipse" -> {
                int cx = left + w / 2;
                int cy = top + h / 2;
                Drawing.drawEllipse(bmp, cx, cy, w / 2, h / 2, colorArgb);
            }
            case "line" -> Drawing.drawLine(bmp, left, top, right, bottom, colorArgb);
            default -> Drawing.drawRect(bmp, left, top, w, h, colorArgb);
        }

        return Datum.VOID;
    }

    /**
     * image.copyPixels(sourceImage, destRect, srcRect [, propList])
     * Copies pixels from source to this image with optional ink and blend.
     */
    private static Datum copyPixels(Bitmap dest, List<Datum> args) {
        if (args.size() < 3) {
            return Datum.VOID;
        }

        Datum srcDatum = args.get(0);
        if (!(srcDatum instanceof Datum.ImageRef srcRef)) {
            return Datum.VOID;
        }
        Bitmap src = srcRef.bitmap();

        Datum destRectDatum = args.get(1);
        Datum srcRectDatum = args.get(2);

        if (!(destRectDatum instanceof Datum.Rect destRect)) {
            return Datum.VOID;
        }
        if (!(srcRectDatum instanceof Datum.Rect srcRect)) {
            return Datum.VOID;
        }

        // Optional propList with ink and blend
        Palette.InkMode ink = Palette.InkMode.COPY;
        int blend = 255;

        if (args.size() >= 4 && args.get(3) instanceof Datum.PropList pl) {
            // Check for #ink property
            Datum inkDatum = pl.properties().getOrDefault("ink",
                    pl.properties().getOrDefault("Ink", Datum.VOID));
            if (inkDatum instanceof Datum.Int inkInt) {
                ink = inkFromInt(inkInt.value());
            }
            // Check for #blend property
            Datum blendDatum = pl.properties().getOrDefault("blend",
                    pl.properties().getOrDefault("Blend", Datum.VOID));
            if (!blendDatum.isVoid()) {
                blend = (int) (blendDatum.toDouble() * 255.0 / 100.0);
            }
        }

        int srcW = srcRect.right() - srcRect.left();
        int srcH = srcRect.bottom() - srcRect.top();
        int destW = destRect.right() - destRect.left();
        int destH = destRect.bottom() - destRect.top();

        if (srcW == destW && srcH == destH) {
            // No scaling needed - direct copy
            Drawing.copyPixels(dest, src,
                    destRect.left(), destRect.top(),
                    srcRect.left(), srcRect.top(),
                    srcW, srcH, ink, blend);
        } else {
            // Scaling needed - create scaled intermediate
            Bitmap scaled = new Bitmap(destW, destH, src.getBitDepth());
            for (int y = 0; y < destH; y++) {
                int sy = srcRect.top() + (y * srcH / destH);
                for (int x = 0; x < destW; x++) {
                    int sx = srcRect.left() + (x * srcW / destW);
                    scaled.setPixel(x, y, src.getPixel(sx, sy));
                }
            }
            Drawing.copyPixels(dest, scaled,
                    destRect.left(), destRect.top(),
                    0, 0, destW, destH, ink, blend);
        }

        return Datum.VOID;
    }

    /**
     * image.crop(rect) - Crop image to rectangle, return new image.
     */
    private static Datum crop(Bitmap bmp, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        if (!(args.get(0) instanceof Datum.Rect rect)) return Datum.VOID;

        int w = rect.right() - rect.left();
        int h = rect.bottom() - rect.top();
        if (w <= 0 || h <= 0) return Datum.VOID;

        Bitmap cropped = bmp.getRegion(rect.left(), rect.top(), w, h);
        return new Datum.ImageRef(cropped);
    }

    /**
     * Convert a Datum color to ARGB int.
     * Handles: Color(r,g,b), packed RGB int, palette index.
     */
    static int datumToArgb(Datum colorDatum) {
        if (colorDatum instanceof Datum.Color c) {
            return 0xFF000000 | (c.r() << 16) | (c.g() << 8) | c.b();
        } else if (colorDatum instanceof Datum.Int i) {
            int val = i.value();
            if (val > 255) {
                // Packed RGB
                return 0xFF000000 | (val & 0xFFFFFF);
            } else {
                // Palette index (0=white, 255=black in Director)
                int gray = 255 - val;
                return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
        }
        return 0xFF000000; // default black
    }

    /**
     * Convert Director ink number to InkMode enum.
     */
    private static Palette.InkMode inkFromInt(int inkNum) {
        return switch (inkNum) {
            case 0 -> Palette.InkMode.COPY;
            case 1 -> Palette.InkMode.TRANSPARENT;
            case 2 -> Palette.InkMode.REVERSE;
            case 3 -> Palette.InkMode.GHOST;
            case 4 -> Palette.InkMode.NOT_COPY;
            case 5 -> Palette.InkMode.NOT_TRANSPARENT;
            case 6 -> Palette.InkMode.NOT_REVERSE;
            case 7 -> Palette.InkMode.NOT_GHOST;
            case 8 -> Palette.InkMode.MATTE;
            case 9 -> Palette.InkMode.MASK;
            case 32 -> Palette.InkMode.BLEND;
            case 33 -> Palette.InkMode.ADD_PIN;
            case 34 -> Palette.InkMode.ADD;
            case 35 -> Palette.InkMode.SUBTRACT_PIN;
            case 36 -> Palette.InkMode.BACKGROUND_TRANSPARENT;
            case 37 -> Palette.InkMode.LIGHTEST;
            case 38 -> Palette.InkMode.SUBTRACT;
            case 39 -> Palette.InkMode.DARKEST;
            case 40 -> Palette.InkMode.LIGHTEN;
            case 41 -> Palette.InkMode.DARKEN;
            default -> Palette.InkMode.COPY;
        };
    }
}
