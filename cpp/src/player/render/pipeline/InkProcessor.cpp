#include "libreshockwave/player/render/pipeline/InkProcessor.hpp"

#include <algorithm>
#include <cmath>
#include <queue>
#include <vector>

namespace libreshockwave::player::render::pipeline {
namespace {

std::uint32_t packArgb(int alpha, int r, int g, int b) {
    return (static_cast<std::uint32_t>(alpha & 0xFF) << 24) |
           (static_cast<std::uint32_t>(r & 0xFF) << 16) |
           (static_cast<std::uint32_t>(g & 0xFF) << 8) |
           static_cast<std::uint32_t>(b & 0xFF);
}

bool isOpaqueColor(std::uint32_t argb, int colorRgb) {
    return ((argb >> 24) & 0xFFU) == 0xFFU && static_cast<int>(argb & 0x00FFFFFFU) == colorRgb;
}

bool hasOpaqueBorderColor(const bitmap::Bitmap& src, int colorRgb) {
    const int w = src.width();
    const int h = src.height();
    if (w <= 0 || h <= 0) {
        return false;
    }

    for (int x = 0; x < w; ++x) {
        if (isOpaqueColor(src.getPixel(x, 0), colorRgb)) return true;
        if (isOpaqueColor(src.getPixel(x, h - 1), colorRgb)) return true;
    }
    for (int y = 1; y < h - 1; ++y) {
        if (isOpaqueColor(src.getPixel(0, y), colorRgb)) return true;
        if (isOpaqueColor(src.getPixel(w - 1, y), colorRgb)) return true;
    }
    return false;
}

bitmap::Bitmap derivedBitmap(const bitmap::Bitmap& src, std::vector<std::uint32_t> pixels) {
    bitmap::Bitmap derived(src.width(), src.height(), src.bitDepth(), std::move(pixels));
    derived.copyPaletteMetadataFrom(&src);
    return derived;
}

int interpolate(float t, int fore, int back) {
    return static_cast<int>(std::lround((1.0F - t) * static_cast<float>(fore) +
                                        t * static_cast<float>(back)));
}

int maskAlphaFromPixel(std::uint32_t pixel) {
    const int r = static_cast<int>((pixel >> 16) & 0xFFU);
    const int g = static_cast<int>((pixel >> 8) & 0xFFU);
    const int b = static_cast<int>(pixel & 0xFFU);
    return ((77 * r) + (150 * g) + (29 * b) + 128) >> 8;
}

bool matchesRgb(std::uint32_t pixel, int matteRgb, int tolerance) {
    const int pr = static_cast<int>((pixel >> 16) & 0xFFU);
    const int pg = static_cast<int>((pixel >> 8) & 0xFFU);
    const int pb = static_cast<int>(pixel & 0xFFU);
    const int mr = (matteRgb >> 16) & 0xFF;
    const int mg = (matteRgb >> 8) & 0xFF;
    const int mb = matteRgb & 0xFF;
    return std::abs(pr - mr) <= tolerance &&
           std::abs(pg - mg) <= tolerance &&
           std::abs(pb - mb) <= tolerance;
}

bool isUniformPaletteIndex(const std::vector<std::uint8_t>& paletteIndices, int paletteIndex) {
    return std::all_of(paletteIndices.begin(), paletteIndices.end(), [&](std::uint8_t entry) {
        return static_cast<int>(entry & 0xFFU) == paletteIndex;
    });
}

bool edgeContainsOpaquePaletteIndex(const bitmap::Bitmap& src,
                                    const std::vector<std::uint8_t>& paletteIndices,
                                    int paletteIndex) {
    const int w = src.width();
    const int h = src.height();
    if (w <= 0 || h <= 0) {
        return false;
    }

    const auto isEdgeIndex = [&](int x, int y) {
        const auto index = static_cast<std::size_t>(y * w + x);
        return index < src.pixels().size() &&
               index < paletteIndices.size() &&
               ((src.pixels()[index] >> 24) & 0xFFU) != 0 &&
               static_cast<int>(paletteIndices[index] & 0xFFU) == paletteIndex;
    };

    for (int x = 0; x < w; ++x) {
        if (isEdgeIndex(x, 0) || isEdgeIndex(x, h - 1)) {
            return true;
        }
    }
    for (int y = 1; y < h - 1; ++y) {
        if (isEdgeIndex(0, y) || isEdgeIndex(w - 1, y)) {
            return true;
        }
    }
    return false;
}

int resolvePaletteIndexRgb(const bitmap::Bitmap& src,
                           const std::vector<std::uint8_t>& paletteIndices,
                           int paletteIndex) {
    const auto& pixels = src.pixels();
    for (std::size_t index = 0; index < pixels.size() && index < paletteIndices.size(); ++index) {
        if (static_cast<int>(paletteIndices[index] & 0xFFU) == paletteIndex) {
            return static_cast<int>(pixels[index] & 0x00FFFFFFU);
        }
    }
    return 0xFFFFFF;
}

bool hasOpaqueNonPaletteIndexContent(const bitmap::Bitmap& src,
                                     const std::vector<std::uint8_t>& paletteIndices,
                                     int paletteIndex) {
    const auto& pixels = src.pixels();
    for (std::size_t index = 0; index < pixels.size() && index < paletteIndices.size(); ++index) {
        if (((pixels[index] >> 24) & 0xFFU) != 0 &&
            static_cast<int>(paletteIndices[index] & 0xFFU) != paletteIndex) {
            return true;
        }
    }
    return false;
}

bool isNearWhiteGrayscale(int rgb, int minChannel, int maxDelta) {
    const int r = (rgb >> 16) & 0xFF;
    const int g = (rgb >> 8) & 0xFF;
    const int b = rgb & 0xFF;
    return r >= minChannel && g >= minChannel && b >= minChannel &&
           std::abs(r - g) <= maxDelta &&
           std::abs(g - b) <= maxDelta &&
           std::abs(r - b) <= maxDelta;
}

bool shouldKeyDefaultIndexedMatte(const bitmap::Bitmap& src,
                                  int backgroundKeyRgb,
                                  const std::vector<std::uint8_t>* paletteIndices) {
    if (src.bitDepth() > 8 || (backgroundKeyRgb & 0x00FFFFFF) != 0xFFFFFF ||
        paletteIndices == nullptr || paletteIndices->size() < src.pixels().size()) {
        return false;
    }
    if (isUniformPaletteIndex(*paletteIndices, 0)) {
        return false;
    }
    if (!edgeContainsOpaquePaletteIndex(src, *paletteIndices, 0)) {
        return false;
    }

    const int indexZeroRgb = resolvePaletteIndexRgb(src, *paletteIndices, 0);
    return isNearWhiteGrayscale(indexZeroRgb, 232, 16) &&
           hasOpaqueNonPaletteIndexContent(src, *paletteIndices, 0);
}

int multiplyDirectorChannel(int src, int tint) {
    if (tint >= 255) {
        return src;
    }
    if (src >= 255) {
        return tint;
    }
    return (src * tint) >> 8;
}

} // namespace

bool InkProcessor::shouldProcessInk(int ink) {
    return shouldProcessInk(id::inkModeFromCode(ink));
}

bool InkProcessor::shouldProcessInk(id::InkMode ink) {
    return ink == id::InkMode::TRANSPARENT ||
           ink == id::InkMode::REVERSE ||
           ink == id::InkMode::GHOST ||
           ink == id::InkMode::NOT_COPY ||
           ink == id::InkMode::NOT_TRANSPARENT ||
           ink == id::InkMode::NOT_REVERSE ||
           ink == id::InkMode::NOT_GHOST ||
           ink == id::InkMode::MATTE ||
           ink == id::InkMode::MASK ||
           ink == id::InkMode::ADD_PIN ||
           ink == id::InkMode::ADD ||
           ink == id::InkMode::SUBTRACT_PIN ||
           ink == id::InkMode::SUBTRACT ||
           ink == id::InkMode::BACKGROUND_TRANSPARENT ||
           ink == id::InkMode::BLEND ||
           ink == id::InkMode::LIGHTEN ||
           ink == id::InkMode::DARKEN;
}

bool InkProcessor::allowsColorize(int ink) {
    return allowsColorize(id::inkModeFromCode(ink));
}

bool InkProcessor::allowsColorize(id::InkMode ink) {
    return ink == id::InkMode::COPY;
}

bitmap::Bitmap InkProcessor::applyInk(const bitmap::Bitmap& src,
                                      int ink,
                                      int backColor,
                                      bool useAlpha,
                                      const bitmap::Palette* palette) {
    return applyInk(src, id::inkModeFromCode(ink), backColor, useAlpha, palette);
}

bitmap::Bitmap InkProcessor::applyInk(const bitmap::Bitmap& src,
                                      id::InkMode ink,
                                      int backColor,
                                      bool useAlpha,
                                      const bitmap::Palette* palette) {
    if (src.width() == 0 || src.height() == 0 || !shouldProcessInk(ink)) {
        return src.copy();
    }

    if (ink == id::InkMode::MATTE) {
        if (src.hasNativeMatteAlpha() && useAlpha) {
            return src.copy();
        }
        const int matteColor = resolveBackColor(src, ink, backColor, useAlpha, palette);
        return matteColor >= 0 ? applyMatte(src, matteColor) : src.copy();
    }

    if (ink == id::InkMode::MASK) {
        return applyMask(src);
    }

    if (ink == id::InkMode::DARKEN || ink == id::InkMode::LIGHTEN) {
        bitmap::Bitmap masked = src.copy();
        if (!(src.bitDepth() == 32 && !useAlpha)) {
            const int matteColor = resolveBackColor(src, ink, backColor, useAlpha, palette);
            if (matteColor >= 0) {
                masked = src.bitDepth() >= 16
                    ? applyBackgroundTransparent(src, matteColor)
                    : applyMatte(src, matteColor);
            }
        }

        if (ink == id::InkMode::DARKEN) {
            const int tintRgb = resolveBackColor(src, ink, backColor, false, palette);
            if (tintRgb >= 0 && tintRgb != 0xFFFFFF) {
                masked = multiplyColor(masked, tintRgb);
            }
        }
        return masked;
    }

    const int bgColor = resolveBackColor(src, ink, backColor, useAlpha, palette);
    if (bgColor < 0) {
        return src.copy();
    }
    return applyBackgroundTransparent(src, bgColor);
}

int InkProcessor::resolveBackColor(const bitmap::Bitmap& src,
                                   id::InkMode ink,
                                   int backColor,
                                   bool useAlpha,
                                   const bitmap::Palette* palette) {
    if (src.hasNativeMatteAlpha() && useAlpha) {
        const int alphaBackColor = resolveBackColorIgnoringAlpha(backColor, palette);
        if (ink == id::InkMode::BACKGROUND_TRANSPARENT && hasOpaqueBorderColor(src, alphaBackColor)) {
            return alphaBackColor;
        }
        return -1;
    }

    if (backColor > 255) {
        return backColor & 0x00FFFFFF;
    }

    if (src.bitDepth() == 32 && !useAlpha && ink != id::InkMode::COPY) {
        return 0xFFFFFF;
    }

    if (palette != nullptr && backColor >= 0 && backColor < palette->size()) {
        return static_cast<int>(palette->getColor(backColor) & 0x00FFFFFFU);
    }

    const int gray = 255 - backColor;
    return (gray << 16) | (gray << 8) | gray;
}

int InkProcessor::resolveBackColorIgnoringAlpha(int backColor, const bitmap::Palette* palette) {
    if (backColor > 255) {
        return backColor & 0x00FFFFFF;
    }
    if (palette != nullptr && backColor >= 0 && backColor < palette->size()) {
        return static_cast<int>(palette->getColor(backColor) & 0x00FFFFFFU);
    }

    const int gray = 255 - backColor;
    return (gray << 16) | (gray << 8) | gray;
}

bitmap::Bitmap InkProcessor::applyForeColorRemap(const bitmap::Bitmap& src,
                                                 std::uint32_t foreColor,
                                                 std::uint32_t backColor) {
    if (src.width() == 0 || src.height() == 0) {
        return src.copy();
    }

    const int fr = static_cast<int>((foreColor >> 16) & 0xFFU);
    const int fg = static_cast<int>((foreColor >> 8) & 0xFFU);
    const int fb = static_cast<int>(foreColor & 0xFFU);
    const int br = static_cast<int>((backColor >> 16) & 0xFFU);
    const int bg = static_cast<int>((backColor >> 8) & 0xFFU);
    const int bb = static_cast<int>(backColor & 0xFFU);

    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());
    for (const auto pixel : src.pixels()) {
        const int alpha = static_cast<int>((pixel >> 24) & 0xFFU);
        if (alpha == 0) {
            result.push_back(0);
            continue;
        }

        const int r = static_cast<int>((pixel >> 16) & 0xFFU);
        const int g = static_cast<int>((pixel >> 8) & 0xFFU);
        const int b = static_cast<int>(pixel & 0xFFU);
        const float t = static_cast<float>((r + g + b) / 3) / 255.0F;
        result.push_back(packArgb(alpha, interpolate(t, fr, br), interpolate(t, fg, bg), interpolate(t, fb, bb)));
    }

    return derivedBitmap(src, std::move(result));
}

bitmap::Bitmap InkProcessor::applyIndexedColorRemap(const bitmap::Bitmap& indexedSource,
                                                    const bitmap::Bitmap& maskedSource,
                                                    std::uint32_t foreColor,
                                                    std::uint32_t backColor) {
    if (indexedSource.width() != maskedSource.width() || indexedSource.height() != maskedSource.height()) {
        return maskedSource.copy();
    }
    const auto paletteIndices = indexedSource.paletteIndices();
    if (!paletteIndices.has_value() || paletteIndices->size() != maskedSource.pixels().size()) {
        return maskedSource.copy();
    }

    const int fr = static_cast<int>((foreColor >> 16) & 0xFFU);
    const int fg = static_cast<int>((foreColor >> 8) & 0xFFU);
    const int fb = static_cast<int>(foreColor & 0xFFU);
    const int br = static_cast<int>((backColor >> 16) & 0xFFU);
    const int bg = static_cast<int>((backColor >> 8) & 0xFFU);
    const int bb = static_cast<int>(backColor & 0xFFU);

    std::vector<std::uint32_t> result;
    result.reserve(maskedSource.pixels().size());
    for (std::size_t i = 0; i < maskedSource.pixels().size(); ++i) {
        const auto pixel = maskedSource.pixels()[i];
        const int alpha = static_cast<int>((pixel >> 24) & 0xFFU);
        if (alpha == 0) {
            result.push_back(0);
            continue;
        }

        const int paletteIndex = static_cast<int>((*paletteIndices)[i] & 0xFFU);
        const float t = static_cast<float>(255 - paletteIndex) / 255.0F;
        result.push_back(packArgb(alpha, interpolate(t, fr, br), interpolate(t, fg, bg), interpolate(t, fb, bb)));
    }

    return derivedBitmap(maskedSource, std::move(result));
}

bitmap::Bitmap InkProcessor::applyDarkenForeColorOffset(const bitmap::Bitmap& src, std::uint32_t foreColor) {
    const int foreR = static_cast<int>((foreColor >> 16) & 0xFFU);
    const int foreG = static_cast<int>((foreColor >> 8) & 0xFFU);
    const int foreB = static_cast<int>(foreColor & 0xFFU);

    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());
    for (const auto pixel : src.pixels()) {
        const int alpha = static_cast<int>((pixel >> 24) & 0xFFU);
        if (alpha == 0) {
            result.push_back(0);
            continue;
        }

        const int r = std::min(255, static_cast<int>((pixel >> 16) & 0xFFU) + foreR);
        const int g = std::min(255, static_cast<int>((pixel >> 8) & 0xFFU) + foreG);
        const int b = std::min(255, static_cast<int>(pixel & 0xFFU) + foreB);
        result.push_back(packArgb(alpha, r, g, b));
    }

    return derivedBitmap(src, std::move(result));
}

bitmap::Bitmap InkProcessor::remapExactColor(const bitmap::Bitmap& src,
                                             std::uint32_t fromRgb,
                                             std::uint32_t toRgb) {
    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());
    for (const auto pixel : src.pixels()) {
        if ((pixel & 0x00FFFFFFU) == (fromRgb & 0x00FFFFFFU)) {
            result.push_back((pixel & 0xFF000000U) | (toRgb & 0x00FFFFFFU));
        } else {
            result.push_back(pixel);
        }
    }
    return derivedBitmap(src, std::move(result));
}

bitmap::Bitmap InkProcessor::applyBackgroundTransparent(const bitmap::Bitmap& src, int bgColorRgb) {
    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());

    const auto paletteIndices = src.paletteIndices();
    const auto* paletteIndexPtr =
        (paletteIndices.has_value() && paletteIndices->size() >= src.pixels().size()) ? &*paletteIndices : nullptr;
    const bool keyDefaultIndexedMatte = shouldKeyDefaultIndexedMatte(src, bgColorRgb, paletteIndexPtr);

    for (std::size_t index = 0; index < src.pixels().size(); ++index) {
        const auto pixel = src.pixels()[index];
        const int alpha = static_cast<int>((pixel >> 24) & 0xFFU);
        if (alpha == 0) {
            result.push_back(0);
            continue;
        }

        const auto rgb = pixel & 0x00FFFFFFU;
        if (keyDefaultIndexedMatte &&
            paletteIndexPtr != nullptr &&
            index < paletteIndexPtr->size() &&
            static_cast<int>((*paletteIndexPtr)[index] & 0xFFU) == 0) {
            result.push_back(0);
        } else if (rgb == static_cast<std::uint32_t>(bgColorRgb & 0x00FFFFFF)) {
            result.push_back(0);
        } else {
            result.push_back(pixel | 0xFF000000U);
        }
    }

    return derivedBitmap(src, std::move(result));
}

bitmap::Bitmap InkProcessor::applyMask(const bitmap::Bitmap& src) {
    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());
    for (const auto pixel : src.pixels()) {
        const int srcAlpha = static_cast<int>((pixel >> 24) & 0xFFU);
        if (srcAlpha == 0) {
            result.push_back(0);
            continue;
        }

        const int maskAlpha = maskAlphaFromPixel(pixel);
        const int combinedAlpha = (srcAlpha * maskAlpha) / 255;
        result.push_back((static_cast<std::uint32_t>(combinedAlpha & 0xFF) << 24) |
                         (pixel & 0x00FFFFFFU));
    }
    return derivedBitmap(src, std::move(result));
}

bitmap::Bitmap InkProcessor::applyMatte(const bitmap::Bitmap& src, int matteColorRgb, int tolerance) {
    const int width = src.width();
    const int height = src.height();
    if (width <= 0 || height <= 0) {
        return src.copy();
    }

    std::vector<bool> transparent(static_cast<std::size_t>(width * height), false);
    std::queue<int> queue;
    const auto seed = [&](int x, int y) {
        const int index = y * width + x;
        if (transparent[static_cast<std::size_t>(index)]) {
            return;
        }
        const auto pixel = src.pixels()[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0 || matchesRgb(pixel, matteColorRgb, tolerance)) {
            transparent[static_cast<std::size_t>(index)] = true;
            queue.push(index);
        }
    };

    for (int x = 0; x < width; ++x) {
        seed(x, 0);
        seed(x, height - 1);
    }
    for (int y = 1; y < height - 1; ++y) {
        seed(0, y);
        seed(width - 1, y);
    }

    while (!queue.empty()) {
        const int index = queue.front();
        queue.pop();
        const int x = index % width;
        const int y = index / width;
        if (x > 0) seed(x - 1, y);
        if (x < width - 1) seed(x + 1, y);
        if (y > 0) seed(x, y - 1);
        if (y < height - 1) seed(x, y + 1);
    }

    std::vector<std::uint32_t> result = src.pixels();
    for (std::size_t index = 0; index < result.size() && index < transparent.size(); ++index) {
        if (transparent[index]) {
            result[index] = 0;
        }
    }
    return derivedBitmap(src, std::move(result));
}

bitmap::Bitmap InkProcessor::convertOpaqueWhiteToTransparent(const bitmap::Bitmap& src) {
    bool changed = false;
    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());
    for (const auto pixel : src.pixels()) {
        if (pixel == 0xFFFFFFFFU) {
            result.push_back(0x00FFFFFFU);
            changed = true;
        } else {
            result.push_back(pixel);
        }
    }
    return changed ? derivedBitmap(src, std::move(result)) : src.copy();
}

bitmap::Bitmap InkProcessor::multiplyColor(const bitmap::Bitmap& src, int tintRgb) {
    const int tintR = (tintRgb >> 16) & 0xFF;
    const int tintG = (tintRgb >> 8) & 0xFF;
    const int tintB = tintRgb & 0xFF;

    std::vector<std::uint32_t> result;
    result.reserve(src.pixels().size());
    for (const auto pixel : src.pixels()) {
        const int alpha = static_cast<int>((pixel >> 24) & 0xFFU);
        if (alpha == 0) {
            result.push_back(0);
            continue;
        }

        const int r = static_cast<int>((pixel >> 16) & 0xFFU);
        const int g = static_cast<int>((pixel >> 8) & 0xFFU);
        const int b = static_cast<int>(pixel & 0xFFU);
        result.push_back(packArgb(alpha,
                                  multiplyDirectorChannel(r, tintR),
                                  multiplyDirectorChannel(g, tintG),
                                  multiplyDirectorChannel(b, tintB)));
    }
    return derivedBitmap(src, std::move(result));
}

} // namespace libreshockwave::player::render::pipeline
