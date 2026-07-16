import { describe, it, expect } from "vitest";
import { buildFrameSnapshot, type ScoreJson } from "../src/ScoreData.js";
import type { Bitmap } from "../src/Bitmap.js";

// buildSprite is not exported, but buildFrameSnapshot runs every sprite through
// coerceSpriteType, so we observe the normalized type via the snapshot's sprites.
function snapshotOf(types: string[]): { sprites: { type: string }[] } {
  const score: ScoreJson = {
    stageWidth: 1,
    stageHeight: 1,
    backgroundColor: 0,
    frameCount: 1,
    frames: [
      {
        frame: 1,
        sprites: types.map((t, i) => ({
          channel: i + 1,
          x: 0,
          y: 0,
          width: 0,
          height: 0,
          locZ: 0,
          visible: true,
          type: t,
          ink: 0,
          blend: 100,
          flipH: false,
          flipV: false,
          rotation: 0,
          skew: 0,
          bakedBitmapAsset: null,
        })),
      },
    ],
  };
  const noBitmap = (_p: string): Bitmap | null => null;
  return buildFrameSnapshot(score, undefined, 0, noBitmap) as unknown as { sprites: { type: string }[] };
}

describe("coerceSpriteType", () => {
  it("maps the C++ exporter's UPPER_SNAKE_CASE type names to runtime SpriteType values", () => {
    const got = snapshotOf([
      "BITMAP",
      "SHAPE",
      "TEXT",
      "BUTTON",
      "FILM_LOOP",
      "SHOCKWAVE_3D",
      "UNKNOWN",
    ]).sprites.map((s) => s.type);
    expect(got).toEqual(["bitmap", "shape", "text", "button", "filmloop", "w3d", "unknown"]);
  });

  it("falls back to unknown for unrecognized types", () => {
    expect(snapshotOf(["nonsense"]).sprites[0].type).toBe("unknown");
  });
});