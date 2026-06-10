#include "libreshockwave/font/BitmapFont.hpp"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace libreshockwave::font {

std::shared_ptr<BitmapFont> BitmapFont::create(std::vector<std::uint32_t> bitmap,
                                               int bitmapWidth,
                                               int bitmapHeight,
                                               int cellWidth,
                                               int cellHeight,
                                               std::vector<int> charWidths,
                                               std::string fontName,
                                               int fontSize,
                                               int metricsAscent,
                                               int metricsLineHeight,
                                               GlyphMap overflowGlyphs,
                                               MetricMap overflowWidths) {
    return create(std::move(bitmap),
                  bitmapWidth,
                  bitmapHeight,
                  cellWidth,
                  cellHeight,
                  std::move(charWidths),
                  std::vector<int>(NUM_CHARS, 0),
                  std::move(fontName),
                  fontSize,
                  metricsAscent,
                  metricsLineHeight,
                  std::move(overflowGlyphs),
                  std::move(overflowWidths),
                  {});
}

std::shared_ptr<BitmapFont> BitmapFont::create(std::vector<std::uint32_t> bitmap,
                                               int bitmapWidth,
                                               int bitmapHeight,
                                               int cellWidth,
                                               int cellHeight,
                                               std::vector<int> charWidths,
                                               std::vector<int> charOffsetsX,
                                               std::string fontName,
                                               int fontSize,
                                               int metricsAscent,
                                               int metricsLineHeight,
                                               GlyphMap overflowGlyphs,
                                               MetricMap overflowWidths,
                                               MetricMap overflowOffsetsX) {
    return std::shared_ptr<BitmapFont>(new BitmapFont(std::move(bitmap),
                                                      bitmapWidth,
                                                      bitmapHeight,
                                                      cellWidth,
                                                      cellHeight,
                                                      std::move(charWidths),
                                                      std::move(charOffsetsX),
                                                      std::move(fontName),
                                                      fontSize,
                                                      metricsAscent,
                                                      metricsLineHeight,
                                                      std::move(overflowGlyphs),
                                                      std::move(overflowWidths),
                                                      std::move(overflowOffsetsX)));
}

BitmapFont::BitmapFont(std::vector<std::uint32_t> bitmap,
                       int bitmapWidth,
                       int bitmapHeight,
                       int cellWidth,
                       int cellHeight,
                       std::vector<int> charWidths,
                       std::vector<int> charOffsetsX,
                       std::string fontName,
                       int fontSize,
                       int metricsAscent,
                       int metricsLineHeight,
                       GlyphMap overflowGlyphs,
                       MetricMap overflowWidths,
                       MetricMap overflowOffsetsX)
    : bitmap_(std::move(bitmap)),
      bitmapWidth_(bitmapWidth),
      bitmapHeight_(bitmapHeight),
      cellWidth_(cellWidth),
      cellHeight_(cellHeight),
      charWidths_(std::move(charWidths)),
      charOffsetsX_(std::move(charOffsetsX)),
      fontName_(std::move(fontName)),
      fontSize_(fontSize),
      metricsAscent_(metricsAscent),
      metricsLineHeight_(metricsLineHeight),
      overflowGlyphs_(std::move(overflowGlyphs)),
      overflowWidths_(std::move(overflowWidths)),
      overflowOffsetsX_(std::move(overflowOffsetsX)) {
    if (bitmapWidth_ < 0 || bitmapHeight_ < 0 || cellWidth_ <= 0 || cellHeight_ <= 0) {
        throw std::invalid_argument("BitmapFont dimensions are invalid");
    }
    const auto expected = static_cast<std::size_t>(bitmapWidth_) * static_cast<std::size_t>(bitmapHeight_);
    if (bitmap_.size() != expected) {
        throw std::invalid_argument("BitmapFont pixel count does not match dimensions");
    }
    if (charWidths_.size() < NUM_CHARS) {
        charWidths_.resize(NUM_CHARS, cellWidth_);
    }
    if (charOffsetsX_.size() < NUM_CHARS) {
        charOffsetsX_.resize(NUM_CHARS, 0);
    }
}

int BitmapFont::bitmapWidth() const { return bitmapWidth_; }
int BitmapFont::bitmapHeight() const { return bitmapHeight_; }
int BitmapFont::cellWidth() const { return cellWidth_; }
int BitmapFont::cellHeight() const { return cellHeight_; }

int BitmapFont::getCharWidth(int charCode) const {
    if (charCode >= 0 && charCode < static_cast<int>(charWidths_.size())) {
        return charWidths_[static_cast<std::size_t>(charCode)];
    }
    if (const auto it = overflowWidths_.find(charCode); it != overflowWidths_.end()) {
        return it->second;
    }
    return cellWidth_;
}

int BitmapFont::getCharOffsetX(int charCode) const {
    if (charCode >= 0 && charCode < static_cast<int>(charOffsetsX_.size())) {
        return charOffsetsX_[static_cast<std::size_t>(charCode)];
    }
    if (const auto it = overflowOffsetsX_.find(charCode); it != overflowOffsetsX_.end()) {
        return it->second;
    }
    return 0;
}

int BitmapFont::getStringWidth(std::string_view text) const {
    int width = 0;
    for (const unsigned char ch : text) {
        width += getCharWidth(ch);
    }
    return width;
}

int BitmapFont::getLineHeight() const { return metricsLineHeight_; }
int BitmapFont::getAscent() const { return metricsAscent_; }
const std::string& BitmapFont::getFontName() const { return fontName_; }
int BitmapFont::getFontSize() const { return fontSize_; }

void BitmapFont::drawChar(int charCode,
                          std::vector<std::uint32_t>& dst,
                          int dstW,
                          int dstH,
                          int dstX,
                          int dstY,
                          std::uint32_t color) const {
    const int drawX = dstX + getCharOffsetX(charCode);
    const int r = static_cast<int>((color >> 16U) & 0xFFU);
    const int g = static_cast<int>((color >> 8U) & 0xFFU);
    const int b = static_cast<int>(color & 0xFFU);

    if (dstW <= 0 || dstH <= 0 || dst.size() < static_cast<std::size_t>(dstW * dstH)) {
        return;
    }

    if (charCode >= 0 && charCode < NUM_CHARS) {
        const int col = charCode % GRID_COLUMNS;
        const int row = charCode / GRID_COLUMNS;
        const int cellX = col * cellWidth_;
        const int cellY = row * cellHeight_;

        for (int cy = 0; cy < cellHeight_; ++cy) {
            const int py = dstY + cy;
            if (py < 0 || py >= dstH) {
                continue;
            }
            for (int cx = 0; cx < cellWidth_; ++cx) {
                const int px = drawX + cx;
                if (px < 0 || px >= dstW) {
                    continue;
                }
                const int srcIdx = (cellY + cy) * bitmapWidth_ + (cellX + cx);
                if (srcIdx < 0 || srcIdx >= static_cast<int>(bitmap_.size())) {
                    continue;
                }
                blendPixel(dst, dstW, px, py, bitmap_[static_cast<std::size_t>(srcIdx)], r, g, b);
            }
        }
        return;
    }

    const auto glyph = overflowGlyphs_.find(charCode);
    if (glyph == overflowGlyphs_.end()) {
        return;
    }
    const auto& glyphPixels = glyph->second;
    for (int cy = 0; cy < cellHeight_; ++cy) {
        const int py = dstY + cy;
        if (py < 0 || py >= dstH) {
            continue;
        }
        for (int cx = 0; cx < cellWidth_; ++cx) {
            const int px = drawX + cx;
            if (px < 0 || px >= dstW) {
                continue;
            }
            const int srcIdx = cy * cellWidth_ + cx;
            if (srcIdx < 0 || srcIdx >= static_cast<int>(glyphPixels.size())) {
                continue;
            }
            blendPixel(dst, dstW, px, py, glyphPixels[static_cast<std::size_t>(srcIdx)], r, g, b);
        }
    }
}

void BitmapFont::blendPixel(std::vector<std::uint32_t>& dst,
                            int dstW,
                            int px,
                            int py,
                            std::uint32_t srcPixel,
                            int r,
                            int g,
                            int b) {
    const int srcA = static_cast<int>((srcPixel >> 24U) & 0xFFU);
    if (srcA == 0) {
        return;
    }

    const auto dstIdx = static_cast<std::size_t>(py * dstW + px);
    if (dstIdx >= dst.size()) {
        return;
    }

    if (srcA == 255) {
        dst[dstIdx] = 0xFF000000U |
                      (static_cast<std::uint32_t>(r & 0xFF) << 16U) |
                      (static_cast<std::uint32_t>(g & 0xFF) << 8U) |
                      static_cast<std::uint32_t>(b & 0xFF);
        return;
    }

    const auto existing = dst[dstIdx];
    const int ea = static_cast<int>((existing >> 24U) & 0xFFU);
    const int er = static_cast<int>((existing >> 16U) & 0xFFU);
    const int eg = static_cast<int>((existing >> 8U) & 0xFFU);
    const int eb = static_cast<int>(existing & 0xFFU);
    const int outA = srcA + (ea * (255 - srcA)) / 255;
    const int outR = (r * srcA + er * (255 - srcA)) / 255;
    const int outG = (g * srcA + eg * (255 - srcA)) / 255;
    const int outB = (b * srcA + eb * (255 - srcA)) / 255;
    dst[dstIdx] = (static_cast<std::uint32_t>(outA & 0xFF) << 24U) |
                  (static_cast<std::uint32_t>(outR & 0xFF) << 16U) |
                  (static_cast<std::uint32_t>(outG & 0xFF) << 8U) |
                  static_cast<std::uint32_t>(outB & 0xFF);
}

} // namespace libreshockwave::font
