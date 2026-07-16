// Exported score hydration.
//
// The C++ exporter writes score.json describing each frame's sprites (geometry, ink,
// blend, baked-bitmap asset ref). This module turns that JSON into the runtime's
// FrameSnapshot shape, decoding baked bitmaps via a caller-supplied loader. The loader
// is responsible for the actual bytes (fetch in the browser, fs in tests) and caching;
// buildFrameSnapshot stays synchronous and pure given a cache hit.

import type { Bitmap } from "./Bitmap.js";
import { inkModeFromCode } from "./InkMode.js";
import type { InkMode } from "./InkMode.js";
import type { FrameSnapshot, RenderSprite, SpriteType } from "./FrameSnapshot.js";

/** One sprite as emitted in score.json. bakedWidth/bakedHeight are present iff bakedBitmapAsset is set. */
export interface ScoreSpriteJson {
  channel: number;
  x: number;
  y: number;
  width: number;
  height: number;
  locZ: number;
  visible: boolean;
  type: string;
  ink: number;
  blend: number;
  flipH: boolean;
  flipV: boolean;
  rotation: number;
  skew: number;
  bakedBitmapAsset: string | null;
  bakedWidth?: number;
  bakedHeight?: number;
  /** Director cast member number, or -1 if none. */
  castMemberId?: number;
  /** Member name if the cast member has one. */
  castMemberName?: string;
  /** True if the score channel carries a behavior script. */
  hasBehaviors?: boolean;
}

export interface ScoreBehaviorJson {
  channel: number;
  castLib: number;
  castMember: number;
  /** Best-effort behavior script name; may be empty. */
  scriptName: string;
}

export interface ScoreFrameJson {
  frame: number;
  /** Effective fps for this frame (score tempo channel, else base tempo). */
  tempo?: number;
  /** Behavior scripts attached to score channels on this frame. */
  behaviors?: ScoreBehaviorJson[];
  /** Optional frame script (behavior on channel 0) for this frame. */
  frameScript?: ScoreBehaviorJson | null;
  sprites: ScoreSpriteJson[];
}

/** A frame label / marker as emitted in score.json (name -> 1-based frame). */
export interface ScoreLabelJson {
  frame: number;
  name: string;
}

/** A decoded sound cast member, as emitted in cast.json (Stage 6). */
export interface SoundMemberJson {
  name: string;
  asset: string;
  format: "wav" | "mp3";
  sampleRate: number;
  channels: number;
  bitsPerSample: number;
  durationSeconds: number;
  codec: string;
}

/** Cast member registry entry (Stage 7: name-based Lingo resolution). */
export interface CastMemberJson {
  id: number;
  castLib: number;
  name: string;
  type: string;
  bakedBitmapAsset: string | null;
  bakedWidth: number;
  bakedHeight: number;
  /** Static text content for text/field cast members (Lingo can mutate it at runtime). */
  text?: string;
  /** Film-loop cast members only: one baked asset per internal sub-frame. */
  filmLoopFrames?: string[];
}

/** cast.json shape (Stage 6: sound members; other cast metadata is enriched in later stages). */
export interface CastJson {
  note?: string;
  members: CastMemberJson[];
  sounds: SoundMemberJson[];
}

export interface ScoreJson {
  stageWidth: number;
  stageHeight: number;
  backgroundColor: number;
  frameCount: number;
  /** Total frames in the source movie (the export may be a partial slice). */
  totalFrames?: number;
  /** Base tempo (fps) from the Config chunk. */
  tempo?: number;
  /** Frame labels / markers within the exported slice, sorted by frame. */
  labels?: ScoreLabelJson[];
  frames: ScoreFrameJson[];
}

/** Resolve a baked-bitmap asset path to a decoded Bitmap, or null if absent. Caller caches. */
export type BitmapLoader = (assetPath: string) => Bitmap | null;

function coerceSpriteType(type: string): SpriteType {
  // The C++ exporter emits `rsp::name(sprite.type())`, which is UPPER_SNAKE_CASE
  // (e.g. "BITMAP", "TEXT", "SHAPE", "FILM_LOOP", "UNKNOWN"). The runtime SpriteType
  // is lowercase ("bitmap", "filmloop", ...). Normalize so the real type survives into
  // the snapshot (the compositor does not branch on type, but future stages — text
  // raster, film-loop playback, W3D — dispatch on it).
  const normalized = type.toLowerCase().replace(/_+/g, "");
  if (normalized === "shockwave3d") {
    return "w3d";
  }
  switch (normalized) {
    case "bitmap":
    case "shape":
    case "text":
    case "button":
    case "filmloop":
    case "w3d":
    case "unknown":
      return normalized;
    default:
      return "unknown";
  }
}

function makeCastLookup(members: CastMemberJson[]): Map<string, CastMemberJson> {
  const map = new Map<string, CastMemberJson>();
  for (const cm of members) {
    const byId = `${cm.castLib}:${cm.id}`;
    if (!map.has(byId)) {
      map.set(byId, cm);
    }
    if (cm.name) {
      const byName = `name:${cm.name}`;
      if (!map.has(byName)) {
        map.set(byName, cm);
      }
    }
  }
  return map;
}

function resolveFilmLoopAsset(
  json: ScoreSpriteJson,
  castLookup: Map<string, CastMemberJson>,
  bakeTick: number,
): string | null {
  const key = json.castMemberName ? `name:${json.castMemberName}` : null;
  if (!key) {
    return null;
  }
  const cm = castLookup.get(key);
  if (!cm || !cm.filmLoopFrames || cm.filmLoopFrames.length === 0) {
    return null;
  }
  const idx = bakeTick % cm.filmLoopFrames.length;
  return cm.filmLoopFrames[idx] ?? null;
}

export function buildSprite(
  json: ScoreSpriteJson,
  loadBitmap: BitmapLoader,
  castLookup: Map<string, CastMemberJson> = new Map(),
  bakeTick: number = 0,
): RenderSprite {
  const spriteType = coerceSpriteType(json.type);
  let asset = json.bakedBitmapAsset;
  // Film-loops animate by cycling through pre-baked sub-frames as the bake tick advances.
  if (spriteType === "filmloop") {
    const loopAsset = resolveFilmLoopAsset(json, castLookup, bakeTick);
    if (loopAsset) {
      asset = loopAsset;
    }
  }
  let bakedBitmap: Bitmap | null = null;
  if (asset !== null && asset !== undefined
      && json.bakedWidth !== undefined && json.bakedHeight !== undefined) {
    const decoded = loadBitmap(asset);
    if (decoded !== null) {
      bakedBitmap = decoded;
    }
  }
  const ink: InkMode = inkModeFromCode(json.ink);
  return {
    channel: json.channel,
    x: json.x,
    y: json.y,
    width: json.width,
    height: json.height,
    locZ: json.locZ,
    visible: json.visible,
    type: coerceSpriteType(json.type),
    foreColor: 0,
    backColor: 0,
    hasForeColor: false,
    hasBackColor: false,
    ink,
    blend: json.blend,
    flipH: json.flipH,
    flipV: json.flipV,
    rotation: json.rotation,
    skew: json.skew,
    bakedBitmap,
    hasBehaviors: json.hasBehaviors ?? false,
    shapeLineSize: null,
    shapePattern: null,
  };
}

/** Build the runtime FrameSnapshot for frame index `frameIndex` (0-based) of an exported score. */
export function buildFrameSnapshot(
  score: ScoreJson,
  cast: CastJson | undefined,
  frameIndex: number,
  loadBitmap: BitmapLoader,
  bakeTick: number = 0,
): FrameSnapshot {
  if (frameIndex < 0 || frameIndex >= score.frames.length) {
    throw new Error(`buildFrameSnapshot: frame index ${frameIndex} out of range (0..${score.frames.length - 1})`);
  }
  const frame = score.frames[frameIndex];
  const castLookup = cast ? makeCastLookup(cast.members) : new Map<string, CastMemberJson>();
  const sprites = frame.sprites.map((sp) => buildSprite(sp, loadBitmap, castLookup, bakeTick));
  return {
    frameNumber: frame.frame,
    stageWidth: score.stageWidth,
    stageHeight: score.stageHeight,
    backgroundColor: score.backgroundColor,
    sprites,
    debugInfo: "",
    stageImage: null,
    bakeTick,
  };
}