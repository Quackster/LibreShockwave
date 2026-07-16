// Self-contained differential harness for an exported project.
//
// Runs from the exported project root (`npm test`). It composites each frame with the
// bundled TS runtime (src/runtime/) and compares pixel-for-pixel against the C++ reference
// frames the exporter shipped under assets/reference/. This is the parity oracle proving
// the hand-written TS runtime matches the C++ SoftwareFrameRenderer for the sprite types
// and inks present in this movie. Tolerance is 1 LSB (blend inks); the runtime replicates
// C++ integer-division truncation, so non-blend frames are typically bit-exact.

import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, it, expect } from "vitest";

import { renderFrame } from "../src/runtime/SoftwareFrameRenderer.js";
import { decodeRgba, decodeRgbaToBitmap } from "../src/runtime/RgbaAsset.js";
import { buildFrameSnapshot, type ScoreJson } from "../src/runtime/ScoreData.js";
import { diffFrames } from "../src/runtime/diff.js";
import type { Bitmap } from "../src/runtime/Bitmap.js";

// The exporter drops score.json + assets/ at the project root; vitest runs from there.
const root = process.cwd();
const score: ScoreJson = JSON.parse(readFileSync(resolve(root, "score.json"), "utf8"));

// Cache raw asset bytes so each bitmap file is read once across frames (the exporter
// dedups identical baked bitmaps to a single asset file).
const bitmapBytes = new Map<string, Uint8Array>();
function loadBytes(assetPath: string): Uint8Array {
  const cached = bitmapBytes.get(assetPath);
  if (cached) {
    return cached;
  }
  const bytes = new Uint8Array(readFileSync(resolve(root, assetPath)));
  bitmapBytes.set(assetPath, bytes);
  return bytes;
}

// Pre-build every referenced bitmap (decode once per asset), then hand back cached
// Bitmaps to buildFrameSnapshot by asset path.
const bitmapCache = new Map<string, Bitmap>();
for (const frame of score.frames) {
  for (const sprite of frame.sprites) {
    if (sprite.bakedBitmapAsset && sprite.bakedWidth !== undefined && sprite.bakedHeight !== undefined) {
      if (!bitmapCache.has(sprite.bakedBitmapAsset)) {
        bitmapCache.set(
          sprite.bakedBitmapAsset,
          decodeRgbaToBitmap(loadBytes(sprite.bakedBitmapAsset), sprite.bakedWidth, sprite.bakedHeight),
        );
      }
    }
  }
}
const loadBitmap = (assetPath: string): Bitmap | null => bitmapCache.get(assetPath) ?? null;

describe("differential vs C++ reference frames", () => {
  it("score has frames with baked bitmaps to validate against", () => {
    const bakedFrames = score.frames.filter((f) => f.sprites.some((s) => s.bakedBitmapAsset));
    expect(bakedFrames.length).toBeGreaterThan(0);
  });

  for (const frame of score.frames) {
    it(`frame ${frame.frame} matches C++ reference`, () => {
      const snapshot = buildFrameSnapshot(score, undefined, score.frames.indexOf(frame), loadBitmap);
      const rendered = renderFrame(snapshot, score.stageWidth, score.stageHeight);
      const refBytes = new Uint8Array(readFileSync(resolve(root, "assets", "reference", `f${frame.frame}.rgba`)));
      const expected = decodeRgba(refBytes, score.stageWidth, score.stageHeight);
      const actual = rendered.pixels();

      const result = diffFrames(expected, actual, 1, score.stageWidth, score.stageHeight);
      if (!result.equal) {
        let sample = -1;
        for (let i = 0; i < expected.length; ++i) {
          const a = expected[i] >>> 0;
          const b = actual[i] >>> 0;
          const d = Math.max(
            Math.abs(((a >>> 24) & 0xff) - ((b >>> 24) & 0xff)),
            Math.abs(((a >>> 16) & 0xff) - ((b >>> 16) & 0xff)),
            Math.abs(((a >>> 8) & 0xff) - ((b >>> 8) & 0xff)),
            Math.abs((a & 0xff) - (b & 0xff)),
          );
          if (d > 1) {
            sample = i;
            break;
          }
        }
        const px = sample >= 0
          ? ` @idx ${sample} (x=${sample % score.stageWidth},y=${Math.floor(sample / score.stageWidth)}) expected=0x${(expected[sample] >>> 0).toString(16).padStart(8, "0")} actual=0x${(actual[sample] >>> 0).toString(16).padStart(8, "0")}`
          : "";
        expect(result, `frame ${frame.frame}: ${result.diffCount}/${expected.length} pixels differ, maxDelta=${result.maxDelta}${px}`).toEqual({
          equal: true,
          diffCount: 0,
          maxDelta: 0,
          width: score.stageWidth,
          height: score.stageHeight,
        });
      }
      expect(result.equal).toBe(true);
    });
  }
});