import { describe, expect, it } from "vitest";
import { Bitmap } from "../src/Bitmap.js";
import { Palette } from "../src/Palette.js";

describe("Bitmap construction", () => {
  it("zero-fills a new bitmap", () => {
    const bmp = new Bitmap(3, 2, 32);
    expect(bmp.width()).toBe(3);
    expect(bmp.height()).toBe(2);
    expect(bmp.pixels().length).toBe(6);
    for (let i = 0; i < 6; ++i) {
      expect(bmp.pixels()[i]).toBe(0);
    }
  });

  it("rejects negative dimensions", () => {
    expect(() => new Bitmap(-1, 2, 32)).toThrow();
  });

  it("rejects a pixel array that does not match dimensions", () => {
    expect(() => new Bitmap(2, 2, 32, new Uint32Array(3))).toThrow();
  });
});

describe("Bitmap pixel access", () => {
  it("set/get within bounds; out of bounds reads zero", () => {
    const bmp = new Bitmap(2, 2, 32);
    bmp.setPixelRGBA(0, 0, 10, 20, 30, 40);
    expect(bmp.getPixel(0, 0)).toBe(((40 << 24) | (10 << 16) | (20 << 8) | 30) >>> 0);
    expect(bmp.getPixel(-1, 0)).toBe(0);
    expect(bmp.getPixel(2, 0)).toBe(0);
  });

  it("setPixelRGB forces full opacity", () => {
    const bmp = new Bitmap(1, 1, 32);
    bmp.setPixelRGB(0, 0, 0x12, 0x34, 0x56);
    expect(bmp.getPixel(0, 0)).toBe(0xff123456 >>> 0);
  });

  it("fill and fillRect write clamped regions", () => {
    const bmp = new Bitmap(3, 3, 32);
    bmp.fill(0xff112233 >>> 0);
    expect(bmp.getPixel(2, 2)).toBe(0xff112233 >>> 0);
    bmp.fillRect(-1, -1, 2, 2, 0xff000000 >>> 0);
    expect(bmp.getPixel(0, 0)).toBe(0xff000000 >>> 0);
    expect(bmp.getPixel(2, 2)).toBe(0xff112233 >>> 0);
  });
});

describe("Bitmap regions and copy", () => {
  it("getRegion extracts a sub-rectangle and copies out-of-bounds as skipped", () => {
    const bmp = new Bitmap(4, 4, 32);
    bmp.fillRect(1, 1, 2, 2, 0xffffffff >>> 0);
    const region = bmp.getRegion(0, 0, 3, 3);
    expect(region.width()).toBe(3);
    expect(region.getPixel(1, 1)).toBe(0xffffffff >>> 0);
    expect(region.getPixel(0, 0)).toBe(0);
  });

  it("copy duplicates pixels and metadata independently", () => {
    const bmp = new Bitmap(1, 1, 32);
    bmp.setPixel(0, 0, 0xffaabbcc >>> 0);
    bmp.setNativeAlpha(true);
    const dup = bmp.copy();
    dup.setPixel(0, 0, 0xff000000 >>> 0);
    expect(bmp.getPixel(0, 0)).toBe(0xffaabbcc >>> 0);
    expect(dup.isNativeAlpha()).toBe(true);
  });
});

describe("Bitmap alpha flags", () => {
  it("detects transparent, translucent, and degenerate-alpha content", () => {
    const transparent = new Bitmap(2, 1, 32, new Uint32Array([0, 0xff112233 >>> 0]));
    expect(transparent.hasTransparentPixels()).toBe(true);
    expect(transparent.hasTranslucentPixels()).toBe(false);

    const translucent = new Bitmap(2, 1, 32, new Uint32Array([0x80808080 >>> 0, 0xff000000 >>> 0]));
    expect(translucent.hasTranslucentPixels()).toBe(true);

    const degenerate = new Bitmap(2, 1, 32, new Uint32Array([0x01000000 >>> 0, 0x00ff0000 >>> 0]));
    expect(degenerate.hasDegenerateAlphaWithRgbContent()).toBe(true);
  });

  it("does not report transparency for sub-32-bit media", () => {
    const bmp = new Bitmap(1, 1, 8);
    expect(bmp.hasTransparentPixels()).toBe(false);
  });

  it("copyWithNonNativeAlphaOpaque forces opaque for non-native-alpha bitmaps", () => {
    const bmp = new Bitmap(2, 1, 32, new Uint32Array([0, 0xff112233 >>> 0]));
    const opaque = bmp.copyWithNonNativeAlphaOpaque();
    expect(opaque.getPixel(0, 0)).toBe(0xff000000 >>> 0);
    expect(opaque.getPixel(1, 0)).toBe(0xff112233 >>> 0);
  });
});

describe("Bitmap trimWhiteSpace", () => {
  it("returns the bounding box of non-white, non-transparent content", () => {
    const bmp = new Bitmap(4, 4, 32);
    bmp.fillRect(1, 1, 2, 2, 0xff000000 >>> 0);
    expect(bmp.trimWhiteSpace()).toEqual({ left: 1, top: 1, right: 3, bottom: 3 });
  });

  it("returns an empty rect when the bitmap is all white/transparent", () => {
    const bmp = new Bitmap(3, 3, 32);
    bmp.fill(0xffffffff >>> 0);
    expect(bmp.trimWhiteSpace()).toEqual({ left: 0, top: 0, right: 0, bottom: 0 });
  });
});

describe("Bitmap palette-index quantization", () => {
  it("quantizes fills to an image palette when bitDepth <= 8", () => {
    const palette = new Palette([0x000000, 0xffffff], "two");
    const bmp = new Bitmap(2, 1, 8);
    bmp.setImagePalette(palette);
    bmp.fill(0xff808080 >>> 0);
    // nearest of 0x808080 among {black, white} is white (closer)
    expect(bmp.getPixel(0, 0)).toBe(0xffffffff >>> 0);
    expect(bmp.paletteIndex(0, 0)).toBe(1);
  });
});