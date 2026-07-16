// ARGB pixel math. Faithful TypeScript port of the inline helpers in
// cpp/src/bitmap/BitmapProcessing.hpp (namespace libreshockwave::bitmap::detail).
//
// Pixel format is packed 32-bit ARGB: (alpha << 24) | (red << 16) | (green << 8) | blue,
// matching the C++ std::vector<uint32_t> storage. JS bitwise operators coerce to signed
// 32-bit, so packArgb() ends with `>>> 0` to keep the result an unsigned 32-bit integer
// (matching uint32_t); channel() masks with & 0xFF, which yields the same byte regardless
// of sign extension.

/** Extracts an 8-bit channel from a packed ARGB pixel at the given bit shift. */
export function channel(argb: number, shift: number): number {
  return (argb >>> shift) & 0xff;
}

/** Packs four 8-bit channels into a single unsigned 32-bit ARGB integer. */
export function packArgb(alpha: number, r: number, g: number, b: number): number {
  return (
    (((alpha & 0xff) << 24) |
      ((r & 0xff) << 16) |
      ((g & 0xff) << 8) |
      (b & 0xff)) >>>
    0
  );
}

/** Linearly interpolates a single channel: (1 - t) * foreground + t * background, rounded. */
export function interpolateChannel(t: number, foreground: number, background: number): number {
  return Math.round((1.0 - t) * foreground + t * background);
}

/**
 * Derives a 1-bit matte alpha from an opaque pixel using the REC601 luma weights used by
 * the C++ runtime: ((77*r) + (150*g) + (29*b) + 128) >> 8.
 */
export function maskAlphaFromPixel(pixel: number): number {
  const r = channel(pixel, 16);
  const g = channel(pixel, 8);
  const b = channel(pixel, 0);
  return ((77 * r + 150 * g + 29 * b + 128) >> 8) & 0xff;
}

/** True when the pixel's RGB matches matteRgb within per-channel tolerance. */
export function matchesRgb(pixel: number, matteRgb: number, tolerance: number): boolean {
  const pr = channel(pixel, 16);
  const pg = channel(pixel, 8);
  const pb = channel(pixel, 0);
  const mr = (matteRgb >>> 16) & 0xff;
  const mg = (matteRgb >>> 8) & 0xff;
  const mb = matteRgb & 0xff;
  return (
    Math.abs(pr - mr) <= tolerance &&
    Math.abs(pg - mg) <= tolerance &&
    Math.abs(pb - mb) <= tolerance
  );
}

/** True when an RGB color is a near-white grayscale (used to detect matte backgrounds). */
export function isNearWhiteGrayscale(colorRgb: number, minChannel: number, maxDelta: number): boolean {
  const r = (colorRgb >>> 16) & 0xff;
  const g = (colorRgb >>> 8) & 0xff;
  const b = colorRgb & 0xff;
  return (
    r >= minChannel &&
    g >= minChannel &&
    b >= minChannel &&
    Math.abs(r - g) <= maxDelta &&
    Math.abs(g - b) <= maxDelta &&
    Math.abs(r - b) <= maxDelta
  );
}

/** True when a pixel is fully opaque (alpha 0xFF) and its RGB equals colorRgb. */
export function isOpaqueColor(argb: number, colorRgb: number): boolean {
  return channel(argb, 24) === 0xff && (argb & 0x00ffffff) === (colorRgb & 0x00ffffff);
}

/** Returns just the RGB triplet (alpha stripped) of a packed ARGB pixel. */
export function rgbOf(argb: number): number {
  return argb & 0x00ffffff;
}

/** Returns the 8-bit alpha channel of a packed ARGB pixel (0..255). */
export function alphaOf(argb: number): number {
  return channel(argb, 24);
}