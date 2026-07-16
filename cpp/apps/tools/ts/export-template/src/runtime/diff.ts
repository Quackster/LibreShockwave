// Frame diff machinery for the differential harness. Compares two ARGB frame buffers
// (Uint32Array) pixel by pixel. `tolerance` is the maximum per-channel delta allowed; the
// C++ vs TS renderers are compared exact for non-blend inks and with a small tolerance for
// blend inks (floating-point compositing rounding).

export interface DiffResult {
  equal: boolean;
  diffCount: number;
  maxDelta: number;
  width: number;
  height: number;
}

function channelDelta(a: number, b: number, shift: number): number {
  return Math.abs(((a >>> shift) & 0xff) - ((b >>> shift) & 0xff));
}

export function pixelDelta(a: number, b: number): number {
  return Math.max(
    channelDelta(a, b, 24),
    channelDelta(a, b, 16),
    channelDelta(a, b, 8),
    channelDelta(a, b, 0),
  );
}

/**
 * Compares two packed-ARGB frame buffers. Returns equal=true when every pixel's per-channel
 * delta is within `tolerance`. Buffers must have equal length.
 */
export function diffFrames(expected: Uint32Array, actual: Uint32Array, tolerance = 0, width = 0, height = 0): DiffResult {
  if (expected.length !== actual.length) {
    return { equal: false, diffCount: Math.abs(expected.length - actual.length), maxDelta: Number.MAX_SAFE_INTEGER, width, height };
  }
  let diffCount = 0;
  let maxDelta = 0;
  for (let i = 0; i < expected.length; ++i) {
    const delta = pixelDelta(expected[i], actual[i]);
    if (delta > tolerance) {
      ++diffCount;
      if (delta > maxDelta) {
        maxDelta = delta;
      }
    }
  }
  return { equal: diffCount === 0, diffCount, maxDelta, width, height };
}