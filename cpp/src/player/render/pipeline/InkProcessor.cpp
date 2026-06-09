#include "libreshockwave/player/render/pipeline/InkProcessor.hpp"

#include <algorithm>
#include <cmath>
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

} // namespace libreshockwave::player::render::pipeline
