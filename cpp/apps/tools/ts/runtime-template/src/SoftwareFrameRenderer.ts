// Software frame compositor. Faithful port of cpp/src/player/render/output/SoftwareFrameRenderer.cpp.
//
// Stage 1 scope: renderFrame + the 1:1 blitBitmap path + alphaComposite/alphaCompositePercent +
// isSpecialCompositingInk are ported here, which together cover the COPY / TRANSPARENT / BLEND
// (blend<100) inks at 1:1 scale. blitBitmapScaled is ported in Stage 2 (scaling/rotation) and
// compositeSpecialInk is ported in Stage 3 (ADD/SUBTRACT/REVERSE/GHOST/NOT_*/...); both throw until
// then so any path that reaches them fails loudly rather than silently producing wrong pixels.

import type { FrameSnapshot } from "./FrameSnapshot.js";
import { InkMode } from "./InkMode.js";
import { Bitmap } from "./Bitmap.js";
import { channel } from "./argb.js";

function opaqueRgb(r: number, g: number, b: number): number {
  return (0xff000000 | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)) >>> 0;
}

export function isSpecialCompositingInk(ink: InkMode): boolean {
  return (
    ink === InkMode.ADD_PIN ||
    ink === InkMode.ADD ||
    ink === InkMode.SUBTRACT_PIN ||
    ink === InkMode.SUBTRACT ||
    ink === InkMode.LIGHTEST ||
    ink === InkMode.DARKEST ||
    ink === InkMode.LIGHTEN ||
    ink === InkMode.REVERSE ||
    ink === InkMode.GHOST ||
    ink === InkMode.NOT_COPY ||
    ink === InkMode.NOT_TRANSPARENT ||
    ink === InkMode.NOT_REVERSE ||
    ink === InkMode.NOT_GHOST
  );
}

/** Composes a frame's sprites into a stage Bitmap. Mirrors SoftwareFrameRenderer::renderFrame. */
export function renderFrame(snapshot: FrameSnapshot, stageWidth: number, stageHeight: number): Bitmap {
  if (stageWidth < 0 || stageHeight < 0) {
    throw new Error("Stage dimensions must be non-negative");
  }
  const argb = new Uint32Array(stageWidth * stageHeight);

  if (snapshot.stageImage !== null) {
    const srcPixels = snapshot.stageImage.pixels();
    const srcW = snapshot.stageImage.width();
    const srcH = snapshot.stageImage.height();
    if (srcPixels.length > 0) {
      for (let y = 0; y < Math.min(srcH, stageHeight); ++y) {
        for (let x = 0; x < Math.min(srcW, stageWidth); ++x) {
          argb[y * stageWidth + x] = srcPixels[y * srcW + x];
        }
      }
    }
  } else {
    const bg = ((snapshot.backgroundColor & 0x00ffffff) | 0xff000000) >>> 0;
    argb.fill(bg);
  }

  for (const sprite of snapshot.sprites) {
    if (!sprite.visible) {
      continue;
    }
    const baked = sprite.bakedBitmap;
    if (baked === null || baked.width() <= 0 || baked.height() <= 0 || baked.pixels().length === 0) {
      continue;
    }

    const sx = sprite.x;
    const sy = sprite.y;
    const sw = sprite.width > 0 ? sprite.width : baked.width();
    const sh = sprite.height > 0 ? sprite.height : baked.height();
    const blend = sprite.blend;
    const ink = sprite.ink;
    const flipH = sprite.flipH; // hasDirectorHorizontalMirror folded into bake/flip at export (Stage 5)
    const flipV = sprite.flipV;

    if (sw === baked.width() && sh === baked.height()) {
      blitBitmap(argb, stageWidth, stageHeight, baked.pixels(), baked.width(), baked.height(), sx, sy, blend, ink, flipH, flipV);
    } else {
      blitBitmapScaled(argb, stageWidth, stageHeight, baked.pixels(), baked.width(), baked.height(), sx, sy, sw, sh, blend, ink, flipH, flipV);
    }
  }

  return new Bitmap(stageWidth, stageHeight, 32, argb);
}

/** 1:1 clipped blit with alpha compositing and (Stage 3) special-ink compositing. */
function blitBitmap(
  argb: Uint32Array,
  stageWidth: number,
  stageHeight: number,
  srcPixels: Uint32Array,
  srcW: number,
  srcH: number,
  dstX: number,
  dstY: number,
  blend: number,
  ink: InkMode,
  flipH: boolean,
  flipV: boolean,
): void {
  if (srcW <= 0 || srcH <= 0 || srcPixels.length < srcW * srcH) {
    return;
  }

  const sx0 = Math.max(0, -dstX);
  const sy0 = Math.max(0, -dstY);
  const sx1 = Math.min(srcW, stageWidth - dstX);
  const sy1 = Math.min(srcH, stageHeight - dstY);
  if (sx0 >= sx1 || sy0 >= sy1) {
    return;
  }

  const argbLen = argb.length;
  const useSpecialInk = isSpecialCompositingInk(ink);

  for (let sy = sy0; sy < sy1; ++sy) {
    const fetchY = flipV ? srcH - 1 - sy : sy;
    for (let sx = sx0; sx < sx1; ++sx) {
      const fetchX = flipH ? srcW - 1 - sx : sx;
      const src = srcPixels[fetchY * srcW + fetchX];
      let srcA = channel(src, 24);
      if (srcA === 0) {
        continue;
      }

      const dstIdx = (dstY + sy) * stageWidth + (dstX + sx);
      if (dstIdx < 0 || dstIdx >= argbLen) {
        continue;
      }

      if (useSpecialInk) {
        if (blend < 100) {
          // C++ integer division: srcA = (srcA * blend) / 100 truncates. Replicate with
          // Math.trunc so srcA stays integral (a float here would also defeat the srcA===0
          // skip below and skew the blend-back in compositeSpecialInk by 1 LSB).
          srcA = Math.trunc((srcA * blend) / 100);
          if (srcA === 0) {
            continue;
          }
        }
        compositeSpecialInk(argb, dstIdx, src, srcA, ink);
      } else if (blend < 100) {
        alphaCompositePercent(argb, dstIdx, src, srcA, blend);
      } else if (srcA >= 255) {
        argb[dstIdx] = (src | 0xff000000) >>> 0;
      } else {
        alphaComposite(argb, dstIdx, src, srcA);
      }
    }
  }
}

function alphaComposite(argb: Uint32Array, dstIdx: number, src: number, srcA: number): void {
  if (dstIdx < 0 || dstIdx >= argb.length) {
    return;
  }
  const dst = argb[dstIdx];
  const dstA = channel(dst, 24);
  const invA = 255 - srcA;
  const outA = srcA + ((dstA * invA) / 255 | 0);
  if (outA === 0) {
    argb[dstIdx] = 0;
    return;
  }
  const srcR = channel(src, 16);
  const srcG = channel(src, 8);
  const srcB = channel(src, 0);
  const dstR = channel(dst, 16);
  const dstG = channel(dst, 8);
  const dstB = channel(dst, 0);
  // C++ integer division truncates at each step; replicate with Math.trunc so per-channel
  // rounding matches the reference renderer exactly (parity is the contract).
  const outR = Math.trunc((srcR * srcA + Math.trunc((dstR * dstA * invA) / 255)) / outA);
  const outG = Math.trunc((srcG * srcA + Math.trunc((dstG * dstA * invA) / 255)) / outA);
  const outB = Math.trunc((srcB * srcA + Math.trunc((dstB * dstA * invA) / 255)) / outA);
  argb[dstIdx] = (((outA & 0xff) << 24) | ((outR & 0xff) << 16) | ((outG & 0xff) << 8) | (outB & 0xff)) >>> 0;
}

function alphaCompositePercent(argb: Uint32Array, dstIdx: number, src: number, srcA: number, blendPercent: number): void {
  if (dstIdx < 0 || dstIdx >= argb.length || srcA <= 0 || blendPercent <= 0) {
    return;
  }
  if (blendPercent >= 100) {
    alphaComposite(argb, dstIdx, src, srcA);
    return;
  }
  const dst = argb[dstIdx];
  const dstA = channel(dst, 24);
  if (dstA !== 255) {
    const blendedAlpha = ((srcA * blendPercent) / 100) | 0;
    alphaComposite(argb, dstIdx, src, blendedAlpha);
    return;
  }
  const opacity = Math.min(Math.max(((srcA * blendPercent * 256) / (255 * 100)) | 0, 0), 256);
  const invOpacity = 256 - opacity;
  const srcR = channel(src, 16);
  const srcG = channel(src, 8);
  const srcB = channel(src, 0);
  const dstR = channel(dst, 16);
  const dstG = channel(dst, 8);
  const dstB = channel(dst, 0);
  const outR = (srcR * opacity + dstR * invOpacity) >> 8;
  const outG = (srcG * opacity + dstG * invOpacity) >> 8;
  const outB = (srcB * opacity + dstB * invOpacity) >> 8;
  argb[dstIdx] = opaqueRgb(outR, outG, outB);
}

// Nearest-neighbour scaled blit. Faithful port of SoftwareFrameRenderer::blitBitmapScaled:
// sample the source at ((dy-dstY)*srcH)/dstH, ((dx-dstX)*srcW)/dstW (C++ integer division ->
// Math.trunc here, same dispatch as the 1:1 path). Stage 2 covers scaling; rotation is
// applied earlier in the pipeline (SpriteBaker) so only axis-aligned scaling reaches here.
function blitBitmapScaled(
  argb: Uint32Array,
  stageWidth: number,
  stageHeight: number,
  srcPixels: Uint32Array,
  srcW: number,
  srcH: number,
  dstX: number,
  dstY: number,
  dstW: number,
  dstH: number,
  blend: number,
  ink: InkMode,
  flipH: boolean,
  flipV: boolean,
): void {
  if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 || srcPixels.length < srcW * srcH) {
    return;
  }

  const dx0 = Math.max(0, dstX);
  const dy0 = Math.max(0, dstY);
  const dx1 = Math.min(stageWidth, dstX + dstW);
  const dy1 = Math.min(stageHeight, dstY + dstH);
  if (dx0 >= dx1 || dy0 >= dy1) {
    return;
  }

  const srcLen = srcPixels.length;
  const argbLen = argb.length;
  const useSpecialInk = isSpecialCompositingInk(ink);

  for (let dy = dy0; dy < dy1; ++dy) {
    let srcY = Math.trunc(((dy - dstY) * srcH) / dstH);
    if (flipV) {
      srcY = srcH - 1 - srcY;
    }
    if (srcY < 0 || srcY >= srcH) {
      continue;
    }

    for (let dx = dx0; dx < dx1; ++dx) {
      let srcX = Math.trunc(((dx - dstX) * srcW) / dstW);
      if (flipH) {
        srcX = srcW - 1 - srcX;
      }
      if (srcX < 0 || srcX >= srcW) {
        continue;
      }

      const srcIdx = srcY * srcW + srcX;
      if (srcIdx < 0 || srcIdx >= srcLen) {
        continue;
      }

      const src = srcPixels[srcIdx];
      let srcA = channel(src, 24);
      if (srcA === 0) {
        continue;
      }

      const dstIdx = dy * stageWidth + dx;
      if (dstIdx < 0 || dstIdx >= argbLen) {
        continue;
      }

      if (useSpecialInk) {
        if (blend < 100) {
          srcA = Math.trunc((srcA * blend) / 100);
          if (srcA === 0) {
            continue;
          }
        }
        compositeSpecialInk(argb, dstIdx, src, srcA, ink);
      } else if (blend < 100) {
        alphaCompositePercent(argb, dstIdx, src, srcA, blend);
      } else if (srcA >= 255) {
        argb[dstIdx] = (src | 0xff000000) >>> 0;
      } else {
        alphaComposite(argb, dstIdx, src, srcA);
      }
    }
  }
}

// Special-ink compositing. Faithful port of SoftwareFrameRenderer::compositeSpecialInk.
// ADD/SUBTRACT/DARKEST/LIGHTEN/LIGHTEST/REVERSE/GHOST/NOT_* compute a per-channel result
// from src and dst; if the source alpha is < 255 the result is blended back toward dst by
// (out*c*srcA + dst*invA)/255 (C++ integer truncation -> Math.trunc here). The output is
// opaque (opaqueRgb) — special inks always produce a fully opaque pixel.
function compositeSpecialInk(argb: Uint32Array, dstIdx: number, src: number, srcA: number, ink: InkMode): void {
  if (dstIdx < 0 || dstIdx >= argb.length) {
    return;
  }
  const dst = argb[dstIdx];
  const srcR = channel(src, 16);
  const srcG = channel(src, 8);
  const srcB = channel(src, 0);
  const dstR = channel(dst, 16);
  const dstG = channel(dst, 8);
  const dstB = channel(dst, 0);

  let outR = 0;
  let outG = 0;
  let outB = 0;

  switch (ink) {
    case InkMode.ADD_PIN:
    case InkMode.ADD:
      outR = Math.min(255, dstR + srcR);
      outG = Math.min(255, dstG + srcG);
      outB = Math.min(255, dstB + srcB);
      break;
    case InkMode.SUBTRACT_PIN:
    case InkMode.SUBTRACT:
      outR = Math.max(0, dstR - srcR);
      outG = Math.max(0, dstG - srcG);
      outB = Math.max(0, dstB - srcB);
      break;
    case InkMode.DARKEST:
      outR = Math.min(dstR, srcR);
      outG = Math.min(dstG, srcG);
      outB = Math.min(dstB, srcB);
      break;
    case InkMode.LIGHTEN:
    case InkMode.LIGHTEST:
      outR = Math.max(dstR, srcR);
      outG = Math.max(dstG, srcG);
      outB = Math.max(dstB, srcB);
      break;
    case InkMode.REVERSE:
      outR = (srcR ^ dstR) & 0xff;
      outG = (srcG ^ dstG) & 0xff;
      outB = (srcB ^ dstB) & 0xff;
      break;
    case InkMode.GHOST:
      outR = (~srcR & 0xff) & dstR;
      outG = (~srcG & 0xff) & dstG;
      outB = (~srcB & 0xff) & dstB;
      break;
    case InkMode.NOT_COPY:
      outR = ~srcR & 0xff;
      outG = ~srcG & 0xff;
      outB = ~srcB & 0xff;
      break;
    case InkMode.NOT_TRANSPARENT:
      outR = srcR & dstR;
      outG = srcG & dstG;
      outB = srcB & dstB;
      break;
    case InkMode.NOT_REVERSE:
      outR = ((~srcR & 0xff) ^ dstR) & 0xff;
      outG = ((~srcG & 0xff) ^ dstG) & 0xff;
      outB = ((~srcB & 0xff) ^ dstB) & 0xff;
      break;
    case InkMode.NOT_GHOST:
      outR = (~srcR & 0xff) | dstR;
      outG = (~srcG & 0xff) | dstG;
      outB = (~srcB & 0xff) | dstB;
      break;
    default:
      alphaComposite(argb, dstIdx, src, srcA);
      return;
  }

  if (srcA < 255) {
    const invA = 255 - srcA;
    outR = Math.trunc((outR * srcA + dstR * invA) / 255);
    outG = Math.trunc((outG * srcA + dstG * invA) / 255);
    outB = Math.trunc((outB * srcA + dstB * invA) / 255);
  }

  argb[dstIdx] = opaqueRgb(outR, outG, outB);
}