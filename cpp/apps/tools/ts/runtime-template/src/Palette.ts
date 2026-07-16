// Color palette. Port of cpp/include/libreshockwave/bitmap/Palette.hpp and the core of
// cpp/src/bitmap/Palette.cpp.
//
// Stage 1 scope: the data-driven Palette (constructor + getColor/getRGB/size/nearestIndex +
// name/colors) and the symbol-id constants + symbol-name mapping are ported here. The
// built-in system palette *color tables* (systemMac/rainbow/grayscale/metallic/systemWin) are
// large literal data and are ported in Stage 3 alongside indexed-media / palette-remap support,
// where they are first exercised by the differential harness. The factory methods below throw
// until then.

export const PaletteId = {
  SYSTEM_MAC: -1,
  RAINBOW: -2,
  GRAYSCALE: -3,
  PASTELS: -4,
  VIVID: -5,
  NTSC: -6,
  METALLIC: -7,
  SYSTEM_WIN: -101,
  SYSTEM_WIN_DIR4: -102,
} as const;

export type PaletteId = (typeof PaletteId)[keyof typeof PaletteId];

export class Palette {
  private readonly colors_: number[];
  private readonly name_: string;
  private readonly nearestCache_: Map<number, number> = new Map();

  constructor(colors: number[], name: string) {
    this.colors_ = colors.slice();
    this.name_ = name;
  }

  /** Returns the ARGB color at index, or 0 when out of range. */
  getColor(index: number): number {
    if (index >= 0 && index < this.colors_.length) {
      return this.colors_[index] >>> 0;
    }
    return 0;
  }

  /** Returns the [r, g, b] triplet at index. */
  getRGB(index: number): [number, number, number] {
    const color = this.getColor(index);
    return [(color >>> 16) & 0xff, (color >>> 8) & 0xff, color & 0xff];
  }

  size(): number {
    return this.colors_.length;
  }

  /**
   * Index of the palette entry nearest to `rgb` by squared RGB Euclidean distance, first match
   * wins, with an early break on an exact match. Cached per target. Mirrors Palette::nearestIndex.
   */
  nearestIndex(rgb: number): number {
    const target = rgb & 0x00ffffff;
    const cached = this.nearestCache_.get(target);
    if (cached !== undefined) {
      return cached;
    }

    const tr = (target >>> 16) & 0xff;
    const tg = (target >>> 8) & 0xff;
    const tb = target & 0xff;
    let bestIndex = 0;
    let bestDistance = Number.MAX_SAFE_INTEGER;

    for (let index = 0; index < this.colors_.length; ++index) {
      const color = this.colors_[index] & 0x00ffffff;
      const dr = tr - ((color >>> 16) & 0xff);
      const dg = tg - ((color >>> 8) & 0xff);
      const db = tb - (color & 0xff);
      const distance = dr * dr + dg * dg + db * db;
      if (distance < bestDistance) {
        bestDistance = distance;
        bestIndex = index;
        if (distance === 0) {
          break;
        }
      }
    }

    this.nearestCache_.set(target, bestIndex);
    return bestIndex;
  }

  name(): string {
    return this.name_;
  }

  colors(): readonly number[] {
    return this.colors_;
  }

  /** Director symbol name for a built-in palette id, or undefined. */
  static builtInSymbolName(paletteId: number): string | undefined {
    switch (paletteId) {
      case PaletteId.SYSTEM_MAC:
        return "systemMac";
      case PaletteId.RAINBOW:
        return "rainbow";
      case PaletteId.GRAYSCALE:
        return "grayscale";
      case PaletteId.PASTELS:
        return "pastels";
      case PaletteId.VIVID:
        return "vivid";
      case PaletteId.NTSC:
        return "ntsc";
      case PaletteId.METALLIC:
        return "metallic";
      case PaletteId.SYSTEM_WIN:
      case PaletteId.SYSTEM_WIN_DIR4:
        return "systemWin";
      default:
        return undefined;
    }
  }

  static normalizeBuiltInSymbolName(symbolName: string): string | undefined {
    const key = symbolName.trim().toLowerCase();
    if (key === "systemmac") return "systemMac";
    if (key === "rainbow") return "rainbow";
    if (key === "grayscale" || key === "greyscale") return "grayscale";
    if (key === "pastels") return "pastels";
    if (key === "vivid") return "vivid";
    if (key === "ntsc") return "ntsc";
    if (key === "metallic") return "metallic";
    if (key === "systemwin" || key === "systemwindows") return "systemWin";
    return undefined;
  }

  // The built-in system palette color tables (systemMac/rainbow/grayscale/metallic/systemWin)
  // are ported in Stage 3 with indexed-media / palette-remap support. Until then these
  // factories are intentionally unavailable.
  static builtInBySymbolName(_symbolName: string): Palette | undefined {
    throw new Error("Palette.builtInBySymbolName: built-in palette tables are ported in Stage 3");
  }

  static builtIn(_paletteId: number): Palette {
    throw new Error("Palette.builtIn: built-in palette tables are ported in Stage 3");
  }
}