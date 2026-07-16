// Entry point for an exported Director movie.
//
// Loads manifest.json + score.json + the baked-bitmap assets the C++ exporter shipped,
// then plays the score back through the bundled runtime (src/runtime/, copied verbatim from
// the LibreShockwave ts/runtime-template/ source of truth) and displays each composited
// frame via PixiJS.
//
// Stage 2 rendering path: composite the whole frame in software with the runtime's
// renderFrame (the same code the differential harness proves bit-exact against the C++
// reference) and upload that ARGB buffer to a single PixiJS texture. The per-sprite PixiJS
// Sprite + blend-mode path (SnapshotContainer / InkFilters) arrives in Stage 3; this MVP
// guarantees the on-screen pixels match the C++ renderer because it uses the same compositor.
//
// Stage 7: Lingo handlers are fully transpiled to TypeScript under src/scripts/ and executed
// live in the browser by the LingoRuntimeHost. Handlers mutate the live FrameSnapshot before
// each frame is composited, matching the C++ player's Lingo-driven behavior.

import { Application, Sprite, Texture } from "pixi.js";

import * as runtime from "./runtime/index.js";
import { renderFrame } from "./runtime/SoftwareFrameRenderer.js";
import { buildFrameSnapshot, type CastJson, type ScoreBehaviorJson, type ScoreFrameJson, type ScoreJson, type ScoreSpriteJson } from "./runtime/ScoreData.js";
import { ScorePlayer } from "./runtime/ScorePlayer.js";
import { AudioPlayer } from "./runtime/AudioPlayer.js";
import { decodeRgbaToBitmap } from "./runtime/RgbaAsset.js";
import type { Bitmap } from "./runtime/Bitmap.js";
import { LingoRuntimeHost, setLingoHost } from "./runtime/index.js";

interface ManifestScript {
  name: string;
  type: string;
  file: string;
  handlerCount: number;
  events: string[];
}

interface Manifest {
  runtimeVersion: string;
  stage: { width: number; height: number; backgroundColor: number };
  frameCount: number;
  totalFrames: number;
  soundCount?: number;
  scriptCount?: number;
  scripts?: ManifestScript[];
}

interface ScriptModule {
  scriptName: string;
  scriptType: string;
  castLib: number;
  castMember: number;
  handlers: { name: string; args: string[]; event: string | null }[];
  handlerStubs: Record<string, (...args: unknown[]) => runtime.LingoValue | void>;
}

async function fetchJson<T>(url: string): Promise<T> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Failed to load ${url}: ${res.status}`);
  }
  return (await res.json()) as T;
}

async function fetchBytes(url: string): Promise<Uint8Array> {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`Failed to load ${url}: ${res.status}`);
  }
  return new Uint8Array(await res.arrayBuffer());
}

// Decode every referenced baked bitmap once and cache by asset path. The exporter dedups
// identical baked bitmaps to a single asset file, so this caches the shared ones.
async function loadBitmapCache(score: ScoreJson, cast?: CastJson): Promise<Map<string, Bitmap>> {
  const cache = new Map<string, Bitmap>();
  const cacheAsset = async (asset: string | null | undefined, width?: number, height?: number) => {
    if (!asset || width === undefined || height === undefined || cache.has(asset)) {
      return;
    }
    const bytes = await fetchBytes(asset);
    cache.set(asset, decodeRgbaToBitmap(bytes, width, height));
  };
  for (const frame of score.frames) {
    for (const sprite of frame.sprites) {
      await cacheAsset(sprite.bakedBitmapAsset, sprite.bakedWidth, sprite.bakedHeight);
    }
  }
  if (cast) {
    for (const cm of cast.members) {
      await cacheAsset(cm.bakedBitmapAsset, cm.bakedWidth, cm.bakedHeight);
      if (cm.filmLoopFrames) {
        for (const asset of cm.filmLoopFrames) {
          await cacheAsset(asset, cm.bakedWidth, cm.bakedHeight);
        }
      }
    }
  }
  return cache;
}

void (async () => {
  const statusEl = document.getElementById("status");

  try {
    const manifest = await fetchJson<Manifest>("/manifest.json");
    if (manifest.runtimeVersion !== runtime.RUNTIME_VERSION) {
      console.warn(`Runtime version mismatch: manifest=${manifest.runtimeVersion} runtime=${runtime.RUNTIME_VERSION}`);
    }

    const score = await fetchJson<ScoreJson>("/score.json");
    const cast = await fetchJson<CastJson>("/cast.json");
    const bitmapCache = await loadBitmapCache(score, cast);
    const loadBitmap = (assetPath: string): Bitmap | null => bitmapCache.get(assetPath) ?? null;
    const audio = new AudioPlayer(cast.sounds ?? []);
    let audioPrimed = false;
    async function primeAudio(): Promise<void> {
      if (audioPrimed) {
        return;
      }
      audioPrimed = true;
      try {
        await audio.audioContext.resume();
        await audio.preload();
      } catch {
        // Audio is best-effort; a failure to prime never breaks playback of frames.
      }
    }
    // Expose for the emitted Lingo-TS (Stage 7) and the UI.
    (window as unknown as { __lsAudio?: unknown }).__lsAudio = audio;

    const { width, height, backgroundColor } = manifest.stage;

    // Offscreen canvas the runtime paints into (RGBA ImageData), then a PixiJS texture
    // sourced from that canvas. Uploading the whole composited frame keeps the displayed
    // pixels identical to the runtime's bit-exact compositing.
    const stageCanvas = document.createElement("canvas");
    stageCanvas.width = width;
    stageCanvas.height = height;
    const ctx = stageCanvas.getContext("2d", { willReadFrequently: true });
    if (!ctx) {
      throw new Error("Unable to acquire 2D context for stage canvas");
    }
    // Bind a non-null const so the paint closure keeps a narrowed type.
    const g = ctx;
    const imageData = g.createImageData(width, height);

    const app = new Application();
    await app.init({ width, height, backgroundColor: "#000000", antialias: false });
    const host = document.getElementById("stage-host");
    if (host) {
      host.appendChild(app.canvas);
    } else {
      document.body.appendChild(app.canvas);
    }

    const texture = Texture.from(stageCanvas);
    const sprite = new Sprite(texture);
    app.stage.addChild(sprite);

    // Stage 7: wire the Lingo execution host so transpiled handlers run against the live score.
    const player = new ScorePlayer(score);
    const lingoHost = new LingoRuntimeHost({ score, loadBitmap, player, audio, cast });
    setLingoHost(lingoHost);

    // Load emitted Lingo script modules and build event-dispatch tables. Each module exports a
    // `handlerStubs` record keyed by handler name; handlers whose `event` field is non-null are
    // the Director system-event handlers (enterFrame, exitFrame, beginSprite, ...).
    const loadedScripts: ScriptModule[] = [];
    const scriptsByName: Map<string, ScriptModule> = new Map();
    const scriptsByCastMember: Map<string, ScriptModule> = new Map();
    for (const scriptInfo of manifest.scripts ?? []) {
      try {
        const mod = (await import(/* @vite-ignore */ `/${scriptInfo.file}`)) as ScriptModule;
        loadedScripts.push(mod);
        scriptsByName.set(mod.scriptName, mod);
        const key = `${mod.castLib}:${mod.castMember}`;
        if (mod.castLib > 0 || mod.castMember > 0) {
          scriptsByCastMember.set(key, mod);
        }
      } catch (err) {
        console.warn(`Failed to load Lingo script ${scriptInfo.file}:`, err);
      }
    }

    // Register every handler as a global function so cross-script calls (e.g.
    // `sprMan_getPuppetSprite()`, `getWorldCoordinate(...)`) resolve without the emitting
    // transpiler needing to know which script owns which handler.
    for (const mod of loadedScripts) {
      for (const handler of mod.handlers) {
        const stub = mod.handlerStubs[handler.name];
        if (stub) {
          (globalThis as unknown as Record<string, typeof stub>)[handler.name] = stub;
        }
      }
    }

    function behaviorKey(bc: NonNullable<ScoreJson["frames"][0]["behaviors"]>[0]): string {
      return `${bc.channel}:${bc.castLib}:${bc.castMember}`;
    }

    function castKey(castLib: number, castMember: number): string {
      return `${castLib}:${castMember}`;
    }

    function castKeyForBehavior(bc: ScoreBehaviorJson): string {
      return castKey(bc.castLib, bc.castMember);
    }

    // Persistent behavior instances per score channel so beginSprite state survives into later
    // enterFrame / sendSprite calls. BehaviorManager in C++ keeps one instance per channel+script.
    const behaviorInstances = new Map<string, ReturnType<LingoRuntimeHost["createMe"]>>();

    // Current 0-based score frame index. Mutable by lifecycle and keyboard navigation.
    let frameIndex = 0;

    function getOrCreateBehaviorInstance(
      bc: NonNullable<ScoreJson["frames"][0]["behaviors"]>[0],
      mod: ScriptModule,
    ): ReturnType<LingoRuntimeHost["createMe"]> {
      const key = behaviorKey(bc);
      let me = behaviorInstances.get(key);
      if (!me) {
        me = lingoHost.createMe(bc.channel);
        me.props.set("__scriptName", mod.scriptName);
        behaviorInstances.set(key, me);
      }
      return me;
    }

    function findModuleForBehavior(bc: ScoreBehaviorJson): ScriptModule | undefined {
      return scriptsByCastMember.get(castKeyForBehavior(bc));
    }

    function dispatchBehaviorEvent(
      bc: ScoreBehaviorJson,
      event: string,
      ...args: unknown[]
    ): void {
      const mod = findModuleForBehavior(bc);
      if (!mod) {
        return;
      }
      for (const handler of mod.handlers) {
        if (handler.event === event) {
          const stub = mod.handlerStubs[handler.name];
          if (!stub) {
            continue;
          }
          try {
            stub(getOrCreateBehaviorInstance(bc, mod), ...args);
          } catch (e) {
            console.warn(
              `Lingo handler ${mod.scriptName}.${handler.name} (${event}, channel ${bc.channel}) failed:`,
              e,
            );
          }
        }
      }
    }

    function dispatchInstanceEvent(
      me: ReturnType<LingoRuntimeHost["createMe"]>,
      event: string,
      ...args: unknown[]
    ): unknown {
      const scriptName = me.props.get("__scriptName");
      if (typeof scriptName !== "string") {
        return undefined;
      }
      const mod = scriptsByName.get(scriptName);
      if (!mod) {
        return undefined;
      }
      const handler = mod.handlers.find((h) => h.name === event);
      if (!handler) {
        return undefined;
      }
      const stub = mod.handlerStubs[handler.name];
      if (!stub) {
        return undefined;
      }
      try {
        return stub(me, ...args);
      } catch (e) {
        console.warn(`Lingo instance event ${scriptName}.${event} failed:`, e);
        return undefined;
      }
    }

    // Manual `beginSprite(o)` / `endSprite(o)` / `enterFrame(o)` / `exitFrame(o)` calls on script
    // instances (parent scripts) must dispatch to the script that created the object.
    (globalThis as unknown as Record<string, (me: ReturnType<LingoRuntimeHost["createMe"]>, ...args: unknown[]) => unknown>).beginSprite =
      (me, ...args) => dispatchInstanceEvent(me, "beginSprite", ...args);
    (globalThis as unknown as Record<string, (me: ReturnType<LingoRuntimeHost["createMe"]>, ...args: unknown[]) => unknown>).endSprite =
      (me, ...args) => dispatchInstanceEvent(me, "endSprite", ...args);
    (globalThis as unknown as Record<string, (me: ReturnType<LingoRuntimeHost["createMe"]>, ...args: unknown[]) => unknown>).enterFrame =
      (me, ...args) => dispatchInstanceEvent(me, "enterFrame", ...args);
    (globalThis as unknown as Record<string, (me: ReturnType<LingoRuntimeHost["createMe"]>, ...args: unknown[]) => unknown>).exitFrame =
      (me, ...args) => dispatchInstanceEvent(me, "exitFrame", ...args);

    // Wire runtime `sendSprite` and `new(script(...), ...)` to the loaded script modules.
    lingoHost.setSpriteDispatcher((channel, symbolName, args) => {
      const frameNumber = lingoHost.snapshot?.frameNumber ?? 1;
      const frame = score.frames[frameNumber - 1];
      const bc = frame?.behaviors?.find((b) => b.channel === channel);
      if (!bc) {
        return undefined;
      }
      const mod = scriptsByCastMember.get(castKeyForBehavior(bc));
      if (!mod) {
        return undefined;
      }
      const handler = mod.handlers.find((h) => h.name === symbolName);
      if (!handler) {
        return undefined;
      }
      const stub = mod.handlerStubs[handler.name];
      if (!stub) {
        return undefined;
      }
      const me = getOrCreateBehaviorInstance(bc, mod);
      try {
        return stub(me, ...args) as runtime.LingoValue;
      } catch (e) {
        console.warn(`sendSprite ${mod.scriptName}.${symbolName} (channel ${channel}) failed:`, e);
        return undefined;
      }
    });

    lingoHost.setScriptInstanceCreator((type, args) => {
      const match = type.match(/^script\("(.+)"\)$/);
      if (!match) {
        return undefined;
      }
      const scriptName = match[1];
      const mod = scriptsByName.get(scriptName);
      if (!mod) {
        return undefined;
      }
      const me = lingoHost.createMe(0);
      me.props.set("__scriptName", scriptName);
      const stub = mod.handlerStubs["_new"];
      if (stub) {
        try {
          stub(me, ...args);
        } catch (e) {
          console.warn(`new(${type}) constructor failed:`, e);
        }
      }
      return me;
    });

    function dispatchMovieScriptEvent(event: string, ...args: unknown[]): void {
      for (const mod of loadedScripts) {
        if (mod.scriptType !== "MovieScript") {
          continue;
        }
        for (const handler of mod.handlers) {
          if (handler.event === event) {
            const stub = mod.handlerStubs[handler.name];
            if (!stub) {
              continue;
            }
            try {
              stub(lingoHost.createMe(0), ...args);
            } catch (e) {
              console.warn(`Lingo movie script ${mod.scriptName}.${handler.name} (${event}) failed:`, e);
            }
          }
        }
      }
    }

    function dispatchGlobalEvent(event: string, ...args: unknown[]): void {
      // Sprite behavior instances (channel order), then frame script, then movie scripts.
      const frame = score.frames[frameIndex];
      const behaviors = frame.behaviors ?? [];
      for (const bc of behaviors) {
        dispatchBehaviorEvent(bc, event, ...args);
      }
      if (frame.frameScript) {
        dispatchFrameScriptEvent(frame.frameScript, event, ...args);
      }
      dispatchMovieScriptEvent(event, ...args);
    }

    function dispatchFrameAndMovieEvent(event: string, ...args: unknown[]): void {
      const frame = score.frames[frameIndex];
      if (frame.frameScript) {
        dispatchFrameScriptEvent(frame.frameScript, event, ...args);
      }
      dispatchMovieScriptEvent(event, ...args);
    }

    function dispatchSpriteAndMovieEvent(event: string, ...args: unknown[]): void {
      const frame = score.frames[frameIndex];
      const behaviors = frame.behaviors ?? [];
      for (const bc of behaviors) {
        dispatchBehaviorEvent(bc, event, ...args);
      }
      dispatchMovieScriptEvent(event, ...args);
    }

    function dispatchFrameScriptEvent(
      fs: NonNullable<ScoreFrameJson["frameScript"]>,
      event: string,
      ...args: unknown[]
    ): void {
      if (!fs) {
        return;
      }
      const mod = scriptsByCastMember.get(castKey(fs.castLib, fs.castMember));
      if (!mod) {
        return;
      }
      for (const handler of mod.handlers) {
        if (handler.event === event) {
          const stub = mod.handlerStubs[handler.name];
          if (!stub) {
            continue;
          }
          try {
            const me = lingoHost.createMe(0);
            me.props.set("__scriptName", mod.scriptName);
            stub(me, ...args);
          } catch (e) {
            console.warn(`Frame script ${mod.scriptName}.${handler.name} (${event}) failed:`, e);
          }
        }
      }
    }

    // Track which behavior instances are active so beginSprite/endSprite fire on transitions.
    let activeBehaviorKeys = new Set<string>();

    function beginSpritesForFrame(idx: number): void {
      const frame = score.frames[idx];
      const behaviors = frame.behaviors ?? [];
      const nextKeys = new Set<string>();
      for (const bc of behaviors) {
        nextKeys.add(behaviorKey(bc));
      }

      // endSprite for behaviors that disappeared since the previous frame.
      for (const key of activeBehaviorKeys) {
        if (!nextKeys.has(key)) {
          const bc = behaviors.find((b) => behaviorKey(b) === key);
          if (bc) {
            dispatchBehaviorEvent(bc, "endSprite");
          }
        }
      }
      // beginSprite for behaviors that are new this frame.
      for (const bc of behaviors) {
        const key = behaviorKey(bc);
        if (!activeBehaviorKeys.has(key)) {
          dispatchBehaviorEvent(bc, "beginSprite");
        }
      }
      // Frame scripts are per-frame in Director; dispatch their beginSprite every frame.
      if (frame.frameScript) {
        dispatchFrameScriptEvent(frame.frameScript, "beginSprite");
      }
      activeBehaviorKeys = nextKeys;
    }

    let bakeTick = 0;

    function renderSnapshot(snapshot: runtime.FrameSnapshot): void {
      const out = renderFrame(snapshot, width, height).pixels();
      const dst = imageData.data;
      for (let i = 0; i < out.length; ++i) {
        const p = out[i] >>> 0;
        dst[i * 4] = (p >>> 16) & 0xff;
        dst[i * 4 + 1] = (p >>> 8) & 0xff;
        dst[i * 4 + 2] = p & 0xff;
        dst[i * 4 + 3] = (p >>> 24) & 0xff;
      }
      g.putImageData(imageData, 0, 0);
      texture.source.update();
    }

    function buildAndSetSnapshot(idx: number): void {
      const snapshot = buildFrameSnapshot(score, cast, idx, loadBitmap, bakeTick);
      lingoHost.snapshot = snapshot;
    }

    function paintFrame(idx: number): void {
      buildAndSetSnapshot(idx);
      renderSnapshot(lingoHost.snapshot!);
    }

    // --- Director lifecycle ----------------------------------------------------
    // Mirrors Player::prepareMovieFoundation() plus executeFrameCycle() enough for
    // Lingo-driven parity. Event order is taken from the C++ FrameContext/EventDispatcher.

    // prepareMovie: movie scripts only.
    dispatchMovieScriptEvent("prepareMovie");

    // First frame: beginSprite, prepareFrame (global), startMovie, enterFrame, exitFrame.
    frameIndex = 0;
    beginSpritesForFrame(0);
    buildAndSetSnapshot(0);
    dispatchGlobalEvent("prepareFrame");
    dispatchMovieScriptEvent("startMovie");
    dispatchGlobalEvent("enterFrame");
    dispatchSpriteAndMovieEvent("exitFrame");
    paintFrame(0);

    function tick(): void {
      // executeFrame for the current frame: stepFrame, prepareFrame, enterFrame (global).
      dispatchGlobalEvent("stepFrame");
      dispatchGlobalEvent("prepareFrame");
      dispatchGlobalEvent("enterFrame");

      // Idle: movie scripts only.
      dispatchMovieScriptEvent("idle");

      // advanceFrame: exitFrame global, then transition to the next frame and beginSprite.
      // If Lingo called `go()`, apply the override; otherwise advance by one. An override
      // that points to the current frame (e.g. `go(the frame)`) must be honoured, so we
      // treat a returned `null` as "no override" rather than comparing indices.
      dispatchGlobalEvent("exitFrame");
      const overriddenFrameIndex = lingoHost.applyFrameOverride(frameIndex);
      const nextFrameIndex = overriddenFrameIndex !== null
        ? overriddenFrameIndex
        : (frameIndex + 1) % score.frames.length;
      frameIndex = nextFrameIndex;
      beginSpritesForFrame(frameIndex);

      buildAndSetSnapshot(frameIndex);
      paintFrame(frameIndex);
      bakeTick++;
    }
    const labelAt = (idx: number): string => {
      const name = player.labelForFrame(score.frames[idx].frame);
      return name ? ` [${name}]` : "";
    };
    const statusText = (prefix: string): string =>
      `${prefix} frame ${frameIndex + 1}/${score.frames.length} @ ${player.effectiveTempo(frameIndex)} fps`
      + `${labelAt(frameIndex)} (runtime ${runtime.describeRuntimeVersion()})`;
    if (statusEl) {
      statusEl.textContent = statusText("Playing");
    }

    // Per-frame tempo playback: each frame dwells for 1 / its-effective-tempo seconds,
    // matching Director's frame rate (and the score tempo channel). The exporter ships a
    // per-frame tempo that mirrors C++ Player::tempo() for the no-Lingo static export.
    let last = performance.now();
    let paused = false;

    function loop(now: number): void {
      requestAnimationFrame(loop);
      if (paused || score.frames.length === 0) {
        return;
      }
      if (now - last >= player.frameDelayMs(frameIndex)) {
        last = now;
        tick();
        if (statusEl) {
          statusEl.textContent = statusText("Playing");
        }
      }
    }
    requestAnimationFrame(loop);

    // Expose deterministic playback hooks for the parity harness (Playwright/Node).
    // __lsStep runs one Director tick cycle synchronously; __lsCapture returns the
    // current stage canvas RGBA pixels so the harness can compare against C++ reference frames.
    // __lsSetPaused disables the rAF playback loop so the harness owns every tick.
    (window as unknown as {
      __lsStep?: () => void;
      __lsCapture?: () => Uint8ClampedArray;
      __lsFrameIndex?: () => number;
      __lsBakeTick?: () => number;
      __lsSetPaused?: (paused: boolean) => void;
    }).__lsStep = tick;
    (window as unknown as {
      __lsStep?: () => void;
      __lsCapture?: () => Uint8ClampedArray;
      __lsFrameIndex?: () => number;
      __lsBakeTick?: () => number;
      __lsSetPaused?: (paused: boolean) => void;
    }).__lsCapture = () => g.getImageData(0, 0, width, height).data;
    (window as unknown as {
      __lsStep?: () => void;
      __lsCapture?: () => Uint8ClampedArray;
      __lsFrameIndex?: () => number;
      __lsBakeTick?: () => number;
      __lsSetPaused?: (paused: boolean) => void;
    }).__lsFrameIndex = () => frameIndex;
    (window as unknown as {
      __lsStep?: () => void;
      __lsCapture?: () => Uint8ClampedArray;
      __lsFrameIndex?: () => number;
      __lsBakeTick?: () => number;
      __lsSetPaused?: (paused: boolean) => void;
    }).__lsBakeTick = () => bakeTick;
    (window as unknown as {
      __lsStep?: () => void;
      __lsCapture?: () => Uint8ClampedArray;
      __lsFrameIndex?: () => number;
      __lsBakeTick?: () => number;
      __lsSetPaused?: (paused: boolean) => void;
    }).__lsSetPaused = (p) => {
      paused = p;
      if (statusEl) {
        statusEl.textContent = statusText(paused ? "Paused" : "Playing");
      }
    };

    document.addEventListener("keydown", (ev) => {
      // Prime audio on the first keypress (browsers require a user gesture).
      void primeAudio();
      if (ev.key === " ") {
        paused = !paused;
        if (statusEl) {
          statusEl.textContent = statusText(paused ? "Paused" : "Playing");
        }
      } else if (ev.key === "ArrowRight") {
        tick();
        if (statusEl) {
          statusEl.textContent = statusText("Paused");
        }
      } else if (ev.key === "ArrowLeft") {
        frameIndex = (frameIndex - 1 + score.frames.length) % score.frames.length;
        paintFrame(frameIndex);
        if (statusEl) {
          statusEl.textContent = statusText("Paused");
        }
      } else if (ev.key === "g" || ev.key === "G") {
        // Jump to a frame label / marker by name (case-insensitive).
        const labels = player.labels();
        if (labels.length === 0) {
          if (statusEl) {
            statusEl.textContent = `No frame labels in this export — ${statusText("Paused")}`;
          }
          return;
        }
        const name = window.prompt(`Jump to label (${labels.map((l) => l.name).join(", ")}):`);
        if (!name) {
          return;
        }
        const idx = player.indexForLabel(name);
        if (idx !== null) {
          frameIndex = idx;
          paintFrame(frameIndex);
          paused = true;
          if (statusEl) {
            statusEl.textContent = statusText("Paused");
          }
        } else if (statusEl) {
          statusEl.textContent = `No label "${name}" — ${statusText("Paused")}`;
        }
      } else if (ev.key === "a" || ev.key === "A") {
        // Play a sound by name (Stage 6). Cues are Lingo-driven, so this is a manual trigger
        // for inspecting exported audio; emitted Lingo (Stage 7) will call audio.play(name).
        const names = audio.names();
        if (names.length === 0) {
          if (statusEl) {
            statusEl.textContent = `No sounds in this export — ${statusText("Playing")}`;
          }
        } else {
          const name = window.prompt(`Play sound (${names.join(", ")}):`);
          if (name) {
            const handle = audio.play(name);
            if (statusEl) {
              statusEl.textContent = handle
                ? `Playing sound "${name}" — ${statusText("Playing")}`
                : `Sound "${name}" not loaded — ${statusText("Playing")}`;
            }
          }
        }
      } else if (ev.key === "s" || ev.key === "S") {
        // List the emitted Lingo scripts (Stage 7). They are fully transpiled to TypeScript under
        // src/scripts/ and executed live via the LingoRuntimeHost. This key binding just reports
        // what was emitted; the full handler table lives in each module's `handlers` export and
        // `events` here are the Director system-event handlers dispatched per frame.
        const scripts = manifest.scripts ?? [];
        if (scripts.length === 0) {
          if (statusEl) {
            statusEl.textContent = `No Lingo scripts in this export — ${statusText("Paused")}`;
          }
        } else {
          const lines = scripts.map(
            (s) => `  ${s.name} [${s.type}] — ${s.handlerCount} handler(s)${
              s.events.length ? `, events: ${s.events.join(", ")}` : ""
            }`,
          );
          console.log(`Emitted Lingo scripts (${scripts.length}):\n${lines.join("\n")}`);
          if (statusEl) {
            statusEl.textContent = `${scripts.length} Lingo script(s) emitted (see console) — ${statusText("Paused")}`;
          }
        }
      }
    });
  } catch (error) {
    if (statusEl) {
      statusEl.textContent = `Failed to start: ${(error as Error).message}`;
    }
    console.error(error);
  }
})();