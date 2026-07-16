// Color reference: either a direct RGB value or a palette index. Port of
// cpp/include/libreshockwave/bitmap/ColorRef.hpp and cpp/src/bitmap/ColorRef.cpp.
//
// Note: the palette-index resolution path falls back to the system Mac palette when no palette is
// supplied (matching the C++ runtime). That fallback depends on the built-in palette tables, which
// are ported in Stage 3; until then, palette-index ColorRefs without an explicit palette throw.

import { Palette } from "./Palette.js";

function clampByte(value: number): number {
  return Math.max(0, Math.min(255, value | 0));
}

export class Rgb {
  readonly r: number;
  readonly g: number;
  readonly b: number;

  constructor(r: number, g: number, b: number) {
    this.r = clampByte(r);
    this.g = clampByte(g);
    this.b = clampByte(b);
  }

  static fromPacked(rgb: number): Rgb {
    return new Rgb((rgb >>> 16) & 0xff, (rgb >>> 8) & 0xff, rgb & 0xff);
  }

  static fromHex(hex: string): Rgb {
    let value = hex;
    if (value.startsWith("#")) {
      value = value.slice(1);
    }
    if (value.length === 0 || value.length > 6 || !/^[0-9a-fA-F]+$/.test(value)) {
      throw new Error("Invalid RGB hex color");
    }
    return Rgb.fromPacked(Number.parseInt(value, 16));
  }

  toPacked(): number {
    return ((this.r << 16) | (this.g << 8) | this.b) >>> 0;
  }

  toArgb(): number {
    return (0xff000000 | this.toPacked()) >>> 0;
  }

  static black(): Rgb {
    return new Rgb(0, 0, 0);
  }

  static white(): Rgb {
    return new Rgb(255, 255, 255);
  }
}

export class PaletteIndex {
  readonly index: number;

  constructor(index: number) {
    this.index = clampByte(index);
  }

  resolve(palette?: Palette | null): Rgb {
    const resolved = palette ?? null;
    if (resolved === null) {
      // C++ falls back to Palette::systemMacPalette(); ported in Stage 3.
      throw new Error("PaletteIndex.resolve without a palette requires the system Mac palette (Stage 3)");
    }
    const [r, g, b] = resolved.getRGB(this.index);
    return new Rgb(r, g, b);
  }
}

type ColorRefValue =
  | { tag: "rgb"; value: Rgb }
  | { tag: "index"; value: PaletteIndex };

export class ColorRef {
  private readonly value_: ColorRefValue;

  constructor(rgb: Rgb);
  constructor(index: PaletteIndex);
  constructor(arg: Rgb | PaletteIndex) {
    if (arg instanceof Rgb) {
      this.value_ = { tag: "rgb", value: arg };
    } else {
      this.value_ = { tag: "index", value: arg };
    }
  }

  toRgb(palette?: Palette | null): Rgb {
    if (this.value_.tag === "rgb") {
      return this.value_.value;
    }
    return this.value_.value.resolve(palette);
  }

  toNearestPaletteIndex(palette?: Palette | null): number {
    if (this.value_.tag === "index") {
      return this.value_.value.index;
    }
    const resolved = palette ?? null;
    if (resolved === null) {
      // C++ falls back to Palette::systemMacPalette(); ported in Stage 3.
      throw new Error("ColorRef.toNearestPaletteIndex without a palette requires the system Mac palette (Stage 3)");
    }
    return resolved.nearestIndex(this.value_.value.toPacked());
  }

  isRgb(): boolean {
    return this.value_.tag === "rgb";
  }

  isPaletteIndex(): boolean {
    return this.value_.tag === "index";
  }
}