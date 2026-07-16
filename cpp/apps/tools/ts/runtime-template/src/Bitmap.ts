// Bitmap: the universal ARGB raster surface. Port of
// cpp/include/libreshockwave/bitmap/Bitmap.hpp and cpp/src/bitmap/Bitmap.cpp.
//
// Storage mirrors the C++ std::vector<uint32_t> as a Uint32Array of packed ARGB pixels
// (alpha << 24 | red << 16 | green << 8 | blue). Palette-index parallel storage
// (paletteIndices) uses a Uint8Array. All semantics follow the C++ reference; the
// differential harness validates parity.

import { Palette } from "./Palette.js";

export interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export class Bitmap {
  private width_: number;
  private height_: number;
  private bitDepth_: number;
  private pixels_: Uint32Array;
  private paletteIndices_: Uint8Array | null = null;
  private scriptModified_ = false;
  private nativeAlpha_ = false;
  private rectangularMedia_ = false;
  private textRendered_ = false;
  private scriptFillBacking_ = false;
  private preserveScriptFillBacking_ = false;
  private imagePalette_: Palette | null = null;
  private paletteRefCastLib_ = -1;
  private paletteRefMemberNum_ = -1;
  private paletteRefSystemName_: string | null = null;
  private hasAnchorPoint_ = false;
  private anchorX_ = 0;
  private anchorY_ = 0;

  constructor(width: number, height: number, bitDepth: number, pixels?: Uint32Array | number[]) {
    if (width < 0 || height < 0) {
      throw new Error("Bitmap dimensions must be non-negative");
    }
    this.width_ = width;
    this.height_ = height;
    this.bitDepth_ = bitDepth;
    const expected = Math.max(0, width) * Math.max(0, height);
    if (pixels === undefined) {
      this.pixels_ = new Uint32Array(expected);
    } else {
      if (pixels.length !== expected) {
        throw new Error("Bitmap pixel count does not match dimensions");
      }
      this.pixels_ = pixels instanceof Uint32Array ? pixels : new Uint32Array(pixels);
    }
  }

  width(): number {
    return this.width_;
  }
  height(): number {
    return this.height_;
  }
  bitDepth(): number {
    return this.bitDepth_;
  }
  pixels(): Uint32Array {
    return this.pixels_;
  }

  // -- alpha / media flags -------------------------------------------------

  isScriptModified(): boolean {
    return this.scriptModified_;
  }
  markScriptModified(): void {
    this.scriptModified_ = true;
  }
  clearScriptModified(): void {
    this.scriptModified_ = false;
  }

  hasTransparentPixels(): boolean {
    if (this.bitDepth_ < 32) {
      return false;
    }
    for (let i = 0; i < this.pixels_.length; ++i) {
      if ((this.pixels_[i] >>> 24) === 0) {
        return true;
      }
    }
    return false;
  }

  hasTranslucentPixels(): boolean {
    if (this.bitDepth_ < 32) {
      return false;
    }
    for (let i = 0; i < this.pixels_.length; ++i) {
      const alpha = (this.pixels_[i] >>> 24) & 0xff;
      if (alpha > 0 && alpha < 255) {
        return true;
      }
    }
    return false;
  }

  hasDegenerateAlphaWithRgbContent(): boolean {
    if (this.bitDepth_ !== 32 || this.pixels_.length === 0) {
      return false;
    }
    let hasRgbContent = false;
    for (let i = 0; i < this.pixels_.length; ++i) {
      const pixel = this.pixels_[i];
      const alpha = (pixel >>> 24) & 0xff;
      if (alpha > 1) {
        return false;
      }
      if ((pixel & 0x00ffffff) !== 0) {
        hasRgbContent = true;
      }
    }
    return hasRgbContent;
  }

  hasNativeMatteAlpha(): boolean {
    return this.bitDepth_ === 32 && this.nativeAlpha_;
  }
  isNativeAlpha(): boolean {
    return this.nativeAlpha_;
  }
  setNativeAlpha(nativeAlpha: boolean): void {
    this.nativeAlpha_ = nativeAlpha;
  }
  isRectangularMedia(): boolean {
    return this.rectangularMedia_;
  }
  setRectangularMedia(rectangularMedia: boolean): void {
    this.rectangularMedia_ = rectangularMedia;
  }
  isTextRendered(): boolean {
    return this.textRendered_;
  }
  markTextRendered(): void {
    this.textRendered_ = true;
  }
  clearTextRendered(): void {
    this.textRendered_ = false;
  }
  hasScriptFillBacking(): boolean {
    return this.scriptFillBacking_;
  }
  markScriptFillBacking(): void {
    this.scriptFillBacking_ = true;
  }
  clearScriptFillBacking(): void {
    this.scriptFillBacking_ = false;
  }
  preservesScriptFillBacking(): boolean {
    return this.preserveScriptFillBacking_;
  }
  markPreserveScriptFillBacking(): void {
    this.preserveScriptFillBacking_ = true;
  }
  clearPreserveScriptFillBacking(): void {
    this.preserveScriptFillBacking_ = false;
  }

  copyWithNonNativeAlphaOpaque(): Bitmap {
    if (this.bitDepth_ !== 32 || this.nativeAlpha_ || !this.hasTransparentPixels()) {
      return this;
    }
    const opaque = this.copy();
    for (let i = 0; i < opaque.pixels_.length; ++i) {
      opaque.pixels_[i] = (0xff000000 | (opaque.pixels_[i] & 0x00ffffff)) >>> 0;
    }
    opaque.nativeAlpha_ = false;
    return opaque;
  }

  copyWithDegenerateAlphaOpaque(): Bitmap {
    if (!this.hasDegenerateAlphaWithRgbContent()) {
      return this;
    }
    const opaque = this.copy();
    for (let i = 0; i < opaque.pixels_.length; ++i) {
      opaque.pixels_[i] = (0xff000000 | (opaque.pixels_[i] & 0x00ffffff)) >>> 0;
    }
    opaque.nativeAlpha_ = false;
    return opaque;
  }

  copyWithDegenerateNativeAlphaOpaque(): Bitmap {
    if (!this.nativeAlpha_) {
      return this;
    }
    return this.copyWithDegenerateAlphaOpaque();
  }

  // -- palette metadata ----------------------------------------------------

  setImagePalette(palette: Palette | null): void {
    this.imagePalette_ = palette;
  }
  imagePalette(): Palette | null {
    return this.imagePalette_;
  }

  setPaletteIndices(paletteIndices: Uint8Array | number[]): void {
    this.paletteIndices_ = paletteIndices instanceof Uint8Array ? paletteIndices : new Uint8Array(paletteIndices);
  }
  clearPaletteIndices(): void {
    this.paletteIndices_ = null;
  }
  paletteIndices(): Uint8Array | null {
    return this.paletteIndices_;
  }
  paletteIndex(x: number, y: number): number | null {
    if (
      this.paletteIndices_ === null ||
      this.paletteIndices_.length !== this.pixels_.length ||
      x < 0 ||
      x >= this.width_ ||
      y < 0 ||
      y >= this.height_
    ) {
      return null;
    }
    return this.paletteIndices_[y * this.width_ + x];
  }

  setPixelPreservePaletteIndex(x: number, y: number, argb: number): void {
    if (x >= 0 && x < this.width_ && y >= 0 && y < this.height_) {
      this.pixels_[y * this.width_ + x] = argb >>> 0;
    }
  }

  fillRectPaletteIndex(x: number, y: number, w: number, h: number, index: number, argb: number): void {
    if (this.paletteIndices_ === null || this.paletteIndices_.length !== this.pixels_.length) {
      this.paletteIndices_ = new Uint8Array(this.pixels_.length);
    }
    let x2 = Math.min(x + w, this.width_);
    let y2 = Math.min(y + h, this.height_);
    x = Math.max(0, x);
    y = Math.max(0, y);
    if (x >= x2 || y >= y2) {
      return;
    }
    for (let py = y; py < y2; ++py) {
      for (let px = x; px < x2; ++px) {
        const offset = py * this.width_ + px;
        this.pixels_[offset] = argb >>> 0;
        this.paletteIndices_![offset] = index & 0xff;
      }
    }
  }

  /** Remaps indexed/RGB content to a new palette. Returns the number of changed pixels. */
  remapImagePalette(newPalette: Palette | null): number {
    const oldPalette = this.imagePalette_;
    this.imagePalette_ = newPalette;
    if (this.imagePalette_ === null || oldPalette === this.imagePalette_) {
      return 0;
    }
    if (this.paletteIndices_ !== null && this.paletteIndices_.length === this.pixels_.length) {
      let changed = 0;
      const max = this.imagePalette_.size();
      for (let i = 0; i < this.pixels_.length; ++i) {
        const alpha = (this.pixels_[i] >>> 24) & 0xff;
        if (alpha === 0) {
          continue;
        }
        const index = this.paletteIndices_![i] & 0xff;
        if (index >= max) {
          continue;
        }
        const newRgb = this.imagePalette_.getColor(index) & 0x00ffffff;
        if ((this.pixels_[i] & 0x00ffffff) !== newRgb) {
          this.pixels_[i] = ((alpha << 24) | newRgb) >>> 0;
          ++changed;
        }
      }
      return changed;
    }
    if (oldPalette === null) {
      return this.shouldQuantizeRgbFills() ? this.quantizeToImagePalette() : 0;
    }
    let changed = 0;
    const max = Math.min(oldPalette.size(), this.imagePalette_.size());
    for (let i = 0; i < this.pixels_.length; ++i) {
      const alpha = (this.pixels_[i] >>> 24) & 0xff;
      if (alpha === 0) {
        continue;
      }
      const rgb = this.pixels_[i] & 0x00ffffff;
      for (let index = 0; index < max; ++index) {
        if ((oldPalette.getColor(index) & 0x00ffffff) === rgb) {
          const newRgb = this.imagePalette_!.getColor(index) & 0x00ffffff;
          if (newRgb !== rgb) {
            this.pixels_[i] = ((alpha << 24) | newRgb) >>> 0;
            ++changed;
          }
          break;
        }
      }
    }
    return changed;
  }

  setPaletteRefCastMember(castLibNumber: number, memberNumber: number): void {
    this.paletteRefCastLib_ = castLibNumber;
    this.paletteRefMemberNum_ = memberNumber;
    this.paletteRefSystemName_ = null;
  }
  paletteRefCastLib(): number {
    return this.paletteRefCastLib_;
  }
  paletteRefMemberNum(): number {
    return this.paletteRefMemberNum_;
  }
  setPaletteRefSystemName(systemName: string): void {
    this.paletteRefSystemName_ = systemName;
    this.paletteRefCastLib_ = -1;
    this.paletteRefMemberNum_ = -1;
  }
  paletteRefSystemName(): string | null {
    return this.paletteRefSystemName_;
  }
  clearPaletteRefMetadata(): void {
    this.paletteRefCastLib_ = -1;
    this.paletteRefMemberNum_ = -1;
    this.paletteRefSystemName_ = null;
  }

  setAnchorPoint(x: number, y: number): void {
    this.hasAnchorPoint_ = true;
    this.anchorX_ = x;
    this.anchorY_ = y;
  }
  hasAnchorPoint(): boolean {
    return this.hasAnchorPoint_;
  }
  anchorX(): number {
    return this.anchorX_;
  }
  anchorY(): number {
    return this.anchorY_;
  }
  clearAnchorPoint(): void {
    this.hasAnchorPoint_ = false;
    this.anchorX_ = 0;
    this.anchorY_ = 0;
  }

  copyPaletteMetadataFrom(other: Bitmap | null): void {
    if (other === null) {
      this.imagePalette_ = null;
      this.paletteIndices_ = null;
      this.scriptModified_ = false;
      this.nativeAlpha_ = false;
      this.rectangularMedia_ = false;
      this.textRendered_ = false;
      this.scriptFillBacking_ = false;
      this.preserveScriptFillBacking_ = false;
      this.clearPaletteRefMetadata();
      this.clearAnchorPoint();
      return;
    }
    this.imagePalette_ = other.imagePalette_;
    this.paletteIndices_ = other.paletteIndices_ === null ? null : other.paletteIndices_.slice();
    this.scriptModified_ = other.scriptModified_;
    this.nativeAlpha_ = other.nativeAlpha_;
    this.rectangularMedia_ = other.rectangularMedia_;
    this.textRendered_ = other.textRendered_;
    this.scriptFillBacking_ = other.scriptFillBacking_;
    this.preserveScriptFillBacking_ = other.preserveScriptFillBacking_;
    this.paletteRefCastLib_ = other.paletteRefCastLib_;
    this.paletteRefMemberNum_ = other.paletteRefMemberNum_;
    this.paletteRefSystemName_ = other.paletteRefSystemName_;
    this.hasAnchorPoint_ = other.hasAnchorPoint_;
    this.anchorX_ = other.anchorX_;
    this.anchorY_ = other.anchorY_;
  }

  resolvePaletteIndex(index: number, fallback?: Palette | null): number {
    const palette = this.imagePalette_ ?? (fallback ?? null);
    if (palette !== null) {
      return palette.getColor(index & 0xff);
    }
    const gray = 255 - (index & 0xff);
    return ((gray << 16) | (gray << 8) | gray) >>> 0;
  }

  // -- pixel access --------------------------------------------------------

  getPixel(x: number, y: number): number {
    if (x < 0 || x >= this.width_ || y < 0 || y >= this.height_) {
      return 0;
    }
    return this.pixels_[y * this.width_ + x];
  }

  setPixel(x: number, y: number, argb: number): void {
    if (x >= 0 && x < this.width_ && y >= 0 && y < this.height_) {
      if (this.shouldQuantizeRgbFills()) {
        this.fillRectPaletteIndex(
          x,
          y,
          1,
          1,
          this.imagePalette_!.nearestIndex(argb),
          this.quantizeArgb(argb),
        );
        return;
      }
      this.clearPaletteIndices();
      this.pixels_[y * this.width_ + x] = argb >>> 0;
    }
  }

  setPixelRGB(x: number, y: number, r: number, g: number, b: number): void {
    this.setPixel(x, y, (0xff000000 | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)) >>> 0);
  }

  setPixelRGBA(x: number, y: number, r: number, g: number, b: number, a: number): void {
    this.setPixel(
      x,
      y,
      (((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)) >>> 0,
    );
  }

  fill(argb: number): void {
    if (this.shouldQuantizeRgbFills()) {
      this.fillRectPaletteIndex(0, 0, this.width_, this.height_, this.imagePalette_!.nearestIndex(argb), this.quantizeArgb(argb));
      return;
    }
    this.clearPaletteIndices();
    this.pixels_.fill(argb >>> 0);
  }

  fillRect(x: number, y: number, w: number, h: number, argb: number): void {
    if (this.shouldQuantizeRgbFills()) {
      this.fillRectPaletteIndex(x, y, w, h, this.imagePalette_!.nearestIndex(argb), this.quantizeArgb(argb));
      return;
    }
    this.clearPaletteIndices();
    let x2 = Math.min(x + w, this.width_);
    let y2 = Math.min(y + h, this.height_);
    x = Math.max(0, x);
    y = Math.max(0, y);
    if (x >= x2 || y >= y2) {
      return;
    }
    const packed = argb >>> 0;
    for (let py = y; py < y2; ++py) {
      for (let px = x; px < x2; ++px) {
        this.pixels_[py * this.width_ + px] = packed;
      }
    }
  }

  copy(): Bitmap {
    const result = new Bitmap(this.width_, this.height_, this.bitDepth_, this.pixels_.slice());
    result.copyPaletteMetadataFrom(this);
    return result;
  }

  getRegion(x: number, y: number, w: number, h: number): Bitmap {
    const result = new Bitmap(w, h, this.bitDepth_);
    result.copyPaletteMetadataFrom(this);
    let regionIndices: Uint8Array | null = null;
    if (this.paletteIndices_ !== null) {
      regionIndices = new Uint8Array(w * h);
    }
    for (let dy = 0; dy < h; ++dy) {
      const srcY = y + dy;
      if (srcY < 0 || srcY >= this.height_) {
        continue;
      }
      for (let dx = 0; dx < w; ++dx) {
        const srcX = x + dx;
        if (srcX < 0 || srcX >= this.width_) {
          continue;
        }
        const dstOffset = dy * w + dx;
        const srcOffset = srcY * this.width_ + srcX;
        result.pixels_[dstOffset] = this.pixels_[srcOffset];
        if (regionIndices !== null && this.paletteIndices_ !== null && srcOffset < this.paletteIndices_.length) {
          regionIndices![dstOffset] = this.paletteIndices_[srcOffset];
        }
      }
    }
    if (regionIndices !== null) {
      result.paletteIndices_ = regionIndices;
    }
    if (this.hasAnchorPoint_) {
      result.setAnchorPoint(this.anchorX_ - x, this.anchorY_ - y);
    }
    return result;
  }

  trimWhiteSpace(): Rect {
    let minX = this.width_;
    let minY = this.height_;
    let maxX = -1;
    let maxY = -1;
    for (let y = 0; y < this.height_; ++y) {
      for (let x = 0; x < this.width_; ++x) {
        const pixel = this.pixels_[y * this.width_ + x];
        if ((pixel >>> 24) === 0) {
          continue;
        }
        if ((pixel & 0x00ffffff) === 0x00ffffff) {
          continue;
        }
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
      }
    }
    if (maxX < 0) {
      return { left: 0, top: 0, right: 0, bottom: 0 };
    }
    return { left: minX, top: minY, right: maxX + 1, bottom: maxY + 1 };
  }

  toString(): string {
    return `Bitmap[${this.width_}x${this.height_}, ${this.bitDepth_}-bit]`;
  }

  static createPaletteSwatch(colors: number[] | readonly number[], swatchSize: number, columns?: number): Bitmap {
    if (colors.length === 0) {
      return new Bitmap(1, 1, 32);
    }
    const count = colors.length;
    const cols = columns !== undefined && columns > 0 ? columns : Math.ceil(Math.sqrt(count));
    const rows = Math.ceil(count / cols);
    const bitmap = new Bitmap(cols * swatchSize, rows * swatchSize, 32);
    bitmap.fill(0xffffffff);
    for (let i = 0; i < count; ++i) {
      const col = i % cols;
      const row = Math.floor(i / cols);
      bitmap.fillRect(
        col * swatchSize,
        row * swatchSize,
        swatchSize,
        swatchSize,
        (0xff000000 | (colors[i] & 0x00ffffff)) >>> 0,
      );
    }
    return bitmap;
  }

  static createPaletteSwatchForPalette(palette: Palette, swatchSize: number): Bitmap {
    return Bitmap.createPaletteSwatch(palette.colors(), swatchSize, 16);
  }

  // -- private --------------------------------------------------------------

  private shouldQuantizeRgbFills(): boolean {
    return this.bitDepth_ <= 8 && this.imagePalette_ !== null;
  }

  private quantizeArgb(argb: number): number {
    if (this.imagePalette_ === null) {
      return argb;
    }
    const alpha = (argb >>> 24) & 0xff;
    const index = this.imagePalette_.nearestIndex(argb);
    return ((alpha << 24) | (this.imagePalette_.getColor(index) & 0x00ffffff)) >>> 0;
  }

  private quantizeToImagePalette(): number {
    if (this.imagePalette_ === null) {
      return 0;
    }
    if (this.paletteIndices_ === null || this.paletteIndices_.length !== this.pixels_.length) {
      this.paletteIndices_ = new Uint8Array(this.pixels_.length);
    }
    let changed = 0;
    for (let i = 0; i < this.pixels_.length; ++i) {
      const pixel = this.pixels_[i];
      const alpha = (pixel >>> 24) & 0xff;
      if (alpha === 0) {
        continue;
      }
      const index = this.imagePalette_.nearestIndex(pixel);
      const quantized = ((alpha << 24) | (this.imagePalette_.getColor(index) & 0x00ffffff)) >>> 0;
      this.paletteIndices_![i] = index & 0xff;
      if (this.pixels_[i] !== quantized) {
        this.pixels_[i] = quantized;
        ++changed;
      }
    }
    return changed;
  }
}