// Raw RGBA asset codec.
//
// The C++ exporter writes baked bitmaps and reference frames as raw RGBA bytes
// (R, G, B, A per pixel, row-major) — dependency-free and lossless, which keeps the
// differential harness pixel-exact with no PNG round-trip quantization. This module
// decodes those bytes back into the runtime's ARGB Uint32Array packing
// ((a << 24) | (r << 16) | (g << 8) | b) >>> 0, matching argb.ts.

import { Bitmap } from "./Bitmap.js";

/**
 * Decode raw RGBA bytes into a packed ARGB Uint32Array.
 * `bytes` must hold exactly width*height*4 bytes (R,G,B,A per pixel).
 */
export function decodeRgba(bytes: Uint8Array, width: number, height: number): Uint32Array {
  const count = width * height;
  if (bytes.length < count * 4) {
    throw new Error(`decodeRgba: expected ${count * 4} bytes, got ${bytes.length}`);
  }
  const argb = new Uint32Array(count);
  for (let i = 0; i < count; ++i) {
    const r = bytes[i * 4];
    const g = bytes[i * 4 + 1];
    const b = bytes[i * 4 + 2];
    const a = bytes[i * 4 + 3];
    argb[i] = (((a << 24) | (r << 16) | (g << 8) | b) >>> 0);
  }
  return argb;
}

/** Decode raw RGBA bytes straight into a 32-bit Bitmap (the runtime's pixel buffer). */
export function decodeRgbaToBitmap(bytes: Uint8Array, width: number, height: number): Bitmap {
  return new Bitmap(width, height, 32, decodeRgba(bytes, width, height));
}