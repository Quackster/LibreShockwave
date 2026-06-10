#include "libreshockwave/bitmap/Drawing.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <memory>
#include <optional>
#include <queue>
#include <unordered_map>
#include <utility>
#include <vector>

#include "libreshockwave/bitmap/Bitmap.hpp"

namespace libreshockwave::bitmap::Drawing {
namespace {

struct FloodFillMatte {
    std::optional<int> paletteIndex;
    int colorRgb = 0xFFFFFF;
    int tolerance = 0;

    [[nodiscard]] bool usesPaletteIndex() const { return paletteIndex.has_value(); }
};

int maskAlphaFromPixel(std::uint32_t pixel) {
    const int r = static_cast<int>((pixel >> 16) & 0xFFU);
    const int g = static_cast<int>((pixel >> 8) & 0xFFU);
    const int b = static_cast<int>(pixel & 0xFFU);
    return ((77 * r) + (150 * g) + (29 * b) + 128) >> 8;
}

std::vector<int> cornerIndices(int width, int height) {
    return {
        0,
        std::max(0, width - 1),
        std::max(0, (height - 1) * width),
        std::max(0, (height - 1) * width + (width - 1)),
    };
}

std::vector<int> edgeIndices(int width, int height) {
    std::vector<int> indices;
    indices.reserve(static_cast<std::size_t>(std::max(1, (width * 2) + std::max(0, height - 2) * 2)));
    for (int x = 0; x < width; ++x) {
        indices.push_back(x);
        if (height > 1) {
            indices.push_back((height - 1) * width + x);
        }
    }
    for (int y = 1; y < height - 1; ++y) {
        indices.push_back(y * width);
        if (width > 1) {
            indices.push_back(y * width + (width - 1));
        }
    }
    return indices;
}

bool hasPaletteIndices(const std::optional<std::vector<std::uint8_t>>& paletteIndices, int width, int height) {
    return paletteIndices.has_value() && paletteIndices->size() >= static_cast<std::size_t>(width * height);
}

bool isUniformPaletteIndex(const std::vector<std::uint8_t>& paletteIndices, int paletteIndex) {
    return std::all_of(paletteIndices.begin(), paletteIndices.end(), [&](std::uint8_t entry) {
        return static_cast<int>(entry) == paletteIndex;
    });
}

int resolvePaletteIndexRgb(const std::vector<std::uint32_t>& pixels,
                           const std::vector<std::uint8_t>& paletteIndices,
                           int paletteIndex) {
    for (std::size_t index = 0; index < pixels.size() && index < paletteIndices.size(); ++index) {
        if (static_cast<int>(paletteIndices[index]) == paletteIndex) {
            return static_cast<int>(pixels[index] & 0x00FFFFFFU);
        }
    }
    return 0xFFFFFF;
}

bool cornerContainsPaletteIndex(const std::vector<std::uint8_t>& paletteIndices,
                                int width,
                                int height,
                                int paletteIndex) {
    for (const int index : cornerIndices(width, height)) {
        if (index >= 0 && static_cast<std::size_t>(index) < paletteIndices.size() &&
            static_cast<int>(paletteIndices[static_cast<std::size_t>(index)]) == paletteIndex) {
            return true;
        }
    }
    return false;
}

bool defaultIndexedMatteRgb(int rgb) {
    return rgb == 0x000000 || rgb == 0xFFFFFF;
}

std::optional<int> inferDominantEdgePaletteIndex(const std::vector<std::uint32_t>& pixels,
                                                 const std::vector<std::uint8_t>& paletteIndices,
                                                 int width,
                                                 int height) {
    if (width <= 0 || height <= 0) return std::nullopt;

    std::array<int, 256> counts{};
    int opaqueEdgeCount = 0;
    int dominantIndex = -1;
    int dominantCount = 0;
    for (const int index : edgeIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        const int paletteIndex = static_cast<int>(paletteIndices[static_cast<std::size_t>(index)]);
        const int count = ++counts[static_cast<std::size_t>(paletteIndex)];
        ++opaqueEdgeCount;
        if (count > dominantCount) {
            dominantCount = count;
            dominantIndex = paletteIndex;
        }
    }
    if (opaqueEdgeCount == 0 || dominantIndex < 0 || isUniformPaletteIndex(paletteIndices, dominantIndex)) {
        return std::nullopt;
    }

    int opaqueCornerCount = 0;
    for (const int index : cornerIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        ++opaqueCornerCount;
        if (static_cast<int>(paletteIndices[static_cast<std::size_t>(index)]) != dominantIndex) {
            return std::nullopt;
        }
    }
    if (opaqueCornerCount == 0 || dominantCount * 4 < opaqueEdgeCount * 3) {
        return std::nullopt;
    }
    return dominantIndex;
}

std::optional<FloodFillMatte> resolveIndexedFloodFillMatte(
    const std::vector<std::uint32_t>& pixels,
    const std::vector<std::uint8_t>& paletteIndices,
    int width,
    int height) {
    if (const auto dominant = inferDominantEdgePaletteIndex(pixels, paletteIndices, width, height)) {
        const int matteRgb = resolvePaletteIndexRgb(pixels, paletteIndices, *dominant);
        if (*dominant == 0 && defaultIndexedMatteRgb(matteRgb)) {
            return FloodFillMatte{*dominant, matteRgb, 0};
        }
    }
    if (cornerContainsPaletteIndex(paletteIndices, width, height, 0)) {
        const int indexZeroRgb = resolvePaletteIndexRgb(pixels, paletteIndices, 0);
        if (defaultIndexedMatteRgb(indexZeroRgb)) {
            return FloodFillMatte{0, indexZeroRgb, 0};
        }
    }
    return std::nullopt;
}

bool isUniformRgb(const std::vector<std::uint32_t>& pixels, int rgb) {
    for (const auto pixel : pixels) {
        if (((pixel >> 24) & 0xFFU) != 0 && static_cast<int>(pixel & 0x00FFFFFFU) != rgb) {
            return false;
        }
    }
    return true;
}

std::optional<int> inferDominantEdgeRgb(const std::vector<std::uint32_t>& pixels, int width, int height) {
    if (width <= 0 || height <= 0) return std::nullopt;

    std::unordered_map<int, int> counts;
    int opaqueEdgeCount = 0;
    int dominantRgb = -1;
    int dominantCount = 0;
    for (const int index : edgeIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        const int rgb = static_cast<int>(pixel & 0x00FFFFFFU);
        const int count = ++counts[rgb];
        ++opaqueEdgeCount;
        if (count > dominantCount) {
            dominantCount = count;
            dominantRgb = rgb;
        }
    }
    if (opaqueEdgeCount == 0 || dominantRgb < 0 || isUniformRgb(pixels, dominantRgb)) {
        return std::nullopt;
    }

    int opaqueCornerCount = 0;
    for (const int index : cornerIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        ++opaqueCornerCount;
        if (static_cast<int>(pixel & 0x00FFFFFFU) != dominantRgb) {
            return std::nullopt;
        }
    }
    if (opaqueCornerCount == 0 || dominantCount * 4 < opaqueEdgeCount * 3) {
        return std::nullopt;
    }
    return dominantRgb;
}

bool cornerContainsOpaqueRgb(const std::vector<std::uint32_t>& pixels, int width, int height, int rgb) {
    for (const int index : cornerIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) != 0 && static_cast<int>(pixel & 0x00FFFFFFU) == rgb) {
            return true;
        }
    }
    return false;
}

std::optional<FloodFillMatte> resolveRgbFloodFillMatte(const std::vector<std::uint32_t>& pixels,
                                                       int width,
                                                       int height) {
    if (const auto dominant = inferDominantEdgeRgb(pixels, width, height)) {
        return FloodFillMatte{std::nullopt, *dominant, 0};
    }
    if (!cornerContainsOpaqueRgb(pixels, width, height, 0xFFFFFF)) {
        return std::nullopt;
    }
    return FloodFillMatte{std::nullopt, 0xFFFFFF, 0};
}

std::optional<FloodFillMatte> resolveFloodFillMatte(
    const std::vector<std::uint32_t>& pixels,
    const std::optional<std::vector<std::uint8_t>>& paletteIndices,
    int width,
    int height) {
    if (hasPaletteIndices(paletteIndices, width, height)) {
        return resolveIndexedFloodFillMatte(pixels, *paletteIndices, width, height);
    }
    return resolveRgbFloodFillMatte(pixels, width, height);
}

bool matchesRgb(std::uint32_t pixel, int matteRgb, int tolerance) {
    const int pr = static_cast<int>((pixel >> 16) & 0xFFU);
    const int pg = static_cast<int>((pixel >> 8) & 0xFFU);
    const int pb = static_cast<int>(pixel & 0xFFU);
    const int mr = (matteRgb >> 16) & 0xFF;
    const int mg = (matteRgb >> 8) & 0xFF;
    const int mb = matteRgb & 0xFF;
    return std::abs(pr - mr) <= tolerance && std::abs(pg - mg) <= tolerance && std::abs(pb - mb) <= tolerance;
}

bool isTransparentOrMatte(const std::vector<std::uint32_t>& pixels,
                          const std::optional<std::vector<std::uint8_t>>& paletteIndices,
                          int index,
                          const FloodFillMatte& matte) {
    const auto pixel = pixels[static_cast<std::size_t>(index)];
    if (((pixel >> 24) & 0xFFU) == 0) {
        return true;
    }
    if (matte.usesPaletteIndex() && paletteIndices.has_value() &&
        static_cast<std::size_t>(index) < paletteIndices->size()) {
        return static_cast<int>((*paletteIndices)[static_cast<std::size_t>(index)]) == *matte.paletteIndex;
    }
    return matchesRgb(pixel, matte.colorRgb, matte.tolerance);
}

std::vector<bool> computeFloodFillTransparency(
    const std::vector<std::uint32_t>& pixels,
    const std::optional<std::vector<std::uint8_t>>& paletteIndices,
    int width,
    int height,
    const FloodFillMatte& matte) {
    std::vector<bool> transparent(static_cast<std::size_t>(width * height), false);
    std::queue<int> queue;
    const auto seed = [&](int x, int y) {
        const int index = y * width + x;
        if (!transparent[static_cast<std::size_t>(index)] &&
            isTransparentOrMatte(pixels, paletteIndices, index, matte)) {
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
    return transparent;
}

std::shared_ptr<Bitmap> createAlphaMatte(const Bitmap& src, int alphaThreshold) {
    const int width = src.width();
    const int height = src.height();
    const int threshold = std::clamp(alphaThreshold, 0, 255);
    std::vector<std::uint32_t> mask(static_cast<std::size_t>(width * height), 0x00FFFFFFU);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int alpha = static_cast<int>((src.getPixel(x, y) >> 24) & 0xFFU);
            if (alpha < threshold) {
                alpha = 0;
            }
            mask[static_cast<std::size_t>(y * width + x)] =
                (static_cast<std::uint32_t>(alpha) << 24) | 0x00FFFFFFU;
        }
    }
    auto matte = std::make_shared<Bitmap>(width, height, 32, std::move(mask));
    matte->setNativeAlpha(true);
    return matte;
}

std::shared_ptr<Bitmap> createFloodFillMatte(const Bitmap& src) {
    const int width = src.width();
    const int height = src.height();
    const auto pixels = src.pixels();
    const auto paletteIndices = src.paletteIndices();
    const auto matte = resolveFloodFillMatte(pixels, paletteIndices, width, height);
    const auto transparent = matte
        ? computeFloodFillTransparency(pixels, paletteIndices, width, height, *matte)
        : std::vector<bool>(static_cast<std::size_t>(width * height), false);

    std::vector<std::uint32_t> mask(static_cast<std::size_t>(width * height), 0x00FFFFFFU);
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        if (transparent[index]) {
            mask[index] = 0x00FFFFFFU;
        } else {
            auto alpha = static_cast<std::uint32_t>((pixels[index] >> 24) & 0xFFU);
            if (alpha == 0) {
                alpha = 0xFFU;
            }
            mask[index] = (alpha << 24) | 0x00FFFFFFU;
        }
    }

    auto matteBitmap = std::make_shared<Bitmap>(width, height, 32, std::move(mask));
    matteBitmap->setNativeAlpha(true);
    return matteBitmap;
}

bool isGrayscaleMaskSource(const std::vector<std::uint32_t>& pixels, const std::vector<bool>& transparent) {
    int opaquePixels = 0;
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        if (transparent[index] || ((pixels[index] >> 24) & 0xFFU) == 0) {
            continue;
        }
        const auto r = (pixels[index] >> 16) & 0xFFU;
        const auto g = (pixels[index] >> 8) & 0xFFU;
        const auto b = pixels[index] & 0xFFU;
        if (r != g || g != b) {
            return false;
        }
        ++opaquePixels;
    }
    return opaquePixels > 0 && opaquePixels * 4 <= static_cast<int>(pixels.size()) * 3;
}

bool isWhiteBackedMaskSource(const std::vector<std::uint32_t>& pixels,
                             const std::vector<bool>& transparent,
                             const FloodFillMatte& matte) {
    if (maskAlphaFromPixel(0xFF000000U | static_cast<std::uint32_t>(matte.colorRgb & 0x00FFFFFF)) < 250) {
        return false;
    }

    bool hasTransparentMatte = false;
    bool hasOpaqueInk = false;
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        if (((pixels[index] >> 24) & 0xFFU) == 0) {
            continue;
        }
        if (transparent[index]) {
            hasTransparentMatte = true;
        } else {
            hasOpaqueInk = true;
        }
        if (hasTransparentMatte && hasOpaqueInk) {
            return true;
        }
    }
    return false;
}

bool isMaskSource(const std::vector<std::uint32_t>& pixels,
                  const std::vector<bool>& transparent,
                  const FloodFillMatte& matte) {
    return isGrayscaleMaskSource(pixels, transparent) ||
           isWhiteBackedMaskSource(pixels, transparent, matte);
}

std::shared_ptr<Bitmap> createDirectMask(const Bitmap& src,
                                         const FloodFillMatte& matte,
                                         int alphaThreshold) {
    const int width = src.width();
    const int height = src.height();
    const int threshold = std::clamp(alphaThreshold, 0, 255);
    const auto pixels = src.pixels();
    const int matteLuma = maskAlphaFromPixel(0xFF000000U | static_cast<std::uint32_t>(matte.colorRgb & 0x00FFFFFF));
    const bool lightMatte = matteLuma >= 128;

    std::vector<std::uint32_t> mask(static_cast<std::size_t>(width * height), 0xFFFFFFFFU);
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        const auto pixel = pixels[index];
        if (((pixel >> 24) & 0xFFU) == 0) {
            mask[index] = 0xFFFFFFFFU;
            continue;
        }
        int maskLuma = maskAlphaFromPixel(pixel);
        if (!lightMatte) {
            maskLuma = 255 - maskLuma;
        }
        const int opacity = 255 - maskLuma;
        if (opacity < threshold) {
            maskLuma = 255;
        }
        const auto luma = static_cast<std::uint32_t>(maskLuma & 0xFF);
        mask[index] = 0xFF000000U | (luma << 16) | (luma << 8) | luma;
    }
    return std::make_shared<Bitmap>(width, height, src.bitDepth(), std::move(mask));
}

} // namespace

void fillRect(Bitmap& dest, int x, int y, int width, int height, std::uint32_t argb) {
    dest.fillRect(x, y, width, height, argb);
}

void drawRect(Bitmap& dest, int x, int y, int width, int height, std::uint32_t argb) {
    if (width <= 0 || height <= 0) {
        return;
    }
    for (int px = x; px < x + width; ++px) {
        dest.setPixel(px, y, argb);
        dest.setPixel(px, y + height - 1, argb);
    }
    for (int py = y; py < y + height; ++py) {
        dest.setPixel(x, py, argb);
        dest.setPixel(x + width - 1, py, argb);
    }
}

void drawLine(Bitmap& dest, int x0, int y0, int x1, int y1, std::uint32_t argb) {
    const int dx = std::abs(x1 - x0);
    const int dy = std::abs(y1 - y0);
    const int sx = x0 < x1 ? 1 : -1;
    const int sy = y0 < y1 ? 1 : -1;
    int err = dx - dy;

    while (true) {
        dest.setPixel(x0, y0, argb);
        if (x0 == x1 && y0 == y1) {
            break;
        }
        const int e2 = 2 * err;
        if (e2 > -dy) {
            err -= dy;
            x0 += sx;
        }
        if (e2 < dx) {
            err += dx;
            y0 += sy;
        }
    }
}

void fillEllipse(Bitmap& dest, int cx, int cy, int rx, int ry, std::uint32_t argb) {
    if (rx < 0 || ry < 0) {
        return;
    }
    const int rxSq = rx * rx;
    const int rySq = ry * ry;
    const int radiusProduct = rxSq * rySq;
    for (int y = -ry; y <= ry; ++y) {
        for (int x = -rx; x <= rx; ++x) {
            if ((x * x * rySq) + (y * y * rxSq) <= radiusProduct) {
                dest.setPixel(cx + x, cy + y, argb);
            }
        }
    }
}

void drawEllipse(Bitmap& dest, int cx, int cy, int rx, int ry, std::uint32_t argb) {
    if (rx <= 0 || ry <= 0) {
        return;
    }

    int x = 0;
    int y = ry;
    const int rxSq = rx * rx;
    const int rySq = ry * ry;
    int p = static_cast<int>(static_cast<double>(rySq) -
                             static_cast<double>(rxSq * ry) +
                             0.25 * static_cast<double>(rxSq));

    while (rySq * x < rxSq * y) {
        dest.setPixel(cx + x, cy + y, argb);
        dest.setPixel(cx - x, cy + y, argb);
        dest.setPixel(cx + x, cy - y, argb);
        dest.setPixel(cx - x, cy - y, argb);
        if (p < 0) {
            ++x;
            p += (2 * rySq * x) + rySq;
        } else {
            ++x;
            --y;
            p += (2 * rySq * x) - (2 * rxSq * y) + rySq;
        }
    }

    p = static_cast<int>(static_cast<double>(rySq) * (static_cast<double>(x) + 0.5) *
                             (static_cast<double>(x) + 0.5) +
                         static_cast<double>(rxSq) * (y - 1) * (y - 1) -
                         static_cast<double>(rxSq * rySq));
    while (y >= 0) {
        dest.setPixel(cx + x, cy + y, argb);
        dest.setPixel(cx - x, cy + y, argb);
        dest.setPixel(cx + x, cy - y, argb);
        dest.setPixel(cx - x, cy - y, argb);
        if (p > 0) {
            --y;
            p -= (2 * rxSq * y) + rxSq;
        } else {
            --y;
            ++x;
            p += (2 * rySq * x) - (2 * rxSq * y) + rxSq;
        }
    }
}

std::shared_ptr<Bitmap> createMatte(const Bitmap& src, int alphaThreshold) {
    if (src.width() <= 0 || src.height() <= 0) {
        return std::make_shared<Bitmap>(1, 1, 32);
    }
    if (src.hasNativeMatteAlpha()) {
        return createAlphaMatte(src, alphaThreshold);
    }
    return createFloodFillMatte(src);
}

std::shared_ptr<Bitmap> createMask(const Bitmap& src, int alphaThreshold) {
    if (src.width() <= 0 || src.height() <= 0) {
        return std::make_shared<Bitmap>(1, 1, 32);
    }
    if (src.hasNativeMatteAlpha()) {
        return createAlphaMatte(src, alphaThreshold);
    }

    const auto pixels = src.pixels();
    const auto paletteIndices = src.paletteIndices();
    const auto matte = resolveFloodFillMatte(pixels, paletteIndices, src.width(), src.height());
    if (matte.has_value()) {
        const auto transparent =
            computeFloodFillTransparency(pixels, paletteIndices, src.width(), src.height(), *matte);
        if (isMaskSource(pixels, transparent, *matte)) {
            return createDirectMask(src, *matte, alphaThreshold);
        }
    }
    return createFloodFillMatte(src);
}

Bitmap applyMatteToRegion(const Bitmap& src, int x, int y, int width, int height) {
    if (width <= 0 || height <= 0) {
        return Bitmap(std::max(width, 1), std::max(height, 1), src.bitDepth());
    }
    auto region = src.getRegion(x, y, width, height);
    if (src.hasNativeMatteAlpha()) {
        region.setNativeAlpha(true);
        return region;
    }

    const auto pixels = region.pixels();
    const auto paletteIndices = region.paletteIndices();
    const auto matte = resolveFloodFillMatte(pixels, paletteIndices, width, height);
    if (!matte.has_value()) {
        return region;
    }

    const auto transparent = computeFloodFillTransparency(pixels, paletteIndices, width, height, *matte);
    auto& regionPixels = region.pixels();
    for (std::size_t index = 0; index < regionPixels.size() && index < transparent.size(); ++index) {
        if (transparent[index]) {
            regionPixels[index] &= 0x00FFFFFFU;
        }
    }
    return region;
}

Bitmap applyFloodFillTransparency(const Bitmap& src) {
    return applyMatteToRegion(src, 0, 0, src.width(), src.height());
}

} // namespace libreshockwave::bitmap::Drawing
