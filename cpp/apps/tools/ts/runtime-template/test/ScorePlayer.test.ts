import { describe, it, expect } from "vitest";
import { ScorePlayer, DEFAULT_TEMPO } from "../src/ScorePlayer.js";
import type { ScoreJson } from "../src/ScoreData.js";

function score(partial: Partial<ScoreJson> & { frames: ScoreJson["frames"] }): ScoreJson {
  return {
    stageWidth: 8,
    stageHeight: 8,
    backgroundColor: 0,
    frameCount: partial.frames.length,
    ...partial,
  };
}

describe("ScorePlayer", () => {
  it("uses per-frame tempo when present, else base tempo, else the default", () => {
    const s = score({
      tempo: 30,
      frames: [
        { frame: 1, tempo: 10, sprites: [] },
        { frame: 2, sprites: [] }, // falls back to base (30)
      ],
    });
    const p = new ScorePlayer(s);
    expect(p.effectiveTempo(0)).toBe(10);
    expect(p.effectiveTempo(1)).toBe(30);
    expect(p.frameDelayMs(0)).toBe(100); // 1000 / 10
    expect(p.frameDelayMs(1)).toBeCloseTo(1000 / 30, 5);
  });

  it("falls back to DEFAULT_TEMPO when no tempo is carried at all", () => {
    const s = score({ frames: [{ frame: 1, sprites: [] }] });
    const p = new ScorePlayer(s);
    expect(p.baseTempo).toBe(DEFAULT_TEMPO);
    expect(p.effectiveTempo(0)).toBe(DEFAULT_TEMPO);
  });

  it("ignores a non-positive per-frame tempo and falls back to base", () => {
    const s = score({
      tempo: 20,
      frames: [{ frame: 1, tempo: 0, sprites: [] }],
    });
    const p = new ScorePlayer(s);
    expect(p.effectiveTempo(0)).toBe(20);
  });

  it("resolves labels case-insensitively to 1-based frame and 0-based index", () => {
    const s = score({
      frames: [
        { frame: 1, sprites: [] },
        { frame: 5, sprites: [] },
        { frame: 9, sprites: [] },
      ],
      labels: [
        { frame: 1, name: "start" },
        { frame: 5, name: "Menu" },
        { frame: 9, name: "credits" },
      ],
    });
    const p = new ScorePlayer(s);
    expect(p.frameForLabel("START")).toBe(1);
    expect(p.frameForLabel("menu")).toBe(5);
    expect(p.indexForLabel("credits")).toBe(2);
    expect(p.frameForLabel("missing")).toBeNull();
    expect(p.labelForFrame(5)).toBe("Menu");
    expect(p.labelForFrame(2)).toBeNull();
  });

  it("totalFrames defaults to frameCount when not carried", () => {
    const s = score({ frames: [{ frame: 1, sprites: [] }, { frame: 2, sprites: [] }] });
    const p = new ScorePlayer(s);
    expect(p.frameCount).toBe(2);
    expect(p.totalFrames).toBe(2);
  });
});