// Stage 7: Lingo runtime shim.
//
// This module is the API surface a future TS Lingo execution model (the remaining Stage 7 tail,
// see GOAL.md) will build on. Today it provides the pure value helpers Lingo handlers rely on —
// `integer`/`float` casts with Director's truncation semantics and 1-indexed list / propList
// containers — plus a `LingoNotImplemented` marker thrown by the emitted handler stubs
// (src/scripts/*.ts). The imperative accessors (`sprite`, `member`, `theProperty`, `sound`) are
// deliberately stubs: wiring them to the live ScorePlayer / AudioPlayer / FrameSnapshot is the
// execution work that has not landed, and per docs/rendering-rules.md the runtime must not fake
// C++ behavior, so they throw rather than silently no-op.
//
// No TS Lingo bytecode VM is built (out of scope, see GOAL.md): handlers are emitted as readable
// TS source backed by this shim, not interpreted.

/** Thrown by emitted handler stubs and by accessors whose execution wiring has not landed. */
export class LingoNotImplemented extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LingoNotImplemented";
  }
}

// Lingo values: a number (Director does not split integer/float at the value level — only the
// integer()/float() casts do), a string, a boolean, a list, a propList, or VOID (undefined).
export type LingoValue =
  | number
  | string
  | boolean
  | null
  | undefined
  | number[]
  | LingoList
  | LingoPropList
  | LingoSymbol
  | LingoSpriteProxy
  | LingoMemberProxy
  | LingoMe
  | { readonly id: number | string; readonly castLib?: number | string };

// Director lists are 1-indexed: index 1 is the first element, index 0 and out-of-range access
// are runtime errors (matching Lingo's "index out of range"). This is the container the emitted
// handlers and the future executor use for `[...]` literals and `list(...)` / `add` calls.
export class LingoList {
  private readonly items: LingoValue[] = [];

  constructor(items: readonly LingoValue[] = []) {
    this.items.push(...items);
  }

  get length(): number {
    return this.items.length;
  }

  get(index: number): LingoValue {
    if (!Number.isInteger(index) || index < 1 || index > this.items.length) {
      throw new RangeError(`LingoList index out of range: ${index} (length ${this.items.length})`);
    }
    return this.items[index - 1] as LingoValue;
  }

  set(index: number, value: LingoValue): void {
    if (!Number.isInteger(index) || index < 1) {
      throw new RangeError(`LingoList index out of range: ${index}`);
    }
    while (this.items.length < index - 1) {
      this.items.push(undefined);
    }
    this.items[index - 1] = value;
  }

  add(value: LingoValue): void {
    this.items.push(value);
  }

  toArray(): LingoValue[] {
    return [...this.items];
  }

  clear(): void {
    this.items.length = 0;
  }
}

// A propList is an ordered list of [:symbol: value] pairs (Director's [:a:1, :b:2]). Duplicate
// keys update in place; missing keys read as VOID. Insertion order is preserved for `count` and
// iteration, matching Director's propList semantics.
export class LingoPropList {
  private readonly keys: string[] = [];
  private readonly vals: LingoValue[] = [];

  get length(): number {
    return this.keys.length;
  }

  add(key: string, value: LingoValue): void {
    const i = this.keys.indexOf(key);
    if (i >= 0) {
      this.vals[i] = value;
      return;
    }
    this.keys.push(key);
    this.vals.push(value);
  }

  get(key: string): LingoValue {
    const i = this.keys.indexOf(key);
    if (i < 0) {
      return undefined;
    }
    return this.vals[i] as LingoValue;
  }

  has(key: string): boolean {
    return this.keys.indexOf(key) >= 0;
  }

  keyAt(index: number): LingoValue {
    if (!Number.isInteger(index) || index < 1 || index > this.keys.length) {
      return undefined;
    }
    return this.keys[index - 1];
  }

  remove(key: string): void {
    const i = this.keys.indexOf(key);
    if (i >= 0) {
      this.keys.splice(i, 1);
      this.vals.splice(i, 1);
    }
  }
}

// integer(x): Director truncates toward zero, matching C++ `static_cast<int>` on a double.
// Strings are parsed (leading/trailing whitespace tolerated); unparseable strings yield 0.
export function integer(value: LingoValue): number {
  if (typeof value === "number") {
    return Math.trunc(value);
  }
  if (typeof value === "boolean") {
    return value ? 1 : 0;
  }
  if (typeof value === "string") {
    const n = Number(value.trim());
    return Number.isFinite(n) ? Math.trunc(n) : 0;
  }
  return 0;
}

// float(x): promote to a double. Strings are parsed; unparseable strings yield 0.
export function float(value: LingoValue): number {
  if (typeof value === "number") {
    return value;
  }
  if (typeof value === "boolean") {
    return value ? 1 : 0;
  }
  if (typeof value === "string") {
    const n = Number(value.trim());
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

// String(x) in Lingo: VOID -> "VOID", TRUE/FALSE capitalized, lists bracketed.
export function lingoString(value: LingoValue): string {
  if (value === undefined) {
    return "VOID";
  }
  if (typeof value === "boolean") {
    return value ? "TRUE" : "FALSE";
  }
  if (typeof value === "string") {
    return value;
  }
  if (value instanceof LingoList) {
    return `[${value.toArray().map(lingoString).join(", ")}]`;
  }
  if (value instanceof LingoPropList) {
    if (value.length === 0) {
      return "[:]";
    }
    return "[:]";
  }
  return String(value);
}

// --- Imperative accessor stubs (execution tail) ----------------------------------
// These mark the API surface the emitted Lingo uses to reach into the live player. They throw
// LingoNotImplemented today because wiring them is the Stage 7 execution work; the runtime must
// not pretend to drive the player before that wiring is validated against C++.

// --- Lingo host: the bridge between transpiled TS handlers and the live player -----------

/** Host services the transpiled TS handlers use to mutate the live score/cast/state. */
export interface LingoHost {
  /** Read a sprite property (locH/locV/loc/member/locZ/visible/etc.). */
  getSpriteProp(channel: number, prop: string): LingoValue;
  /** Write a sprite property. */
  setSpriteProp(channel: number, prop: string, value: LingoValue): void;
  /** Look up a cast member by number or name, optionally scoped to a cast lib. Returns a member token. */
  getMember(numOrName: LingoValue, castLib?: LingoValue): LingoValue;
  /** Read a property of a member token returned by getMember. */
  getMemberProp(member: LingoValue, prop: string): LingoValue;
  /** Write a property of a member token. */
  setMemberProp(member: LingoValue, prop: string, value: LingoValue): void;
  /** Read a global variable. */
  getGlobal(name: string): LingoValue;
  /** Write a global variable. */
  setGlobal(name: string, value: LingoValue): void;
  /** Read a `the <name>` property (the frame, the mouseH, etc.). */
  getThe(name: string): LingoValue;
  /** Write a `the <name>` property. */
  setThe(name: string, value: LingoValue): void;
  /** Call a builtin by name (random, go, marker, point, sendAllSprites, etc.). */
  callBuiltin(name: string, args: LingoValue[]): LingoValue;
  /** Current 1-based frame number. */
  currentFrame(): number;
  /** Navigate to a frame or label. */
  go(target: LingoValue): void;
}

let activeHost: LingoHost | null = null;

/** Set the host that transpiled handlers talk to. The host is set per execution context. */
export function setLingoHost(host: LingoHost | null): void {
  activeHost = host;
}

/** @internal testing hook. */
export function getLingoHost(): LingoHost | null {
  return activeHost;
}

function requireHost(): LingoHost {
  if (!activeHost) {
    throw new LingoNotImplemented(
      "No LingoHost is set — transpiled handlers cannot run without a live player host.",
    );
  }
  return activeHost;
}

/** Returned by `sprite(n)`. Supports both `set the locH of sprite 5 to 100` (transpiled to
 * `sprite(5).locH = 100`) and `the locH of sprite 5` (transpiled to `sprite(5).locH`). */
export class LingoSpriteProxy {
  constructor(
    public readonly num: number,
    public readonly host: LingoHost,
  ) {}

  private getNum(name: string): number {
    return float(this.host.getSpriteProp(this.num, name));
  }

  private setNum(name: string, value: LingoValue): void {
    this.host.setSpriteProp(this.num, name, value);
  }

  get locH(): number { return this.getNum("locH"); }
  set locH(v: LingoValue) { this.setNum("locH", v); }

  get locV(): number { return this.getNum("locV"); }
  set locV(v: LingoValue) { this.setNum("locV", v); }

  get locZ(): number { return this.getNum("locZ"); }
  set locZ(v: LingoValue) { this.setNum("locZ", v); }

  get width(): number { return this.getNum("width"); }
  set width(v: LingoValue) { this.setNum("width", v); }

  get height(): number { return this.getNum("height"); }
  set height(v: LingoValue) { this.setNum("height", v); }

  get visible(): boolean { return !!this.host.getSpriteProp(this.num, "visible"); }
  set visible(v: LingoValue) { this.host.setSpriteProp(this.num, "visible", v); }

  get ink(): number { return this.getNum("ink"); }
  set ink(v: LingoValue) { this.setNum("ink", v); }

  get blend(): number { return this.getNum("blend"); }
  set blend(v: LingoValue) { this.setNum("blend", v); }

  get flipH(): boolean { return !!this.host.getSpriteProp(this.num, "flipH"); }
  set flipH(v: LingoValue) { this.host.setSpriteProp(this.num, "flipH", v); }

  get flipV(): boolean { return !!this.host.getSpriteProp(this.num, "flipV"); }
  set flipV(v: LingoValue) { this.host.setSpriteProp(this.num, "flipV", v); }

  get rotation(): number { return this.getNum("rotation"); }
  set rotation(v: LingoValue) { this.setNum("rotation", v); }

  get skew(): number { return this.getNum("skew"); }
  set skew(v: LingoValue) { this.setNum("skew", v); }

  get member(): LingoValue { return this.host.getSpriteProp(this.num, "member"); }
  set member(v: LingoValue) { this.host.setSpriteProp(this.num, "member", v); }

  get palette(): LingoValue { return this.host.getSpriteProp(this.num, "palette"); }
  set palette(v: LingoValue) { this.host.setSpriteProp(this.num, "palette", v); }

  get cursor(): LingoValue { return this.host.getSpriteProp(this.num, "cursor"); }
  set cursor(v: LingoValue) { this.host.setSpriteProp(this.num, "cursor", v); }

  /** `the loc of sprite n` / `set the loc of sprite n to point(x,y)` */
  get loc(): LingoPointProxy {
    return new LingoPointProxy(
      () => this.locH,
      () => this.locV,
      (v) => { this.locH = v; },
      (v) => { this.locV = v; },
    );
  }
  set loc(v: LingoValue) {
    const p = lingoPointToXY(v);
    this.locH = p.x;
    this.locV = p.y;
  }
}

/** Returned by `member(...)` and by `sprite(...).member`. */
export class LingoMemberProxy {
  constructor(
    public readonly token: LingoValue,
    public readonly host: LingoHost,
  ) {}

  get name(): string {
    const v = this.host.getMemberProp(this.token, "name");
    return typeof v === "string" ? v : lingoString(v);
  }
  set name(v: LingoValue) { this.host.setMemberProp(this.token, "name", v); }

  get text(): LingoValue { return this.host.getMemberProp(this.token, "text"); }
  set text(v: LingoValue) { this.host.setMemberProp(this.token, "text", v); }
}

/** `point(x,y)` proxy used by `sprite(...).loc`. */
class LingoPointProxy {
  constructor(
    private getX: () => number,
    private getY: () => number,
    private setX: (v: LingoValue) => void,
    private setY: (v: LingoValue) => void,
  ) {}
  get x(): number { return this.getX(); }
  set x(v: LingoValue) { this.setX(v); }
  get y(): number { return this.getY(); }
  set y(v: LingoValue) { this.setY(v); }
}

function lingoPointToXY(v: LingoValue): { x: number; y: number } {
  if (v instanceof LingoPointProxy) {
    return { x: v.x, y: v.y };
  }
  if (v instanceof LingoList && v.length >= 2) {
    return { x: float(v.get(1)), y: float(v.get(2)) };
  }
  if (Array.isArray(v) && v.length >= 2) {
    return { x: float(v[0]), y: float(v[1]) };
  }
  return { x: 0, y: 0 };
}

/** `sprite(n)` — returns a live proxy wired to the current host. */
export function sprite(num: number): LingoSpriteProxy {
  return new LingoSpriteProxy(num, requireHost());
}

/** `member(numOrName, castLib?)` — returns a live member proxy. */
export function member(numOrName: LingoValue, castLib?: LingoValue): LingoMemberProxy {
  const host = requireHost();
  return new LingoMemberProxy(host.getMember(numOrName, castLib), host);
}

/** `the <name>` / `the <name> of <obj>` entry point for simple global properties. */
export function theProperty(name: string): LingoValue {
  return requireHost().getThe(name);
}

/** `set the <name> to <value>` entry point. */
export function setTheProperty(name: string, value: LingoValue): void {
  requireHost().setThe(name, value);
}

/** Call a Lingo builtin through the host. */
export function callBuiltin(name: string, ...args: LingoValue[]): LingoValue {
  return requireHost().callBuiltin(name, args);
}

/** `me` context: property ivars and the spriteNum for behavior instances. */
export interface LingoMe {
  spriteNum: number;
  props: Map<string, LingoValue>;
}

/** Create a `me` object for a behavior instance on a given channel. */
export function createMe(spriteNum: number): LingoMe {
  const props = new Map<string, LingoValue>();
  return new Proxy(
    { spriteNum, props },
    {
      get(target, prop) {
        if (prop === "spriteNum") {
          return target.spriteNum;
        }
        if (prop === "props") {
          return target.props;
        }
        if (typeof prop === "string") {
          return target.props.get(prop);
        }
        return undefined;
      },
      set(target, prop, value) {
        if (prop === "spriteNum") {
          target.spriteNum = value;
          return true;
        }
        if (typeof prop === "string") {
          target.props.set(prop, value);
          return true;
        }
        return false;
      },
    },
  ) as unknown as LingoMe;
}

/** Property-ivar helper: `property foo` handlers read/write `me.foo`. */
export function meProp(me: LingoMe, name: string): LingoValue {
  if (!me.props.has(name)) {
    me.props.set(name, undefined);
  }
  return me.props.get(name);
}

export function setMeProp(me: LingoMe, name: string, value: LingoValue): void {
  me.props.set(name, value);
}

/** Symbol marker for Lingo `#foo` literals used by `sendAllSprites(#foo, ...)` etc. */
export function symbol(name: string): LingoSymbol {
  return { __lingoSymbol: true, name };
}

export interface LingoSymbol {
  readonly __lingoSymbol: true;
  readonly name: string;
}

export function isSymbol(value: LingoValue): value is LingoSymbol {
  return typeof value === "object" && value !== null && "__lingoSymbol" in value;
}

/** `the <prop> of <obj>` where the property name is dynamic (e.g. `the text of ...`). */
export function thePropOf(obj: LingoValue, prop: string): LingoValue {
  if (obj instanceof LingoSpriteProxy) {
    return obj.host.getSpriteProp(obj.num, prop);
  }
  if (obj instanceof LingoMemberProxy) {
    return obj.host.getMemberProp(obj.token, prop);
  }
  return undefined;
}

/** `set the <prop> of <obj> to <value>` dynamic setter. */
export function setThePropOf(obj: LingoValue, prop: string, value: LingoValue): void {
  if (obj instanceof LingoSpriteProxy) {
    obj.host.setSpriteProp(obj.num, prop, value);
    return;
  }
  if (obj instanceof LingoMemberProxy) {
    obj.host.setMemberProp(obj.token, prop, value);
    return;
  }
}

/** Variable reference placeholder (rare in source emission). */
export function varRef(_name: string): LingoValue {
  return undefined;
}

/** Object method call used by the transpiler for V4-style `obj.handler(args)`. */
export function callMethod(_name: string, ..._args: LingoValue[]): LingoValue {
  return undefined;
}

/** `new(<type>, args)` constructor helper. */
export function newObj(type: string, args: LingoValue): LingoValue {
  const host = getLingoHost();
  if (host) {
    return host.callBuiltin("newObj", [type, args]);
  }
  return undefined;
}

/** `sprite a intersects sprite b` */
export function spriteIntersects(_a: LingoValue, _b: LingoValue): boolean {
  return false;
}

/** `sprite a within sprite b` */
export function spriteWithin(_a: LingoValue, _b: LingoValue): boolean {
  return false;
}

/** Global variable read. */
export function globalVar(name: string): LingoValue {
  return requireHost().getGlobal(name);
}

/** Global variable write. */
export function setGlobal(name: string, value: LingoValue): void {
  requireHost().setGlobal(name, value);
}

// --- Lingo string chunk helpers ------------------------------------------------

let itemDelimiter = ",";

/** Set the `the itemDelimiter` value used by item chunk operations. */
export function setItemDelimiter(delimiter: string): void {
  itemDelimiter = (typeof delimiter === "string" && delimiter.length > 0) ? delimiter : ",";
}

/** Read the current `the itemDelimiter`. */
export function getItemDelimiter(): string {
  return itemDelimiter;
}

function lingoValueToString(value: LingoValue): string {
  if (value instanceof LingoMemberProxy) {
    const v = value.text;
    return v === undefined || v === null ? "" : lingoString(v);
  }
  return lingoString(value);
}

function chunkTypeName(type: string): string {
  return type.toLowerCase();
}

function splitChunks(value: string, type: string): string[] {
  switch (chunkTypeName(type)) {
    case "char":
      return value === "" ? [] : Array.from(value);
    case "word":
      return value.trim() === "" ? [] : value.trim().split(/\s+/);
    case "item":
      return value === "" ? [] : value.split(itemDelimiter);
    case "line":
      return value === "" ? [] : value.split(/\r?\n/);
    default:
      return [value];
  }
}

function joinChunks(parts: string[], type: string): string {
  switch (chunkTypeName(type)) {
    case "char":
      return parts.join("");
    case "word":
      return parts.join(" ");
    case "item":
      return parts.join(itemDelimiter);
    case "line":
      return parts.join("\n");
    default:
      return parts.join("");
  }
}

/** `char 1 of s`, `item 2 to 4 of s`, `line figure of field f`, etc. */
export function chunkOf(
  value: LingoValue,
  type?: string,
  first?: LingoValue,
  last?: LingoValue,
): LingoValue {
  const s = lingoValueToString(value);
  if (!type) {
    return s;
  }
  const a = first === undefined ? 1 : integer(first);
  const b = last === undefined ? a : integer(last);
  if (a < 1 || b < a) {
    return "";
  }
  const parts = splitChunks(s, type);
  if (a > parts.length) {
    return "";
  }
  return joinChunks(parts.slice(a - 1, b), type);
}

/** `the number of chars/words/items/lines in s`. */
export function chunkCount(value: LingoValue, type: string): number {
  return splitChunks(lingoValueToString(value), type).length;
}

/** `the last char/word/item/line of s`. */
export function lastChunk(value: LingoValue, type: string): LingoValue {
  const parts = splitChunks(lingoValueToString(value), type);
  return parts.length > 0 ? parts[parts.length - 1] : "";
}

/** `delete chunk of string` — no-op stub. */
export function deleteChunk(_value: LingoValue): void {
  // Chunk deletion (e.g. `delete char 1 of data`) is not yet ported.
}

/** `charToNum(s)` — ASCII code of the first character (0 for empty). */
export function charToNum(value: LingoValue): number {
  const s = lingoValueToString(value);
  return s.length > 0 ? s.charCodeAt(0) : 0;
}

/** `numToChar(n)` — character from a Unicode code point. */
export function numToChar(value: LingoValue): string {
  const n = integer(value);
  if (n < 0 || n > 0x10FFFF) {
    return "";
  }
  return String.fromCodePoint(n);
}

/** `add value to list` — append to a LingoList or array. */
export function add(list: LingoValue, value: LingoValue): void {
  if (list instanceof LingoList) {
    list.add(value);
  } else if (Array.isArray(list)) {
    (list as LingoValue[]).push(value);
  }
}

/** `getPropAt(list, index)` — return the key at position `index` in a propList. */
export function getPropAt(list: LingoValue, index: LingoValue): LingoValue {
  const i = integer(index);
  if (list instanceof LingoPropList) {
    return list.keyAt(i);
  }
  if (Array.isArray(list)) {
    return (list as LingoValue[])[i - 1];
  }
  return undefined;
}

function normalizeString(value: LingoValue): string {
  if (value === undefined || value === null) {
    return "";
  }
  return String(value).toLowerCase();
}

/** Lingo `haystack contains needle` — case-insensitive substring test. */
export function contains(haystack: LingoValue, needle: LingoValue): boolean {
  return normalizeString(haystack).includes(normalizeString(needle));
}

/** Lingo `haystack starts needle` — case-insensitive prefix test. */
export function starts(haystack: LingoValue, needle: LingoValue): boolean {
  return normalizeString(haystack).startsWith(normalizeString(needle));
}

/** Menu / sound property helpers — stubs. */
export function menuProp(_menu: LingoValue, _prop: number): LingoValue { return undefined; }
export function menuItemProp(_menu: LingoValue, _item: LingoValue, _prop: number): LingoValue { return undefined; }
export function soundProp(_sound: LingoValue, _prop: number): LingoValue { return undefined; }

// --- Transpiler-emitted helpers -------------------------------------------------

/** Return statement used by the transpiler (actual TS `return` cannot always be emitted). */
export function _return(value?: LingoValue): LingoValue {
  return value;
}

/** Alias for the transpiler's `new(...)` calls. */
export const _new = newObj;

/** Alias for the transpiler's internal symbol constructor. */
export const _symbol = symbol;

// --- Common Lingo utility builtins (stubs that keep handlers running) ----------

export function voidp(value: LingoValue): boolean {
  return value === undefined || value === null;
}

export function listp(value: LingoValue): boolean {
  return value instanceof LingoList || Array.isArray(value);
}

export function stringp(value: LingoValue): boolean {
  return typeof value === "string";
}

export function count(value: LingoValue): number {
  if (value instanceof LingoList || value instanceof LingoPropList) {
    return value.length;
  }
  if (Array.isArray(value)) {
    return value.length;
  }
  return 0;
}

export function getAt(value: LingoValue, index: LingoValue): LingoValue {
  const i = integer(index);
  if (value instanceof LingoList) {
    return value.get(i);
  }
  if (Array.isArray(value)) {
    return value[i - 1];
  }
  return undefined;
}

export function setAt(value: LingoValue, index: LingoValue, item: LingoValue): void {
  const i = integer(index);
  if (value instanceof LingoList) {
    value.set(i, item);
  } else if (Array.isArray(value)) {
    (value as LingoValue[])[i - 1] = item;
  }
}

function keyName(key: LingoValue): string | null {
  if (typeof key === "string") {
    return key;
  }
  if (isSymbol(key)) {
    return key.name;
  }
  return null;
}

export function getProp(value: LingoValue, key: LingoValue): LingoValue {
  const name = keyName(key);
  if (name === null) {
    return undefined;
  }
  if (value instanceof LingoPropList) {
    return value.get(name);
  }
  if (typeof value === "object" && value !== null && name in value) {
    return (value as Record<string, LingoValue>)[name];
  }
  return undefined;
}

export function getaProp(value: LingoValue, key: LingoValue): LingoValue {
  return getProp(value, key);
}

export function addProp(list: LingoValue, key: LingoValue, value?: LingoValue): void {
  if (list instanceof LingoList) {
    list.add(value ?? key);
    return;
  }
  const name = keyName(key);
  if (name !== null && list instanceof LingoPropList) {
    list.add(name, value ?? undefined);
  }
}

export function deleteProp(list: LingoValue, key: LingoValue): void {
  const name = keyName(key);
  if (name !== null && list instanceof LingoPropList) {
    list.remove(name);
  }
}

export function setProp(list: LingoValue, key: LingoValue, value?: LingoValue): void {
  const name = keyName(key);
  if (name === null) {
    return;
  }
  if (list instanceof LingoPropList) {
    list.add(name, value ?? undefined);
    return;
  }
  if (typeof list === "object" && list !== null) {
    (list as Record<string, LingoValue>)[name] = value ?? undefined;
  }
}

/** `sendSprite(channel, #symbol, ...)` — dispatch a symbol handler on a sprite channel. */
export function sendSprite(channel: LingoValue, sym: LingoValue, ...args: LingoValue[]): LingoValue {
  const host = getLingoHost();
  if (host) {
    return host.callBuiltin("sendSprite", [channel, sym, ...args]);
  }
  return undefined;
}

/** `put <expr>` statement helper — no-op in the browser runtime. */
export function put(..._values: LingoValue[]): void {
  // No-op; mirrors Director's debug-output `put`.
}

// --- Common Lingo value builtins -----------------------------------------------

/** `duplicate(value)` — deep copy for LingoList / LingoPropList, identity otherwise. */
export function duplicate(value: LingoValue): LingoValue {
  if (value instanceof LingoList) {
    return new LingoList(value.toArray().map(duplicate));
  }
  if (value instanceof LingoPropList) {
    const copy = new LingoPropList();
    for (let i = 1; i <= value.length; i++) {
      const key = value.keyAt(i);
      if (typeof key === "string") {
        copy.add(key, duplicate(value.get(key)));
      }
    }
    return copy;
  }
  return value;
}

/** `sort(list)` — sort a list or propList in place. */
export function sort(list: LingoValue): void {
  if (list instanceof LingoList) {
    const arr = list.toArray();
    arr.sort((a, b) => {
      if (typeof a === "number" && typeof b === "number") return a - b;
      return String(a).localeCompare(String(b));
    });
    list.clear();
    for (const item of arr) list.add(item);
  } else if (list instanceof LingoPropList) {
    const entries: [string, LingoValue][] = [];
    for (let i = 1; i <= list.length; i++) {
      const key = list.keyAt(i);
      if (typeof key === "string") {
        entries.push([key, list.get(key)]);
      }
    }
    entries.sort((a, b) => a[0].localeCompare(b[0]));
    for (const [k, v] of entries) list.add(k, v);
  }
}

/** `paletteIndex(n)` — stub; returns the index unchanged. */
export function paletteIndex(value: LingoValue): LingoValue {
  return value;
}

/** `abs(n)` — absolute value. */
export function abs(value: LingoValue): number {
  return Math.abs(float(value));
}

/** `sqrt(n)` — square root. */
export function sqrt(value: LingoValue): number {
  return Math.sqrt(float(value));
}

/** `atan(n)` — arctangent in radians. */
export function atan(value: LingoValue): number {
  return Math.atan(float(value));
}

