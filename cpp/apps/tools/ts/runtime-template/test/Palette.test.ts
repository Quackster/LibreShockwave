import { describe, expect, it } from "vitest";
import { Palette, PaletteId } from "../src/Palette.js";
import { Rgb, PaletteIndex, ColorRef } from "../src/ColorRef.js";

describe("Palette", () => {
  it("getColor returns the entry, or 0 out of range", () => {
    const palette = new Palette([0xff0000, 0x00ff00, 0x0000ff], "rgb");
    expect(palette.getColor(0)).toBe(0xff0000);
    expect(palette.getColor(3)).toBe(0);
  });

  it("getRGB splits a color into channels", () => {
    const palette = new Palette([0x123456], "x");
    expect(palette.getRGB(0)).toEqual([0x12, 0x34, 0x56]);
  });

  it("nearestIndex picks the closest entry by squared distance, exact match short-circuits", () => {
    const palette = new Palette([0x000000, 0x101010, 0xffffff], "g");
    expect(palette.nearestIndex(0x050505)).toBe(0);
    expect(palette.nearestIndex(0xffffff)).toBe(2);
    expect(palette.nearestIndex(0x202020)).toBe(1);
  });

  it("builtInSymbolName maps the system palette ids", () => {
    expect(Palette.builtInSymbolName(PaletteId.SYSTEM_MAC)).toBe("systemMac");
    expect(Palette.builtInSymbolName(PaletteId.SYSTEM_WIN)).toBe("systemWin");
    expect(Palette.builtInSymbolName(7)).toBeUndefined();
  });

  it("normalizeBuiltInSymbolName accepts aliases", () => {
    expect(Palette.normalizeBuiltInSymbolName("Greyscale")).toBe("grayscale");
    expect(Palette.normalizeBuiltInSymbolName("systemWindows")).toBe("systemWin");
    expect(Palette.normalizeBuiltInSymbolName("nope")).toBeUndefined();
  });
});

describe("ColorRef", () => {
  it("Rgb clamps channels to bytes and packs", () => {
    const rgb = new Rgb(300, -5, 128);
    expect(rgb.r).toBe(255);
    expect(rgb.g).toBe(0);
    expect(rgb.b).toBe(128);
    expect(rgb.toPacked()).toBe(0xff0080);
    expect(rgb.toArgb()).toBe(0xffff0080 >>> 0);
  });

  it("Rgb.fromHex strips a leading hash and parses 6-digit hex", () => {
    expect(Rgb.fromHex("#102030").toPacked()).toBe(0x102030);
    expect(Rgb.fromHex("ffffff").toPacked()).toBe(0xffffff);
    expect(() => Rgb.fromHex("#1234567")).toThrow();
    expect(() => Rgb.fromHex("zzz")).toThrow();
  });

  it("ColorRef resolves an Rgb directly and a PaletteIndex via a palette", () => {
    const palette = new Palette([0x112233, 0x445566], "p");
    const rgbRef = new ColorRef(new Rgb(0x10, 0x20, 0x30));
    expect(rgbRef.isRgb()).toBe(true);
    expect(rgbRef.toRgb().toPacked()).toBe(0x102030);

    const indexRef = new ColorRef(new PaletteIndex(1));
    expect(indexRef.isPaletteIndex()).toBe(true);
    expect(indexRef.toRgb(palette).toPacked()).toBe(0x445566);
    expect(indexRef.toNearestPaletteIndex()).toBe(1);
  });

  it("ColorRef.toNearestPaletteIndex maps an Rgb to the nearest entry", () => {
    const palette = new Palette([0x000000, 0xffffff], "bw");
    const ref = new ColorRef(new Rgb(0x10, 0x10, 0x10));
    expect(ref.toNearestPaletteIndex(palette)).toBe(0);
  });
});