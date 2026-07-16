// Web Audio playback of exported sound cast members.
//
// Stage 6. Director sound playback is Lingo-driven: there is no score sound channel and no
// parsed cue points, so a static (no-Lingo) export cannot capture *when* sounds play — that
// arrives with the emitted Lingo in Stage 7 (e.g. `sound(1).play(member("beep"))`). What the
// exporter CAN ship statically is the sound ASSETS (decoded to WAV/MP3 via the same
// SoundManager path the C++ player uses) plus their metadata, listed in cast.json. This
// module loads those assets once into AudioBuffers and exposes a `play(name)` API that the
// emitted Lingo-TS (and the UI) calls — the single source of truth for "play this sound".
//
// Audio is not pixel-diffed. The parity contract here is structural: the same sound members
// the C++ player would play are present, decoded, and playable by name.

import type { SoundMemberJson } from "./ScoreData.js";

export interface PlayOptions {
  /** 0..1, default 1. */
  volume?: number;
  /** Number of extra full loops after the first play (0 = play once). */
  loopCount?: number;
  /** Delay before playback, in seconds. */
  when?: number;
}

export interface PlayingHandle {
  /** Stop the sound early. */
  stop: () => void;
}

/**
 * Loads and plays the sound assets listed in cast.json. Browser-only (Web Audio). Construct
 * with the sound list (from cast.json) and an optional existing AudioContext; call
 * `preload()` after user interaction (browsers require a gesture to start audio), then
 * `play(name)`.
 */
export class AudioPlayer {
  private readonly ctx: AudioContext;
  private readonly sounds: readonly SoundMemberJson[];
  private readonly buffers = new Map<string, AudioBuffer>();
  private readonly assetBase: string;

  constructor(sounds: readonly SoundMemberJson[], ctx?: AudioContext, assetBase = "") {
    this.ctx = ctx ?? new AudioContext();
    this.sounds = sounds;
    this.assetBase = assetBase;
  }

  /** The sound members this player knows about (from cast.json). */
  get soundList(): readonly SoundMemberJson[] {
    return this.sounds;
  }

  /** Names of all known sounds. */
  names(): string[] {
    return this.sounds.map((s) => s.name);
  }

  /** True once a given sound's AudioBuffer is decoded and cached. */
  isLoaded(name: string): boolean {
    return this.buffers.has(name);
  }

  /** The underlying AudioContext (call `ctx.resume()` after a user gesture if suspended). */
  get audioContext(): AudioContext {
    return this.ctx;
  }

  /**
   * Fetch + decode every known sound into AudioBuffers. Web Audio's decodeAudioData handles
   * both WAV and MP3 bytes. Returns the names that decoded successfully. Safe to call once
   * (cached); re-call to retry missing ones.
   */
  async preload(): Promise<string[]> {
    const loaded: string[] = [];
    for (const s of this.sounds) {
      if (this.buffers.has(s.name)) {
        loaded.push(s.name);
        continue;
      }
      try {
        const url = this.assetBase + s.asset;
        const res = await fetch(url);
        if (!res.ok) {
          continue;
        }
        const arr = await res.arrayBuffer();
        const buf = await this.ctx.decodeAudioData(arr);
        this.buffers.set(s.name, buf);
        loaded.push(s.name);
      } catch {
        // A sound that fails to decode (unsupported codec, missing file) is skipped, not fatal.
      }
    }
    return loaded;
  }

  /**
   * Play a sound by name. Returns a handle to stop it early, or null if the sound is unknown
   * or not yet loaded (call `preload()` first). `loopCount` extra loops are achieved by
   * setting `loop = true` for the play duration when loopCount > 0 — Director sound loops
   * are whole-buffer repeats, matching this.
   */
  play(name: string, opts: PlayOptions = {}): PlayingHandle | null {
    const buf = this.buffers.get(name);
    if (!buf) {
      return null;
    }
    const src = this.ctx.createBufferSource();
    src.buffer = buf;
    const volume = clamp01(opts.volume ?? 1);
    const when = this.ctx.currentTime + (opts.when ?? 0);
    if (opts.loopCount && opts.loopCount > 0) {
      src.loop = true;
    }
    if (volume < 1) {
      const gain = this.ctx.createGain();
      gain.gain.value = volume;
      src.connect(gain).connect(this.ctx.destination);
    } else {
      src.connect(this.ctx.destination);
    }
    src.start(when);
    if (opts.loopCount && opts.loopCount > 0) {
      // Schedule the source to stop after (1 + loopCount) full durations so it does not loop
      // forever (Director loopCount is a finite repeat count).
      const totalSeconds = buf.duration * (1 + opts.loopCount);
      src.stop(when + totalSeconds);
    }
    return {
      stop: () => {
        try {
          src.stop();
        } catch {
          // Already stopped — ignore.
        }
      },
    };
  }

  /** Stop nothing globally (each handle stops itself); included for API symmetry. */
  stopAll(): void {
    // Per-handle stop is authoritative; there is no global node list to tear down.
  }
}

function clamp01(v: number): number {
  if (v < 0) {
    return 0;
  }
  if (v > 1) {
    return 1;
  }
  return v;
}