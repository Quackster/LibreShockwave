// Differential harness: composite each exported frame with the TS runtime and
// compare pixel-for-pixel against the C++ reference frame the exporter shipped.
//
// This is the parity oracle for the hand-written TS runtime. It is driven entirely
// by data the C++ exporter produced: score.json + assets/reference/*.rgba (the C++
// SoftwareFrameRenderer output) + assets/bitmaps/*.rgba (the C++ SpriteBaker output).
// The only variable under test is the TS compositor (renderFrame), so a pass proves
// the TS re-implementation matches the C++ renderer for the sprite types / inks present.
//
// Point it at an export with: LSDIFF_EXPORT_DIR=/path/to/export npm test
// Skipped when LSDIFF_EXPORT_DIR is unset (e.g. plain runtime-template unit runs).

import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, it, expect } from "vitest";

import { renderFrame } from "../src/SoftwareFrameRenderer.js";
import { decodeRgba, decodeRgbaToBitmap } from "../src/RgbaAsset.js";
import { buildFrameSnapshot, type ScoreJson } from "../src/ScoreData.js";
import { diffFrames } from "../src/diff.js";
import type { Bitmap } from "../src/Bitmap.js";

const exportDir = process.env.LSDIFF_EXPORT_DIR ?? "";
const skip = exportDir.length === 0;

function readJson<T>(path: string): T {
  return JSON.parse(readFileSync(path, "utf8")) as T;
}

describe.skipIf(skip)("differential vs C++ reference frames", () => {
  const root = exportDir;
  // describe.skipIf skips the tests, but the describe body still runs at collection
  // time — so guard the file reads. When skipped, score is null and the `it` blocks
  // (which reference it) never execute.
  const score: ScoreJson | null = skip ? null : readJson<ScoreJson>(resolve(root, "score.json"));

  // Cache raw asset bytes so each bitmap file is read once even across frames; the
  // exporter dedups identical baked bitmaps to a single asset file.
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

  // buildFrameSnapshot takes a single loader keyed by asset path; pre-build every referenced
  // bitmap (decode once per asset) and hand back the cached Bitmap.
  const bitmapCache = new Map<string, Bitmap>();
  const loadBitmap = (assetPath: string): Bitmap | null => bitmapCache.get(assetPath) ?? null;

  // Pre-build every bitmap referenced by every frame (decode once per asset).
  if (score !== null) {
    for (const frame of score.frames) {
      for (const sprite of frame.sprites) {
        if (sprite.bakedBitmapAsset && sprite.bakedWidth !== undefined && sprite.bakedHeight !== undefined) {
          if (!bitmapCache.has(sprite.bakedBitmapAsset)) {
            const bytes = loadBytes(sprite.bakedBitmapAsset);
            bitmapCache.set(
              sprite.bakedBitmapAsset,
              decodeRgbaToBitmap(bytes, sprite.bakedWidth, sprite.bakedHeight),
            );
          }
        }
      }
    }
  }

  it("score has frames with baked bitmaps to validate against", () => {
    const frames = score!.frames;
    const bakedFrames = frames.filter((f) => f.sprites.some((s) => s.bakedBitmapAsset));
    expect(bakedFrames.length).toBeGreaterThan(0);
  });

  for (const frame of score?.frames ?? []) {
    it(`frame ${frame.frame} matches C++ reference`, () => {
      const s = score!;
      const snapshot = buildFrameSnapshot(s, undefined, s.frames.indexOf(frame), loadBitmap);
      const rendered = renderFrame(snapshot, s.stageWidth, s.stageHeight);
      const refPath = resolve(root, "assets", "reference", `f${frame.frame}.rgba`);
      const refBytes = new Uint8Array(readFileSync(refPath));
      const expected = decodeRgba(refBytes, s.stageWidth, s.stageHeight);
      const actual = rendered.pixels();

      const result = diffFrames(expected, actual, 1, s.stageWidth, s.stageHeight);
      if (!result.equal) {
        // Surface a concise diagnostic: how many pixels, worst channel delta, and a
        // representative mismatch so failures are debuggable without a pixel dump.
        let sample = -1;
        for (let i = 0; i < expected.length; ++i) {
          const a = expected[i] >>> 0;
          const b = actual[i] >>> 0;
          const da = Math.abs(((a >>> 24) & 0xff) - ((b >>> 24) & 0xff));
          const dr = Math.abs(((a >>> 16) & 0xff) - ((b >>> 16) & 0xff));
          const dg = Math.abs(((a >>> 8) & 0xff) - ((b >>> 8) & 0xff));
          const db = Math.abs((a & 0xff) - (b & 0xff));
          if (Math.max(da, dr, dg, db) > 1) {
            sample = i;
            break;
          }
        }
        const px = sample >= 0 ? ` @idx ${sample} (x=${sample % s.stageWidth},y=${Math.floor(sample / s.stageWidth)}) expected=0x${(expected[sample] >>> 0).toString(16).padStart(8, "0")} actual=0x${(actual[sample] >>> 0).toString(16).padStart(8, "0")}` : "";
        expect(result, `frame ${frame.frame}: ${result.diffCount}/${expected.length} pixels differ, maxDelta=${result.maxDelta}${px}`).toEqual({
          equal: true,
          diffCount: 0,
          maxDelta: 0,
          width: s.stageWidth,
          height: s.stageHeight,
        });
      }
      expect(result.equal).toBe(true);
    });
  }
});