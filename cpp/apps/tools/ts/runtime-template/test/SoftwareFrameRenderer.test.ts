import { describe, expect, it } from "vitest";
import { Bitmap } from "../src/Bitmap.js";
import { InkMode } from "../src/InkMode.js";
import { renderFrame } from "../src/SoftwareFrameRenderer.js";
import { diffFrames } from "../src/diff.js";
import type { FrameSnapshot, RenderSprite } from "../src/FrameSnapshot.js";
import { packArgb } from "../src/argb.js";

// Stage 1 differential harness proof: build a synthetic FrameSnapshot, render it with the TS
// software compositor, and assert the output is pixel-identical to a hand-computed expected frame
// (0 diff). This proves the harness machinery end-to-end. The C++-vs-TS cross-renderer diff
// (the real parity gate) begins in Stage 2 once the exporter + RenderProbe dump mode exist.

const STAGE_W = 4;
const STAGE_H = 4;
const BG_RGB = 0x3366aa; // stage background
const OPAQUE = (rgb: number) => (0xff000000 | rgb) >>> 0;

function snapshotWith(sprites: RenderSprite[], stageImage: Bitmap | null = null): FrameSnapshot {
  return {
    frameNumber: 0,
    stageWidth: STAGE_W,
    stageHeight: STAGE_H,
    backgroundColor: BG_RGB,
    sprites,
    debugInfo: "",
    stageImage,
    bakeTick: 0,
  };
}

function bitmapSprite(opts: Partial<RenderSprite> & { x: number; y: number; w: number; h: number; bmp: Bitmap }): RenderSprite {
  return {
    channel: 1,
    x: opts.x,
    y: opts.y,
    width: opts.w,
    height: opts.h,
    locZ: opts.locZ ?? 0,
    visible: opts.visible ?? true,
    type: opts.type ?? "bitmap",
    foreColor: opts.foreColor ?? 0,
    backColor: opts.backColor ?? 0,
    hasForeColor: opts.hasForeColor ?? false,
    hasBackColor: opts.hasBackColor ?? false,
    ink: opts.ink ?? InkMode.COPY,
    blend: opts.blend ?? 100,
    flipH: opts.flipH ?? false,
    flipV: opts.flipV ?? false,
    rotation: opts.rotation ?? 0,
    skew: opts.skew ?? 0,
    bakedBitmap: opts.bmp,
    hasBehaviors: opts.hasBehaviors ?? false,
    shapeLineSize: opts.shapeLineSize ?? null,
    shapePattern: opts.shapePattern ?? null,
  };
}

describe("differential harness (synthetic, 0-diff proof)", () => {
  it("fills the stage with the opaque background and composites one opaque bitmap (COPY)", () => {
    const bmp = new Bitmap(2, 2, 32, new Uint32Array([OPAQUE(0xff0000), OPAQUE(0x00ff00), OPAQUE(0x0000ff), OPAQUE(0xffffff)]));
    const snap = snapshotWith([bitmapSprite({ x: 1, y: 1, w: 2, h: 2, bmp })]);
    const out = renderFrame(snap, STAGE_W, STAGE_H);

    const expected = new Uint32Array(STAGE_W * STAGE_H);
    expected.fill(OPAQUE(BG_RGB));
    expected[(1) * STAGE_W + 1] = OPAQUE(0xff0000);
    expected[(1) * STAGE_W + 2] = OPAQUE(0x00ff00);
    expected[(2) * STAGE_W + 1] = OPAQUE(0x0000ff);
    expected[(2) * STAGE_W + 2] = OPAQUE(0xffffff);

    const diff = diffFrames(expected, out.pixels(), 0, STAGE_W, STAGE_H);
    expect(diff).toEqual({ equal: true, diffCount: 0, maxDelta: 0, width: STAGE_W, height: STAGE_H });
  });

  it("honors locZ ordering: a later sprite paints over an earlier one", () => {
    const red = new Bitmap(1, 1, 32, new Uint32Array([OPAQUE(0xff0000)]));
    const blue = new Bitmap(1, 1, 32, new Uint32Array([OPAQUE(0x0000ff)]));
    const snap = snapshotWith([
      bitmapSprite({ x: 0, y: 0, w: 1, h: 1, bmp: red, locZ: 0 }),
      bitmapSprite({ x: 0, y: 0, w: 1, h: 1, bmp: blue, locZ: 1 }),
    ]);
    const out = renderFrame(snap, STAGE_W, STAGE_H);
    expect(out.getPixel(0, 0)).toBe(OPAQUE(0x0000ff));
  });

  it("skips fully-transparent source pixels (TRANSPARENT behavior at COPY)", () => {
    const bmp = new Bitmap(2, 1, 32, new Uint32Array([0, OPAQUE(0xff0000)]));
    const snap = snapshotWith([bitmapSprite({ x: 0, y: 0, w: 2, h: 1, bmp })]);
    const out = renderFrame(snap, STAGE_W, STAGE_H);
    expect(out.getPixel(0, 0)).toBe(OPAQUE(BG_RGB)); // transparent source left the background
    expect(out.getPixel(1, 0)).toBe(OPAQUE(0xff0000));
  });

  it("clips sprites that extend past the stage edges", () => {
    const bmp = new Bitmap(3, 3, 32);
    bmp.fill(OPAQUE(0xffffff));
    const snap = snapshotWith([bitmapSprite({ x: -1, y: -1, w: 3, h: 3, bmp })]);
    const out = renderFrame(snap, STAGE_W, STAGE_H);
    // only the (0,0)..(2,2) region is painted; corner (3,3) stays background
    expect(out.getPixel(0, 0)).toBe(OPAQUE(0xffffff));
    expect(out.getPixel(3, 3)).toBe(OPAQUE(BG_RGB));
  });

  it("BLEND at 50% blends an opaque sprite onto the opaque background", () => {
    const bmp = new Bitmap(1, 1, 32, new Uint32Array([OPAQUE(0xff0000)]));
    const snap = snapshotWith([bitmapSprite({ x: 0, y: 0, w: 1, h: 1, bmp, ink: InkMode.BLEND, blend: 50 })]);
    const out = renderFrame(snap, STAGE_W, STAGE_H);
    // opacity = (255*50*256)/(255*100) = 128; out = (src*128 + dst*128) >> 8
    const bgR = (BG_RGB >> 16) & 0xff;
    const outR = ((0xff * 128) + (bgR * (256 - 128))) >> 8;
    expect(((out.getPixel(0, 0) >> 16) & 0xff)).toBe(outR);
    expect(((out.getPixel(0, 0) >> 24) & 0xff)).toBe(0xff);
  });

  it("flipH mirrors the source columns", () => {
    const bmp = new Bitmap(2, 1, 32, new Uint32Array([OPAQUE(0xff0000), OPAQUE(0x0000ff)]));
    const snap = snapshotWith([bitmapSprite({ x: 0, y: 0, w: 2, h: 1, bmp, flipH: true })]);
    const out = renderFrame(snap, STAGE_W, STAGE_H);
    expect(out.getPixel(0, 0)).toBe(OPAQUE(0x0000ff));
    expect(out.getPixel(1, 0)).toBe(OPAQUE(0xff0000));
  });

  it("packArgb helper produces canonical ARGB packing", () => {
    expect(packArgb(0xff, 0x80, 0x40, 0x20)).toBe(0xff804020 >>> 0);
  });

  // Stage 8: Shockwave3D sprites are baked to RGBA by the C++ SpriteBaker::bakeShockwave3D and
  // shipped with a bakedBitmapAsset exactly like bitmap/shape/text/film-loop sprites. The
  // compositor is type-agnostic (it composites any sprite with a bakedBitmap), so a "w3d" sprite
  // renders identically to a "bitmap" sprite carrying the same baked pixels — this is the W3D
  // parity path (bit-exact, tolerance 0), not a Three.js re-render. Proven structurally here
  // because no real .dir fixture with a Shockwave3D cast member exists in the repo.
  it("composites a Shockwave3D (w3d) sprite through the same baked-bitmap path as a bitmap sprite", () => {
    const bmp = new Bitmap(2, 2, 32, new Uint32Array([OPAQUE(0xff0000), OPAQUE(0x00ff00), OPAQUE(0x0000ff), OPAQUE(0xffffff)]));
    const w3dSnap = snapshotWith([bitmapSprite({ x: 1, y: 1, w: 2, h: 2, bmp, type: "w3d" })]);
    const bmpSnap = snapshotWith([bitmapSprite({ x: 1, y: 1, w: 2, h: 2, bmp, type: "bitmap" })]);
    const w3dOut = renderFrame(w3dSnap, STAGE_W, STAGE_H);
    const bmpOut = renderFrame(bmpSnap, STAGE_W, STAGE_H);
    const diff = diffFrames(w3dOut.pixels(), bmpOut.pixels(), 0, STAGE_W, STAGE_H);
    expect(diff).toEqual({ equal: true, diffCount: 0, maxDelta: 0, width: STAGE_W, height: STAGE_H });
  });
});