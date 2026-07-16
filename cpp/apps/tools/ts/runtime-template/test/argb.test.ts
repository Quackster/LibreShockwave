import { describe, expect, it } from "vitest";
import { channel, packArgb, maskAlphaFromPixel, matchesRgb, isOpaqueColor, alphaOf, rgbOf } from "../src/argb.js";

describe("argb channel/pack", () => {
  it("round-trips a packed ARGB pixel through its channels", () => {
    const argb = packArgb(0x12, 0x34, 0x56, 0x78);
    expect(argb).toBe(0x12345678 >>> 0);
    expect(channel(argb, 24)).toBe(0x12);
    expect(channel(argb, 16)).toBe(0x34);
    expect(channel(argb, 8)).toBe(0x56);
    expect(channel(argb, 0)).toBe(0x78);
  });

  it("keeps fully-opaque white and black as unsigned 32-bit", () => {
    expect(packArgb(0xff, 0xff, 0xff, 0xff)).toBe(0xffffffff >>> 0);
    expect(packArgb(0xff, 0, 0, 0)).toBe(0xff000000 >>> 0);
  });

  it("extracts alpha and rgb triplets", () => {
    const argb = packArgb(0xab, 0x10, 0x20, 0x30);
    expect(alphaOf(argb)).toBe(0xab);
    expect(rgbOf(argb)).toBe(0x102030);
  });
});

describe("argb matte helpers", () => {
  it("maskAlphaFromPixel follows REC601 luma weights", () => {
    // white -> 255
    expect(maskAlphaFromPixel(packArgb(0, 0xff, 0xff, 0xff))).toBe(255);
    // black -> 0
    expect(maskAlphaFromPixel(packArgb(0, 0, 0, 0))).toBe(0);
  });

  it("matchesRgb respects per-channel tolerance", () => {
    const pixel = packArgb(0xff, 100, 100, 100);
    expect(matchesRgb(pixel, (100 << 16) | (100 << 8) | 100, 0)).toBe(true);
    expect(matchesRgb(pixel, (110 << 16) | (100 << 8) | 100, 5)).toBe(false);
    expect(matchesRgb(pixel, (110 << 16) | (100 << 8) | 100, 10)).toBe(true);
  });

  it("isOpaqueColor checks alpha and rgb", () => {
    const pixel = packArgb(0xff, 0x12, 0x34, 0x56);
    expect(isOpaqueColor(pixel, 0x123456)).toBe(true);
    expect(isOpaqueColor(pixel, 0x123450)).toBe(false);
    expect(isOpaqueColor(packArgb(0x80, 0x12, 0x34, 0x56), 0x123456)).toBe(false);
  });
});