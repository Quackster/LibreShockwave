import { describe, it, expect } from "vitest";

import { decodeRgba, decodeRgbaToBitmap } from "../src/RgbaAsset.js";
import { channel } from "../src/argb.js";

describe("RgbaAsset codec", () => {
  it("decodes raw RGBA bytes into packed ARGB matching the runtime packing", () => {
    // R=0x12, G=0x34, B=0x56, A=0xff -> ARGB 0xff123456
    const bytes = new Uint8Array([0x12, 0x34, 0x56, 0xff]);
    const argb = decodeRgba(bytes, 1, 1);
    expect(argb.length).toBe(1);
    expect(argb[0]).toBe(0xff123456);
    expect(channel(argb[0], 24)).toBe(0xff);
    expect(channel(argb[0], 16)).toBe(0x12);
    expect(channel(argb[0], 8)).toBe(0x34);
    expect(channel(argb[0], 0)).toBe(0x56);
  });

  it("decodes a multi-pixel buffer in row-major order", () => {
    const bytes = new Uint8Array([
      0xff, 0x00, 0x00, 0xff, // red opaque
      0x00, 0xff, 0x00, 0x80, // green half-transparent
      0x00, 0x00, 0xff, 0x00, // blue transparent
      0x80, 0x80, 0x80, 0xff, // gray opaque
    ]);
    const argb = decodeRgba(bytes, 2, 2);
    expect(argb[0]).toBe(0xffff0000);
    expect(argb[1]).toBe(0x8000ff00);
    expect(argb[2]).toBe(0x000000ff);
    expect(argb[3]).toBe(0xff808080);
  });

  it("rejects an undersized buffer", () => {
    expect(() => decodeRgba(new Uint8Array(2), 1, 1)).toThrow();
  });

  it("decodeRgbaToBitmap builds a Bitmap of the right dimensions and pixels", () => {
    const bytes = new Uint8Array([0xaa, 0xbb, 0xcc, 0xdd]);
    const bmp = decodeRgbaToBitmap(bytes, 1, 1);
    expect(bmp.width()).toBe(1);
    expect(bmp.height()).toBe(1);
    expect(bmp.pixels()[0]).toBe(0xddaabbcc);
  });
});