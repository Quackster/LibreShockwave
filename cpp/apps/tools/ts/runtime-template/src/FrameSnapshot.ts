// FrameSnapshot / RenderSprite runtime data contract.
//
// These types are the boundary between the C++ exporter (which serializes a movie's per-frame
// render state to JSON) and the TS runtime (which consumes it). They mirror the fields of the
// C++ libreshockwave::player::render::pipeline::FrameSnapshot / RenderSprite that are needed to
// reproduce a frame, minus the C++-only chunk/cast handles (replaced by asset references the
// exporter resolves). The exporter emits JSON matching FrameSnapshotJson; the runtime hydrates
// baked bitmaps from assets/ into the Bitmap fields.

import type { Bitmap } from "./Bitmap.js";
import type { InkMode } from "./InkMode.js";

export const SpriteType = {
  Bitmap: "bitmap",
  Shape: "shape",
  Text: "text",
  Button: "button",
  FilmLoop: "filmloop",
  Shockwave3D: "w3d",
  Unknown: "unknown",
} as const;

export type SpriteType = (typeof SpriteType)[keyof typeof SpriteType];

/** A single sprite ready to draw in a frame (runtime representation, post-bake). */
export interface RenderSprite {
  channel: number;
  x: number;
  y: number;
  width: number;
  height: number;
  locZ: number;
  visible: boolean;
  type: SpriteType;
  foreColor: number;
  backColor: number;
  hasForeColor: boolean;
  hasBackColor: boolean;
  ink: InkMode;
  blend: number;
  flipH: boolean;
  flipV: boolean;
  rotation: number;
  skew: number;
  /** Baked bitmap (already decoded, ink-pre-processed) or null for non-bitmap sprites. */
  bakedBitmap: Bitmap | null;
  hasBehaviors: boolean;
  shapeLineSize: number | null;
  shapePattern: number | null;
}

/** A complete frame: stage geometry + the ordered list of sprites to composite. */
export interface FrameSnapshot {
  frameNumber: number;
  stageWidth: number;
  stageHeight: number;
  backgroundColor: number;
  sprites: RenderSprite[];
  debugInfo: string;
  /** Optional pre-rendered stage image (authored backdrop). */
  stageImage: Bitmap | null;
  bakeTick: number;
}

/** JSON form emitted by the C++ exporter; baked bitmaps are referenced by asset id, not inline. */
export interface RenderSpriteJson {
  channel: number;
  x: number;
  y: number;
  width: number;
  height: number;
  locZ: number;
  visible: boolean;
  type: SpriteType;
  foreColor: number;
  backColor: number;
  hasForeColor: boolean;
  hasBackColor: boolean;
  ink: number;
  blend: number;
  flipH: boolean;
  flipV: boolean;
  rotation: number;
  skew: number;
  bakedBitmapAsset: string | null;
  hasBehaviors: boolean;
  shapeLineSize: number | null;
  shapePattern: number | null;
}

export interface FrameSnapshotJson {
  frameNumber: number;
  stageWidth: number;
  stageHeight: number;
  backgroundColor: number;
  sprites: RenderSpriteJson[];
  debugInfo: string;
  stageImageAsset: string | null;
  bakeTick: number;
}