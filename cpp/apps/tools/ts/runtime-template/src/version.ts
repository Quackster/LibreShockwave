// Runtime self-description. Lets an exported project report which renderer features the
// bundled runtime supports, so a stale runtime copy is obvious at load time.

export const RUNTIME_VERSION = "0.1.0-stage8";

// Feature flags grow stage by stage. `true` means the runtime can render that subsystem
// faithfully enough to pass the differential harness for the inks/types listed.
//
// Stage 2 added bit-exact scaled blits and proved COPY / MATTE / BACKGROUND_TRANSPARENT /
// BLEND against a real Director movie's C++ reference frames (mobiles_disco.dir).
//
// Stage 3 extends the compositor to every InkMode value: the full special-ink switch
// (ADD / ADD_PIN / SUBTRACT / SUBTRACT_PIN / LIGHTEST / DARKEST / LIGHTEN / DARKEN /
// REVERSE / GHOST / NOT_COPY / NOT_TRANSPARENT / NOT_REVERSE / NOT_GHOST) plus MASK and
// TRANSPARENT. Coverage is proven bit-exact (tolerance 0) by the `--self-test-inks`
// synthetic harness (20 inks x 3 variants = 60 frames) AND by the real-movie differential
// (mobiles_disco.dir, 61/61 at tolerance 0). MATTE / MASK / BACKGROUND_TRANSPARENT reuse
// the normal alpha path (the baked bitmap's alpha channel already encodes matte/mask
// transparency), matching C++ where they are not in the special-ink switch.
export const RUNTIME_FEATURES = {
  inks: [
    "COPY",
    "TRANSPARENT",
    "REVERSE",
    "GHOST",
    "NOT_COPY",
    "NOT_TRANSPARENT",
    "NOT_REVERSE",
    "NOT_GHOST",
    "MATTE",
    "MASK",
    "BLEND",
    "ADD_PIN",
    "ADD",
    "SUBTRACT_PIN",
    "SUBTRACT",
    "BACKGROUND_TRANSPARENT",
    "LIGHTEST",
    "DARKEST",
    "LIGHTEN",
    "DARKEN",
  ] as const,
  // Stage 4: shape and text sprites are baked to RGBA by the C++ SpriteBaker (the baker's
  // output is serialized as an asset, exactly like decoded bitmaps) and composited through
  // the same bitmap path, proven bit-exact on mobiles_disco.dir (TEXT in frames 5-24/32-33,
  // SHAPE in frames 15-18). The runtime re-implements the compositor (SoftwareFrameRenderer),
  // not the baker (SpriteBaker); no TS shape-draw / text-raster port is needed for parity.
  // Stage 4/6/8: shape/text/button/film-loop/shockwave3d sprites are all baked to RGBA by the
  // C++ SpriteBaker (the baker's output is serialized as an asset, exactly like decoded bitmaps)
  // and composited through the same type-agnostic bitmap path. The runtime re-implements the
  // compositor (SoftwareFrameRenderer), not the baker; no TS shape-draw / text-raster / film-loop
  // / 3D-raster port is needed for parity.
  spriteTypes: ["bitmap", "shape", "text", "button", "filmloop", "w3d"] as const,
  scaling: true,
  rotation: false,
  shapes: false,
  text: false,
  // Stage 5: score playback fidelity. The exporter ships base tempo + a per-frame effective
  // tempo (mirroring C++ Player::tempo() for the no-Lingo static export) and the frame
  // labels / markers; ScorePlayer drives playback at the movie's real frame rate and
  // navigates by label. Transitions are NOT implemented in the C++ player (no transition
  // channel is parsed, no transition render code; FrameSnapshot is always a settled frame),
  // so there are no transition frames to gate on — a TS TransitionPlayer would be net-new
  // behavior diverging from C++ and is out of scope for the parity contract.
  scoreFidelity: true,
  tempo: true,
  labels: true,
  transitions: false,
  // Stage 6: FilmLoop pixel parity is met via the baked-bitmap path (the C++ SpriteBaker bakes
  // the film-loop's current sub-frame per score frame using tickCounter_ % frameCount, and the
  // exporter bakes once per score frame in order, so shipped baked bitmaps reproduce the exact
  // sub-frame sequence) — proven on mobiles_disco.dir (26/60 frames carry FILM_LOOP sprites,
  // 61/61 bit-exact). No TS FilmLoopPlayer is needed for the parity gate. Audio: the exporter
  // decodes sound cast members to WAV/MP3 assets (via the C++ SoundManager path) and lists them
  // in cast.json; AudioPlayer plays them by name. Director sound playback is Lingo-driven (no
  // score sound channel, no parsed cue points), so per-frame cues are NOT statically captured —
  // they arrive with the emitted Lingo in Stage 7, which drives AudioPlayer.play(name).
  filmLoop: true,
  audio: true,
  audioCues: false,
  // Stage 7: Lingo scripts are TRANSPILED to runnable TypeScript modules (src/scripts/*.ts)
  // preserving the decompiled Lingo source + a structured handler table, backed by
  // lingo-runtime.ts and LingoRuntimeHost.ts. Handlers execute live in the browser and mutate
  // the live FrameSnapshot. `lingo` remains false until the parity gate is met: the TS run must
  // produce post-Lingo frames matching the C++ oracle (e.g. the orange furniture in
  // mobiles_disco). `lingoEmission` and `lingoRuntime` are true.
  lingo: false,
  lingoEmission: true,
  lingoRuntime: true,
  // Stage 8: Shockwave3D parity is met via the baked-bitmap path — the C++ SpriteBaker::
  // bakeShockwave3D rasterizes each W3D sprite's meshes (flat-shaded, orthographic fit-to-world-
  // bounds, painter's-algorithm depth sort) to RGBA, the exporter ships those pixels as a
  // bakedBitmapAsset with type "SHOCKWAVE_3D", and the type-agnostic compositor renders them
  // through the proven bitmap path (bit-exact, tolerance 0) — same as shape/text/film-loop. A
  // Three.js re-render is NOT a parity path: it would use a perspective camera + lighting and
  // diverge from the C++ reference (which is itself a flat-shaded approximation of Director's
  // real 3D), contrary to the parity contract, so it is out of scope. No real .dir fixture with
  // a Shockwave3D cast member exists in the repo, so this is proven structurally (coerceSpriteType
  // SHOCKWAVE_3D->w3d + the type-agnostic compositor test) and by the C++ rasterizer's own unit
  // tests, not end-to-end on a movie.
  shockwave3D: true,
} as const;

export function describeRuntimeVersion(): string {
  return `${RUNTIME_VERSION} (inks: ${RUNTIME_FEATURES.inks.join("/")}; sprites: ${RUNTIME_FEATURES.spriteTypes.join("/")})`;
}