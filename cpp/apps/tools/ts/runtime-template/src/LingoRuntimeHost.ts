// TypeScript Lingo host implementation.
//
// This wires the transpiled Lingo handlers (lingo-runtime.ts accessors + callBuiltin) to the live
// exported movie state: the score JSON, the current FrameSnapshot, cast members, and globals.
// It is intentionally minimal for the Stage 7 first slice; unimplemented surfaces throw
// LingoNotImplemented so failures are loud rather than silently wrong.

import type { Bitmap } from "./Bitmap.js";
import { buildSprite, type BitmapLoader, type ScoreJson, type ScoreSpriteJson } from "./ScoreData.js";
import { inkModeFromCode } from "./InkMode.js";
import type { FrameSnapshot, RenderSprite } from "./FrameSnapshot.js";
import type { ScorePlayer } from "./ScorePlayer.js";
import {
  LingoNotImplemented,
  type LingoHost,
  type LingoMe,
  type LingoSymbol,
  type LingoValue,
  isSymbol,
  getItemDelimiter,
  setItemDelimiter,
} from "./lingo-runtime.js";

/** A cast member token used by `member()` and `sprite(...).member`. */
export interface MemberToken {
  readonly id: number | string;
  readonly castLib?: number | string;
}

export function isMemberToken(value: LingoValue): value is MemberToken {
  return typeof value === "object" && value !== null && "id" in value &&
    (typeof (value as MemberToken).id === "number" ||
     typeof (value as MemberToken).id === "string");
}

export interface LingoRuntimeHostOptions {
  score: ScoreJson;
  loadBitmap: BitmapLoader;
  player: ScorePlayer;
  audio?: { play(name: string): void };
  cast?: import("./ScoreData.js").CastJson;
}

/** Mutable host state that survives across frames. */
interface HostState {
  globals: Map<string, LingoValue>;
  frameIndexOverride: number | null;
  pendingSound: string | null;
  memberTextCache: Map<string, LingoValue>;
  randomSeed: number;
  randomState: number;
}

export class LingoRuntimeHost implements LingoHost {
  private readonly score: ScoreJson;
  private readonly loadBitmap: BitmapLoader;
  private readonly player: ScorePlayer;
  private readonly audio?: { play(name: string): void };
  private readonly cast?: import("./ScoreData.js").CastJson;
  private readonly state: HostState;
  private readonly spriteMemberTokens = new WeakMap<RenderSprite, MemberToken>();

  /** The snapshot the host is currently reading/writing. Set by the frame loop before handlers run. */
  public snapshot: FrameSnapshot | null = null;

  private spriteDispatcher: ((channel: number, symbolName: string, args: LingoValue[]) => LingoValue) | null = null;
  private scriptInstanceCreator: ((scriptName: string, args: LingoValue[]) => LingoValue) | null = null;

  setSpriteDispatcher(dispatcher: typeof this.spriteDispatcher): void {
    this.spriteDispatcher = dispatcher;
  }

  setScriptInstanceCreator(creator: typeof this.scriptInstanceCreator): void {
    this.scriptInstanceCreator = creator;
  }

  constructor(options: LingoRuntimeHostOptions) {
    this.score = options.score;
    this.loadBitmap = options.loadBitmap;
    this.player = options.player;
    this.audio = options.audio;
    this.cast = options.cast;
    this.state = {
      globals: new Map(),
      frameIndexOverride: null,
      pendingSound: null,
      memberTextCache: new Map(),
      randomSeed: 0,
      randomState: 0,
    };
    this.setRandomSeed(0);
  }

  // --- Deterministic random (matches C++ LingoVM / java.util.Random) ------------
  private setRandomSeed(seed: number): void {
    const MASK = (1n << 48n) - 1n;
    const MULTIPLIER = 0x5DEECE66Dn;
    this.state.randomSeed = seed;
    let s = (BigInt(seed) ^ MULTIPLIER) & MASK;
    this.state.randomState = Number(s);
  }

  private nextRandomBits(bits: number): number {
    const MASK = (1n << 48n) - 1n;
    const MULTIPLIER = 0x5DEECE66Dn;
    const ADDEND = 0xBn;
    let s = (BigInt(this.state.randomState) * MULTIPLIER + ADDEND) & MASK;
    this.state.randomState = Number(s);
    return Number(s >> BigInt(48 - bits));
  }

  private randomInt(max: number): number {
    if (max <= 0) {
      return 1;
    }
    let result = 0;
    if ((max & -max) === max) {
      // max is a power of two: fast path.
      result = Number((BigInt(max) * BigInt(this.nextRandomBits(31))) >> 31n);
    } else {
      let bits = 0;
      do {
        bits = this.nextRandomBits(31);
        result = bits % max;
      } while (bits - result + (max - 1) < 0);
    }
    return result + 1;
  }

  private memberCacheKey(member: import("./ScoreData.js").CastMemberJson | null, token?: MemberToken): string {
    if (member) {
      return `${member.castLib}:${member.id}`;
    }
    if (token) {
      if (typeof token.id === "string") {
        return `name:${token.id}`;
      }
      return `${token.castLib ?? 0}:${token.id}`;
    }
    return "";
  }

  private requireSnapshot(): FrameSnapshot {
    if (!this.snapshot) {
      throw new LingoNotImplemented("Lingo host has no active snapshot (frame loop not running).");
    }
    return this.snapshot;
  }

  private findSpriteIndex(channel: number): number {
    const snapshot = this.requireSnapshot();
    return snapshot.sprites.findIndex((s) => s.channel === channel);
  }

  private currentScoreSpriteJson(channel: number): ScoreSpriteJson | null {
    const snapshot = this.requireSnapshot();
    const frameIndex = (snapshot.frameNumber >= 1 ? snapshot.frameNumber : 1) - 1;
    const frame = this.score.frames[frameIndex];
    if (!frame) {
      return null;
    }
    return frame.sprites.find((s) => s.channel === channel) ?? null;
  }

  private ensureSprite(channel: number): RenderSprite {
    const snapshot = this.requireSnapshot();
    const index = this.findSpriteIndex(channel);
    if (index >= 0) {
      const sprite = snapshot.sprites[index];
      if (!this.spriteMemberTokens.has(sprite)) {
        const json = this.currentScoreSpriteJson(channel);
        if (json && json.castMemberId) {
          this.spriteMemberTokens.set(sprite, { id: json.castMemberId });
        } else {
          this.spriteMemberTokens.set(sprite, { id: 0 });
        }
      }
      return sprite;
    }
    // Create a dynamic placeholder sprite for Lingo-puppetted channels.
    const placeholder: ScoreSpriteJson = {
      channel,
      x: 0,
      y: 0,
      width: 0,
      height: 0,
      locZ: channel,
      visible: true,
      type: "BITMAP",
      ink: 0,
      blend: 100,
      flipH: false,
      flipV: false,
      rotation: 0,
      skew: 0,
      bakedBitmapAsset: null,
    };
    const sprite = buildSprite(placeholder, this.loadBitmap);
    snapshot.sprites.push(sprite);
    return sprite;
  }

  getSpriteProp(channel: number, prop: string): LingoValue {
    const sprite = this.ensureSprite(channel);
    switch (prop.toLowerCase()) {
      case "loch":
        return sprite.x;
      case "locv":
        return sprite.y;
      case "loc":
        return [sprite.x, sprite.y];
      case "locz":
        return sprite.locZ;
      case "width":
        return sprite.width;
      case "height":
        return sprite.height;
      case "visible":
        return sprite.visible;
      case "puppet":
        return true;
      case "ink":
        return sprite.ink;
      case "blend":
        return sprite.blend;
      case "fliph":
        return sprite.flipH;
      case "flipv":
        return sprite.flipV;
      case "rotation":
        return sprite.rotation;
      case "skew":
        return sprite.skew;
      case "member":
      case "castnum":
      case "membernum":
        return this.memberFromSprite(sprite);
      case "cursor":
        return null;
      case "palette":
        return null;
      case "scriptinstancelist":
        return null;
      default:
        throw new LingoNotImplemented(`sprite(${channel}).${prop} is not implemented in the TS host.`);
    }
  }

  setSpriteProp(channel: number, prop: string, value: LingoValue): void {
    const sprite = this.ensureSprite(channel);
    const num = (v: LingoValue): number => (typeof v === "number" ? v : Number(v));
    const bool = (v: LingoValue): boolean => {
      if (typeof v === "boolean") return v;
      if (typeof v === "number") return v !== 0;
      return !!v;
    };
    switch (prop.toLowerCase()) {
      case "loch":
        sprite.x = num(value);
        break;
      case "locv":
        sprite.y = num(value);
        break;
      case "loc": {
        const arr = value as number[] | LingoValue;
        if (Array.isArray(arr)) {
          sprite.x = num(arr[0]);
          sprite.y = num(arr[1]);
        }
        break;
      }
      case "locz":
        sprite.locZ = num(value);
        break;
      case "width":
        sprite.width = num(value);
        break;
      case "height":
        sprite.height = num(value);
        break;
      case "visible":
        sprite.visible = bool(value);
        break;
      case "ink":
        sprite.ink = inkModeFromCode(num(value));
        break;
      case "blend":
        sprite.blend = num(value);
        break;
      case "fliph":
        sprite.flipH = bool(value);
        break;
      case "flipv":
        sprite.flipV = bool(value);
        break;
      case "rotation":
        sprite.rotation = num(value);
        break;
      case "skew":
        sprite.skew = num(value);
        break;
      case "member":
      case "castnum":
      case "membernum":
        this.setMemberOnSprite(sprite, value);
        break;
      case "cursor":
      case "palette":
        // Silently ignored for first slice.
        break;
      default:
        throw new LingoNotImplemented(`set sprite(${channel}).${prop} is not implemented in the TS host.`);
    }
  }

  private memberFromSprite(sprite: RenderSprite): MemberToken {
    const token = this.spriteMemberTokens.get(sprite);
    if (token) {
      return token;
    }
    const json = this.currentScoreSpriteJson(sprite.channel);
    if (json && json.castMemberId) {
      return { id: json.castMemberId };
    }
    return { id: 0 };
  }

  private spriteCastLib(sprite: RenderSprite): number | undefined {
    const token = this.spriteMemberTokens.get(sprite);
    if (token && typeof token.castLib === "number") {
      return token.castLib;
    }
    return undefined;
  }

  private applyMemberBitmap(sprite: RenderSprite, member: import("./ScoreData.js").CastMemberJson | null): void {
    if (member && member.bakedBitmapAsset) {
      const baked = this.loadBitmap(member.bakedBitmapAsset);
      sprite.bakedBitmap = baked;
      if (baked) {
        sprite.width = baked.width();
        sprite.height = baked.height();
      }
    }
  }

  private setMemberOnSprite(sprite: RenderSprite, value: LingoValue): void {
    if (isMemberToken(value)) {
      const member = this.resolveMember(value.id, value.castLib);
      this.spriteMemberTokens.set(sprite, { id: value.id, castLib: value.castLib });
      this.applyMemberBitmap(sprite, member);
      return;
    }
    if (typeof value === "string") {
      const member = this.findCastMemberByName(value);
      if (member) {
        this.spriteMemberTokens.set(sprite, { id: member.id, castLib: member.castLib });
      }
      this.applyMemberBitmap(sprite, member);
      return;
    }
    if (typeof value === "number") {
      const castLib = this.spriteCastLib(sprite);
      const member = this.resolveMember(value, castLib);
      if (member) {
        this.spriteMemberTokens.set(sprite, { id: member.id, castLib: member.castLib });
      }
      this.applyMemberBitmap(sprite, member);
      return;
    }
  }

  private resolveMember(id: number | string, castLib?: number | string): import("./ScoreData.js").CastMemberJson | null {
    const members = this.cast?.members;
    if (!members) {
      return null;
    }
    const rawLib = castLib === undefined ? undefined : Number(castLib);
    // Director casts are numbered from 1; a castLib of 0 means "unspecified" in Lingo
    // shorthand like `field "x"` / `member("x", 0)` and should resolve by name across all libs.
    const libNum = (rawLib !== undefined && rawLib !== 0 && !Number.isNaN(rawLib)) ? rawLib : undefined;
    if (typeof id === "string") {
      for (const m of members) {
        if (m.name !== id) {
          continue;
        }
        if (libNum !== undefined && m.castLib !== libNum) {
          continue;
        }
        return m;
      }
      return null;
    }
    const num = Number(id);
    for (const m of members) {
      if (m.id !== num) {
        continue;
      }
      if (libNum !== undefined && m.castLib !== libNum) {
        continue;
      }
      return m;
    }
    return null;
  }

  private findCastMemberByName(name: string): import("./ScoreData.js").CastMemberJson | null {
    return this.resolveMember(name, undefined);
  }


  getMember(numOrName: LingoValue, castLib?: LingoValue): LingoValue {
    const safeId: number | string =
      numOrName === null || numOrName === undefined ? 0 :
      typeof numOrName === "number" ? numOrName :
      typeof numOrName === "string" ? numOrName :
      0;
    const safeCastLib: number | string | undefined =
      castLib === null || castLib === undefined ? undefined :
      typeof castLib === "number" ? castLib :
      typeof castLib === "string" ? castLib :
      undefined;
    return { id: safeId, castLib: safeCastLib };
  }

  getMemberProp(member: LingoValue, prop: string): LingoValue {
    if (!isMemberToken(member)) {
      return undefined;
    }
    const resolved = this.resolveMember(member.id, member.castLib);
    const lower = prop.toLowerCase();
    if (lower === "name") {
      return resolved?.name ?? "";
    }
    if (lower === "number" || lower === "num") {
      return typeof member.id === "number" ? member.id : 0;
    }
    if (lower === "text" || lower === "htmltext") {
      const key = this.memberCacheKey(resolved, member as MemberToken);
      if (key && this.state.memberTextCache.has(key)) {
        return this.state.memberTextCache.get(key);
      }
      // Initial static text exported from the Director text cast member.
      if (resolved && "text" in resolved && resolved.text !== undefined) {
        return resolved.text;
      }
      return "";
    }
    if (lower === "width" || lower === "height") {
      return resolved ? (lower === "width" ? resolved.bakedWidth : resolved.bakedHeight) : 0;
    }
    return undefined;
  }

  setMemberProp(member: LingoValue, prop: string, value: LingoValue): void {
    if (!isMemberToken(member)) {
      return;
    }
    const lower = prop.toLowerCase();
    if (lower === "text" || lower === "htmltext") {
      const resolved = this.resolveMember(member.id, member.castLib);
      const key = this.memberCacheKey(resolved, member as MemberToken);
      if (key) {
        this.state.memberTextCache.set(key, value);
      }
      return;
    }
    if (lower === "name") {
      // Rename is not supported; ignored.
      return;
    }
  }


  getGlobal(name: string): LingoValue {
    return this.state.globals.get(name);
  }

  setGlobal(name: string, value: LingoValue): void {
    this.state.globals.set(name, value);
  }

  getThe(name: string): LingoValue {
    switch (name.toLowerCase()) {
      case "frame":
        return this.currentFrame();
      case "itemdelimiter":
        return getItemDelimiter();
      case "key":
        return "";
      case "mouseh":
      case "mousev":
      case "mousedown":
        return 0;
      case "shiftdown":
        return false;
      case "randomseed":
        return this.state.randomSeed;
      default:
        throw new LingoNotImplemented(`the ${name} is not implemented in the TS host.`);
    }
  }

  setThe(name: string, value: LingoValue): void {
    switch (name.toLowerCase()) {
      case "itemdelimiter":
        setItemDelimiter(String(value));
        return;
      case "randomseed":
        this.setRandomSeed(Number(value) | 0);
        return;
      default:
        // No-op for unimplemented global properties in the first slice.
        break;
    }
  }

  currentFrame(): number {
    if (this.state.frameIndexOverride !== null) {
      return this.state.frameIndexOverride + 1;
    }
    return this.snapshot?.frameNumber ?? 1;
  }

  go(target: LingoValue): void {
    if (typeof target === "number") {
      this.state.frameIndexOverride = Math.max(0, target - 1);
    } else if (typeof target === "string") {
      const idx = this.player.indexForLabel(target);
      if (idx !== null && idx >= 0) {
        this.state.frameIndexOverride = idx;
      }
    } else if (isSymbol(target)) {
      const idx = this.player.indexForLabel(target.name);
      if (idx !== null && idx >= 0) {
        this.state.frameIndexOverride = idx;
      }
    }
  }

  callBuiltin(name: string, args: LingoValue[]): LingoValue {
    const lower = name.toLowerCase();
    switch (lower) {
      case "random":
        return this.randomInt(Number(args[0]) || 1);
      case "point":
        return [Number(args[0] ?? 0), Number(args[1] ?? 0)];
      case "go":
        this.go(args[0]);
        return undefined;
      case "marker":
        if (isSymbol(args[0])) {
          const idx = this.player.indexForLabel(args[0].name);
          return idx !== null && idx >= 0 ? idx + 1 : 0;
        }
        if (typeof args[0] === "string") {
          const idx = this.player.indexForLabel(args[0]);
          return idx !== null && idx >= 0 ? idx + 1 : 0;
        }
        return 0;
      case "updatestage":
        return undefined;
      case "preload":
        return undefined;
      case "sendallsprites": {
        const symbol = isSymbol(args[0]) ? args[0] : null;
        if (!symbol) return undefined;
        // Handled by the event dispatcher; this host-level call is a no-op.
        return undefined;
      }
      case "sendfusemsg":
        // Network messages are not replayable in the browser.
        return undefined;
      case "play": {
        const member = args[0];
        if (isMemberToken(member) && typeof member.id === "string" && this.audio) {
          this.audio.play(member.id);
        }
        return undefined;
      }
      case "sprite":
        return Number(args[0]);
      case "member":
        return this.getMember(args[0], args[1]);
      case "sendsprite": {
        const channel = Number(args[0]);
        const sym = args[1];
        const symbolName = isSymbol(sym) ? sym.name : typeof sym === "string" ? sym : "";
        const handlerArgs = args.slice(2);
        if (this.spriteDispatcher) {
          return this.spriteDispatcher(channel, symbolName, handlerArgs);
        }
        return undefined;
      }
      case "newobj": {
        const type = typeof args[0] === "string" ? args[0] : "";
        const ctorArgs = Array.isArray(args[1]) ? (args[1] as LingoValue[]) : [];
        if (this.scriptInstanceCreator) {
          return this.scriptInstanceCreator(type, ctorArgs);
        }
        return undefined;
      }
      default:
        throw new LingoNotImplemented(`Lingo builtin "${name}" is not implemented in the TS host.`);
    }
  }

  /**
   * Apply any pending frame override and return the next frame index (0-based),
   * or `null` when no override was requested. The caller must distinguish these
   * cases because `go(the frame)` sets an override equal to the current frame.
   */
  applyFrameOverride(frameIndex: number): number | null {
    if (this.state.frameIndexOverride !== null) {
      const next = this.state.frameIndexOverride;
      this.state.frameIndexOverride = null;
      return next;
    }
    return null;
  }

  /** Create a `me` object for a behavior instance on the given channel. */
  createMe(channel: number): LingoMe {
    return { spriteNum: channel, props: new Map() };
  }
}
