import { describe, it, expect, beforeEach } from "vitest";
import {
  integer,
  float,
  lingoString,
  LingoList,
  LingoPropList,
  LingoNotImplemented,
  sprite,
  member,
  theProperty,
  setLingoHost,
  createMe,
  meProp,
  setMeProp,
  type LingoHost,
  type LingoValue,
} from "../src/lingo-runtime.js";

describe("integer", () => {
  it("truncates toward zero like C++ static_cast<int>", () => {
    expect(integer(3.9)).toBe(3);
    expect(integer(-3.9)).toBe(-3);
    expect(integer(3)).toBe(3);
    expect(integer(-3)).toBe(-3);
  });
  it("maps booleans to 1/0", () => {
    expect(integer(true)).toBe(1);
    expect(integer(false)).toBe(0);
  });
  it("parses strings, tolerating surrounding whitespace", () => {
    expect(integer("  12 ")).toBe(12);
    expect(integer("-7.8")).toBe(-7);
    expect(integer("not-a-number")).toBe(0);
  });
  it("yields 0 for VOID/lists", () => {
    expect(integer(undefined)).toBe(0);
    expect(integer(new LingoList([5]))).toBe(0);
  });
});

describe("float", () => {
  it("promotes numbers and parses strings", () => {
    expect(float(3)).toBe(3);
    expect(float("  2.5 ")).toBe(2.5);
    expect(float(true)).toBe(1);
    expect(float("nope")).toBe(0);
  });
});

describe("LingoList (1-indexed)", () => {
  it("reads/writes at 1-based indices and rejects 0 / out-of-range", () => {
    const list = new LingoList(["a", "b", "c"]);
    expect(list.length).toBe(3);
    expect(list.get(1)).toBe("a");
    expect(list.get(3)).toBe("c");
    expect(() => list.get(0)).toThrow(RangeError);
    expect(() => list.get(4)).toThrow(RangeError);
    list.set(2, "B");
    expect(list.get(2)).toBe("B");
    expect(() => list.set(0, "x")).toThrow(RangeError);
  });
  it("add appends at the next index", () => {
    const list = new LingoList([1]);
    list.add(2);
    expect(list.length).toBe(2);
    expect(list.get(2)).toBe(2);
  });
});

describe("LingoPropList", () => {
  it("updates duplicate keys in place and preserves insertion order", () => {
    const p = new LingoPropList();
    p.add("a", 1);
    p.add("b", 2);
    p.add("a", 10);
    expect(p.length).toBe(2);
    expect(p.get("a")).toBe(10);
    expect(p.get("b")).toBe(2);
    expect(p.has("c")).toBe(false);
    expect(p.get("c")).toBeUndefined();
  });
});

describe("lingoString", () => {
  it("renders VOID/booleans/lists Director-style", () => {
    expect(lingoString(undefined)).toBe("VOID");
    expect(lingoString(true)).toBe("TRUE");
    expect(lingoString(false)).toBe("FALSE");
    expect(lingoString(new LingoList([1, "x"]))).toBe("[1, x]");
    expect(lingoString(new LingoPropList())).toBe("[:]");
  });
});

describe("imperative accessors without a host", () => {
  beforeEach(() => setLingoHost(null));
  it("throw LingoNotImplemented when no host is set", () => {
    expect(() => sprite(1)).toThrow(LingoNotImplemented);
    expect(() => member(1)).toThrow(LingoNotImplemented);
    expect(() => theProperty("mouseLevel")).toThrow(LingoNotImplemented);
  });
});

describe("LingoHost wiring", () => {
  it("sprite proxy reads/writes sprite properties through the host", () => {
    const props: Record<string, LingoValue> = {};
    const host: LingoHost = {
      getSpriteProp: (_channel, prop) => props[prop] ?? 0,
      setSpriteProp: (_channel, prop, value) => { props[prop] = value; },
      getMember: () => undefined,
      getMemberProp: () => undefined,
      setMemberProp: () => undefined,
      getGlobal: () => undefined,
      setGlobal: () => undefined,
      getThe: () => undefined,
      setThe: () => undefined,
      callBuiltin: () => undefined,
      currentFrame: () => 1,
      go: () => undefined,
    };
    setLingoHost(host);
    sprite(5).locH = 100;
    sprite(5).locV = 200;
    expect(sprite(5).locH).toBe(100);
    expect(sprite(5).locV).toBe(200);
    setLingoHost(null);
  });

  it("me property ivars read/write via helpers", () => {
    const me = createMe(7);
    expect(meProp(me, "locH")).toBeUndefined();
    setMeProp(me, "locH", 42);
    expect(meProp(me, "locH")).toBe(42);
    expect(me.spriteNum).toBe(7);
  });

  it("LingoNotImplemented carries its name", () => {
    const e = new LingoNotImplemented("x");
    expect(e).toBeInstanceOf(Error);
    expect(e.name).toBe("LingoNotImplemented");
    expect(e.message).toBe("x");
  });
});