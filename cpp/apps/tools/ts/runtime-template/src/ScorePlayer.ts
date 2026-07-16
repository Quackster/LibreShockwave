// Score playback metadata: tempo resolution and frame-label / marker navigation.
//
// Stage 5. The C++ exporter ships, alongside the per-frame sprite data, the movie's base
// tempo (Config chunk), a per-frame effective tempo (the score tempo channel — sticky from
// the most recent entry at or before the frame — else the base tempo; this mirrors C++
// `Player::tempo()` without Ligo, where puppetTempo is 0), and the frame labels / markers
// within the exported slice (in Director the label set and the marker set are the same —
// `ScoreNavigator` populates both from the VWLB frame-labels chunk).
//
// This module is pure logic over ScoreJson — no rendering, no PixiJS — so it lives in the
// shared runtime and is unit-tested directly. The export-template's main.ts uses it to drive
// playback at the movie's real frame rate and to jump to labels.

import type { ScoreJson, ScoreLabelJson } from "./ScoreData.js";

/** Fallback fps when a movie carries no tempo at all (Director's own default is 15). */
export const DEFAULT_TEMPO = 15;

export class ScorePlayer {
  private readonly score: ScoreJson;

  constructor(score: ScoreJson) {
    this.score = score;
  }

  /** Number of frames in the exported slice (0-based index range is [0, frameCount)). */
  get frameCount(): number {
    return this.score.frames.length;
  }

  /** Total frames in the source movie (the export may be a partial slice). */
  get totalFrames(): number {
    return this.score.totalFrames ?? this.score.frameCount;
  }

  /** Base tempo (fps) from the Config chunk, falling back to the Director default. */
  get baseTempo(): number {
    return this.score.tempo ?? DEFAULT_TEMPO;
  }

  /**
   * Effective fps for the frame at `frameIndex` (0-based into score.frames). Uses the
   * per-frame tempo the exporter resolved (score tempo channel, else base tempo), falling
   * back to the base tempo and then the default if absent. Matches C++ `Player::tempo()`
   * for the no-Lingo static export.
   */
  effectiveTempo(frameIndex: number): number {
    const frame = this.score.frames[frameIndex];
    if (frame && typeof frame.tempo === "number" && frame.tempo > 0) {
      return frame.tempo;
    }
    return this.baseTempo;
  }

  /** Milliseconds to dwell on the frame at `frameIndex` at its effective tempo. */
  frameDelayMs(frameIndex: number): number {
    const fps = this.effectiveTempo(frameIndex);
    return 1000 / fps;
  }

  /** Frame labels / markers within the exported slice, sorted by frame. */
  labels(): ScoreLabelJson[] {
    return this.score.labels ?? [];
  }

  /** Case-insensitive label lookup -> 1-based frame number, or null if not found. */
  frameForLabel(name: string): number | null {
    const needle = name.toLowerCase();
    for (const lbl of this.labels()) {
      if (lbl.name.toLowerCase() === needle) {
        return lbl.frame;
      }
    }
    return null;
  }

  /** 0-based index into score.frames for a label, or null if not found / out of range. */
  indexForLabel(name: string): number | null {
    const frame = this.frameForLabel(name);
    if (frame === null) {
      return null;
    }
    return this.indexForFrame(frame);
  }

  /** Convert a 1-based frame number to a 0-based index into score.frames, or null if absent. */
  indexForFrame(frame: number): number | null {
    for (let i = 0; i < this.score.frames.length; ++i) {
      if (this.score.frames[i].frame === frame) {
        return i;
      }
    }
    return null;
  }

  /** The label at a given 1-based frame, or null if no label is anchored there. */
  labelForFrame(frame: number): string | null {
    for (const lbl of this.labels()) {
      if (lbl.frame === frame) {
        return lbl.name;
      }
    }
    return null;
  }
}