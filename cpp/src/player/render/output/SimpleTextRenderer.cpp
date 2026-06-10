#include "libreshockwave/player/render/output/SimpleTextRenderer.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "libreshockwave/player/cast/FontRegistry.hpp"

namespace libreshockwave::player::render::output {
namespace {

using bitmap::Bitmap;
using font::BitmapFont;
using ::libreshockwave::player::cast::FontRegistry;

std::string lowerAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

int alignmentOffset(std::string_view alignment, int fieldWidth, int lineWidth) {
    if (fieldWidth <= 0) {
        return 0;
    }
    if (alignment == "center") {
        return std::max(0, (fieldWidth - lineWidth) / 2);
    }
    if (alignment == "right") {
        return std::max(0, fieldWidth - lineWidth);
    }
    return 0;
}

int renderAlignmentX(std::string_view alignment, int width, int lineWidth) {
    if (alignment == "center") {
        return (width - lineWidth) / 2;
    }
    if (alignment == "right") {
        return width - lineWidth;
    }
    return 0;
}

bool isDefaultLeading(const BitmapFont* font, int lineHeight, int topSpacing) {
    return topSpacing == 1 && font != nullptr && lineHeight == font->getFontSize();
}

int bitmapLineAdvance(const BitmapFont* font, int lineHeight, int topSpacing) {
    return lineHeight + (isDefaultLeading(font, lineHeight, topSpacing) ? 0 : topSpacing);
}

int excludedLeading(const BitmapFont* font, int lineHeight, int topSpacing) {
    return isDefaultLeading(font, lineHeight, topSpacing) ? topSpacing : 0;
}

int findInkBottom(const std::vector<std::uint32_t>& pixels,
                  int width,
                  int height,
                  int startX,
                  int endX,
                  int startY,
                  int endY) {
    const int clampedStartX = std::max(0, startX);
    const int clampedEndX = std::min(width, endX);
    const int clampedStartY = std::max(0, startY);
    const int clampedEndY = std::min(height - 1, endY);
    for (int y = clampedEndY; y >= clampedStartY; --y) {
        for (int x = clampedStartX; x < clampedEndX; ++x) {
            if (((pixels[static_cast<std::size_t>(y * width + x)] >> 24U) & 0xFFU) != 0) {
                return y;
            }
        }
    }
    return clampedStartY;
}

std::array<int, 2> findHorizontalInkBounds(const std::vector<std::uint32_t>& pixels,
                                           int width,
                                           int height,
                                           int startX,
                                           int endX,
                                           int startY,
                                           int endY) {
    const int clampedStartX = std::max(0, startX);
    const int clampedEndX = std::min(width, endX);
    const int clampedStartY = std::max(0, startY);
    const int clampedEndY = std::min(height - 1, endY);
    int left = clampedEndX;
    int right = clampedStartX;
    for (int y = clampedStartY; y <= clampedEndY; ++y) {
        for (int x = clampedStartX; x < clampedEndX; ++x) {
            if (((pixels[static_cast<std::size_t>(y * width + x)] >> 24U) & 0xFFU) != 0) {
                left = std::min(left, x);
                right = std::max(right, x + 1);
            }
        }
    }
    if (left >= right) {
        return {clampedStartX, clampedEndX};
    }
    return {left, right};
}

int extendUnderlineRightEdge(int inkRightX, int spanEndX, int width) {
    return std::min(width, std::min(spanEndX, inkRightX + 1));
}

void drawUnderline(std::vector<std::uint32_t>& pixels,
                   int width,
                   int height,
                   int y,
                   int lineStartX,
                   int lineEndX,
                   std::uint32_t textColor) {
    if (y < 0 || y >= height) {
        return;
    }
    for (int x = std::max(0, lineStartX); x < std::min(width, lineEndX); ++x) {
        pixels[static_cast<std::size_t>(y * width + x)] = textColor;
    }
}

std::shared_ptr<Bitmap> applyTextAA(const Bitmap& bitmap) {
    const int width = bitmap.width();
    const int height = bitmap.height();
    const auto& src = bitmap.pixels();
    auto dst = src;
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            const auto index = static_cast<std::size_t>(y * width + x);
            const auto current = src[index];
            bool boundary = false;
            for (int dy = -1; dy <= 1 && !boundary; ++dy) {
                for (int dx = -1; dx <= 1 && !boundary; ++dx) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    if (src[static_cast<std::size_t>((y + dy) * width + (x + dx))] != current) {
                        boundary = true;
                    }
                }
            }
            if (!boundary) {
                continue;
            }

            int a = 0;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    const auto pixel = src[static_cast<std::size_t>((y + dy) * width + (x + dx))];
                    a += static_cast<int>((pixel >> 24U) & 0xFFU);
                    r += static_cast<int>((pixel >> 16U) & 0xFFU);
                    g += static_cast<int>((pixel >> 8U) & 0xFFU);
                    b += static_cast<int>(pixel & 0xFFU);
                }
            }
            dst[index] = (static_cast<std::uint32_t>(a / 9) << 24U) |
                         (static_cast<std::uint32_t>(r / 9) << 16U) |
                         (static_cast<std::uint32_t>(g / 9) << 8U) |
                         static_cast<std::uint32_t>(b / 9);
        }
    }
    auto result = std::make_shared<Bitmap>(width, height, 32, std::move(dst));
    result->markScriptModified();
    return result;
}

int builtinScale(int fontSize) {
    return std::max(1, fontSize / 8);
}

int builtinCharWidth(int fontSize) {
    return 6 * builtinScale(fontSize);
}

int builtinLineHeight(int fontSize) {
    return 9 * builtinScale(fontSize);
}

int builtinAscent(int fontSize) {
    return 7 * builtinScale(fontSize);
}

constexpr std::array<std::array<std::uint8_t, 5>, 95> FONT_5X7{{
    {{0x00, 0x00, 0x00, 0x00, 0x00}}, // 32 space
    {{0x00, 0x00, 0x5F, 0x00, 0x00}}, // 33 !
    {{0x00, 0x07, 0x00, 0x07, 0x00}}, // 34 "
    {{0x14, 0x7F, 0x14, 0x7F, 0x14}}, // 35 #
    {{0x24, 0x2A, 0x7F, 0x2A, 0x12}}, // 36 $
    {{0x23, 0x13, 0x08, 0x64, 0x62}}, // 37 %
    {{0x36, 0x49, 0x55, 0x22, 0x50}}, // 38 &
    {{0x00, 0x05, 0x03, 0x00, 0x00}}, // 39 '
    {{0x00, 0x1C, 0x22, 0x41, 0x00}}, // 40 (
    {{0x00, 0x41, 0x22, 0x1C, 0x00}}, // 41 )
    {{0x14, 0x08, 0x3E, 0x08, 0x14}}, // 42 *
    {{0x08, 0x08, 0x3E, 0x08, 0x08}}, // 43 +
    {{0x00, 0x50, 0x30, 0x00, 0x00}}, // 44 ,
    {{0x08, 0x08, 0x08, 0x08, 0x08}}, // 45 -
    {{0x00, 0x60, 0x60, 0x00, 0x00}}, // 46 .
    {{0x20, 0x10, 0x08, 0x04, 0x02}}, // 47 /
    {{0x3E, 0x51, 0x49, 0x45, 0x3E}}, // 48 0
    {{0x00, 0x42, 0x7F, 0x40, 0x00}}, // 49 1
    {{0x42, 0x61, 0x51, 0x49, 0x46}}, // 50 2
    {{0x21, 0x41, 0x45, 0x4B, 0x31}}, // 51 3
    {{0x18, 0x14, 0x12, 0x7F, 0x10}}, // 52 4
    {{0x27, 0x45, 0x45, 0x45, 0x39}}, // 53 5
    {{0x3C, 0x4A, 0x49, 0x49, 0x30}}, // 54 6
    {{0x01, 0x71, 0x09, 0x05, 0x03}}, // 55 7
    {{0x36, 0x49, 0x49, 0x49, 0x36}}, // 56 8
    {{0x06, 0x49, 0x49, 0x29, 0x1E}}, // 57 9
    {{0x00, 0x36, 0x36, 0x00, 0x00}}, // 58 :
    {{0x00, 0x56, 0x36, 0x00, 0x00}}, // 59 ;
    {{0x08, 0x14, 0x22, 0x41, 0x00}}, // 60 <
    {{0x14, 0x14, 0x14, 0x14, 0x14}}, // 61 =
    {{0x00, 0x41, 0x22, 0x14, 0x08}}, // 62 >
    {{0x02, 0x01, 0x51, 0x09, 0x06}}, // 63 ?
    {{0x32, 0x49, 0x79, 0x41, 0x3E}}, // 64 @
    {{0x7E, 0x11, 0x11, 0x11, 0x7E}}, // 65 A
    {{0x7F, 0x49, 0x49, 0x49, 0x36}}, // 66 B
    {{0x3E, 0x41, 0x41, 0x41, 0x22}}, // 67 C
    {{0x7F, 0x41, 0x41, 0x22, 0x1C}}, // 68 D
    {{0x7F, 0x49, 0x49, 0x49, 0x41}}, // 69 E
    {{0x7F, 0x09, 0x09, 0x09, 0x01}}, // 70 F
    {{0x3E, 0x41, 0x49, 0x49, 0x7A}}, // 71 G
    {{0x7F, 0x08, 0x08, 0x08, 0x7F}}, // 72 H
    {{0x00, 0x41, 0x7F, 0x41, 0x00}}, // 73 I
    {{0x20, 0x40, 0x41, 0x3F, 0x01}}, // 74 J
    {{0x7F, 0x08, 0x14, 0x22, 0x41}}, // 75 K
    {{0x7F, 0x40, 0x40, 0x40, 0x40}}, // 76 L
    {{0x7F, 0x02, 0x0C, 0x02, 0x7F}}, // 77 M
    {{0x7F, 0x04, 0x08, 0x10, 0x7F}}, // 78 N
    {{0x3E, 0x41, 0x41, 0x41, 0x3E}}, // 79 O
    {{0x7F, 0x09, 0x09, 0x09, 0x06}}, // 80 P
    {{0x3E, 0x41, 0x51, 0x21, 0x5E}}, // 81 Q
    {{0x7F, 0x09, 0x19, 0x29, 0x46}}, // 82 R
    {{0x46, 0x49, 0x49, 0x49, 0x31}}, // 83 S
    {{0x01, 0x01, 0x7F, 0x01, 0x01}}, // 84 T
    {{0x3F, 0x40, 0x40, 0x40, 0x3F}}, // 85 U
    {{0x1F, 0x20, 0x40, 0x20, 0x1F}}, // 86 V
    {{0x3F, 0x40, 0x38, 0x40, 0x3F}}, // 87 W
    {{0x63, 0x14, 0x08, 0x14, 0x63}}, // 88 X
    {{0x07, 0x08, 0x70, 0x08, 0x07}}, // 89 Y
    {{0x61, 0x51, 0x49, 0x45, 0x43}}, // 90 Z
    {{0x00, 0x7F, 0x41, 0x41, 0x00}}, // 91 [
    {{0x02, 0x04, 0x08, 0x10, 0x20}}, // 92 backslash
    {{0x00, 0x41, 0x41, 0x7F, 0x00}}, // 93 ]
    {{0x04, 0x02, 0x01, 0x02, 0x04}}, // 94 ^
    {{0x40, 0x40, 0x40, 0x40, 0x40}}, // 95 _
    {{0x00, 0x01, 0x02, 0x04, 0x00}}, // 96 `
    {{0x20, 0x54, 0x54, 0x54, 0x78}}, // 97 a
    {{0x7F, 0x48, 0x44, 0x44, 0x38}}, // 98 b
    {{0x38, 0x44, 0x44, 0x44, 0x20}}, // 99 c
    {{0x38, 0x44, 0x44, 0x48, 0x7F}}, // 100 d
    {{0x38, 0x54, 0x54, 0x54, 0x18}}, // 101 e
    {{0x08, 0x7E, 0x09, 0x01, 0x02}}, // 102 f
    {{0x0C, 0x52, 0x52, 0x52, 0x3E}}, // 103 g
    {{0x7F, 0x08, 0x04, 0x04, 0x78}}, // 104 h
    {{0x00, 0x44, 0x7D, 0x40, 0x00}}, // 105 i
    {{0x20, 0x40, 0x44, 0x3D, 0x00}}, // 106 j
    {{0x7F, 0x10, 0x28, 0x44, 0x00}}, // 107 k
    {{0x00, 0x41, 0x7F, 0x40, 0x00}}, // 108 l
    {{0x7C, 0x04, 0x18, 0x04, 0x78}}, // 109 m
    {{0x7C, 0x08, 0x04, 0x04, 0x78}}, // 110 n
    {{0x38, 0x44, 0x44, 0x44, 0x38}}, // 111 o
    {{0x7C, 0x14, 0x14, 0x14, 0x08}}, // 112 p
    {{0x08, 0x14, 0x14, 0x18, 0x7C}}, // 113 q
    {{0x7C, 0x08, 0x04, 0x04, 0x08}}, // 114 r
    {{0x48, 0x54, 0x54, 0x54, 0x20}}, // 115 s
    {{0x04, 0x3F, 0x44, 0x40, 0x20}}, // 116 t
    {{0x3C, 0x40, 0x40, 0x20, 0x7C}}, // 117 u
    {{0x1C, 0x20, 0x40, 0x20, 0x1C}}, // 118 v
    {{0x3C, 0x40, 0x30, 0x40, 0x3C}}, // 119 w
    {{0x44, 0x28, 0x10, 0x28, 0x44}}, // 120 x
    {{0x0C, 0x50, 0x50, 0x50, 0x3C}}, // 121 y
    {{0x44, 0x64, 0x54, 0x4C, 0x44}}, // 122 z
    {{0x00, 0x08, 0x36, 0x41, 0x00}}, // 123 {
    {{0x00, 0x00, 0x7F, 0x00, 0x00}}, // 124 |
    {{0x00, 0x41, 0x36, 0x08, 0x00}}, // 125 }
    {{0x10, 0x08, 0x08, 0x10, 0x08}}, // 126 ~
}};

void drawBuiltinChar(char ch,
                     std::vector<std::uint32_t>& pixels,
                     int width,
                     int height,
                     int x,
                     int baselineY,
                     int scale,
                     std::uint32_t color) {
    int index = static_cast<int>(static_cast<unsigned char>(ch)) - 32;
    if (index < 0 || index >= static_cast<int>(FONT_5X7.size())) {
        index = 0;
    }
    const auto& glyph = FONT_5X7[static_cast<std::size_t>(index)];
    const int topY = baselineY - 7 * scale;
    for (int col = 0; col < 5; ++col) {
        const int bits = glyph[static_cast<std::size_t>(col)];
        for (int row = 0; row < 7; ++row) {
            if ((bits & (1 << row)) == 0) {
                continue;
            }
            for (int sy = 0; sy < scale; ++sy) {
                for (int sx = 0; sx < scale; ++sx) {
                    const int px = x + col * scale + sx;
                    const int py = topY + row * scale + sy;
                    if (px >= 0 && px < width && py >= 0 && py < height) {
                        pixels[static_cast<std::size_t>(py * width + px)] = color;
                    }
                }
            }
        }
    }
}

std::shared_ptr<Bitmap> renderWithBitmapFont(const std::shared_ptr<BitmapFont>& font,
                                             const std::string& text,
                                             int width,
                                             int height,
                                             std::string_view alignment,
                                             std::uint32_t textColor,
                                             std::uint32_t bgColor,
                                             bool wordWrap,
                                             int fixedLineSpace,
                                             int topSpacing,
                                             bool syntheticBold,
                                             bool underline) {
    const int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : font->getLineHeight();
    std::vector<std::string> lines;
    for (const auto& rawLine : TextRenderer::splitLines(text)) {
        if (wordWrap) {
            TextRenderer::wrapLine(rawLine,
                                   [&font](std::string_view value) {
                                       return font->getStringWidth(value);
                                   },
                                   width,
                                   lines);
        } else {
            lines.push_back(rawLine);
        }
    }

    const int lineAdvance = bitmapLineAdvance(font.get(), lineHeight, topSpacing);
    const int neededHeight = static_cast<int>(lines.size()) * lineAdvance +
                             excludedLeading(font.get(), lineHeight, topSpacing);
    height = std::max(height, neededHeight);
    std::vector<std::uint32_t> pixels(static_cast<std::size_t>(width * height), bgColor);

    const int leading = std::max(0, lineHeight - font->getLineHeight());
    const int verticalOverflow = std::max(0, font->getLineHeight() - lineHeight);
    int y = topSpacing + (topSpacing > 1 ? 1 : 0);
    for (const auto& line : lines) {
        if (y >= height) {
            break;
        }
        const int lineWidth = font->getStringWidth(line);
        int x = renderAlignmentX(alignment, width, lineWidth);
        const int lineStartX = x;
        const int glyphY = y + leading - verticalOverflow;
        for (char ch : line) {
            font->drawChar(static_cast<unsigned char>(ch), pixels, width, height, x, glyphY, textColor);
            if (syntheticBold) {
                font->drawChar(static_cast<unsigned char>(ch), pixels, width, height, x + 1, glyphY, textColor);
            }
            x += font->getCharWidth(static_cast<unsigned char>(ch));
        }
        if (underline && !line.empty()) {
            const int bottom = findInkBottom(pixels,
                                             width,
                                             height,
                                             lineStartX,
                                             x,
                                             glyphY,
                                             std::min(height - 1, glyphY + font->getLineHeight() - 1));
            const auto bounds = findHorizontalInkBounds(pixels,
                                                        width,
                                                        height,
                                                        lineStartX,
                                                        x,
                                                        glyphY,
                                                        std::min(height - 1, glyphY + font->getLineHeight() - 1));
            const int underlineY = std::min(height - 1, std::max(glyphY, bottom + 1));
            drawUnderline(pixels,
                          width,
                          height,
                          underlineY,
                          bounds[0],
                          extendUnderlineRightEdge(bounds[1], x, width),
                          textColor);
        }
        y += lineAdvance;
    }

    auto bitmap = std::make_shared<Bitmap>(width, height, 32, std::move(pixels));
    bitmap->markScriptModified();
    return bitmap;
}

std::shared_ptr<Bitmap> renderWithBuiltinFont(const std::string& text,
                                              int width,
                                              int height,
                                              int fontSize,
                                              std::string_view alignment,
                                              std::uint32_t textColor,
                                              std::uint32_t bgColor,
                                              bool wordWrap,
                                              int fixedLineSpace,
                                              int topSpacing,
                                              bool underline) {
    const int charWidth = builtinCharWidth(fontSize);
    const int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);
    const int ascent = builtinAscent(fontSize);
    const int scale = builtinScale(fontSize);

    std::vector<std::string> lines;
    for (const auto& rawLine : TextRenderer::splitLines(text)) {
        if (wordWrap) {
            TextRenderer::wrapLine(rawLine,
                                   [charWidth](std::string_view value) {
                                       return static_cast<int>(value.size()) * charWidth;
                                   },
                                   width,
                                   lines);
        } else {
            lines.push_back(rawLine);
        }
    }

    const int lineAdvance = lineHeight + topSpacing;
    const int neededHeight = static_cast<int>(lines.size()) * lineAdvance;
    height = std::max(height, neededHeight);
    std::vector<std::uint32_t> pixels(static_cast<std::size_t>(width * height), bgColor);

    int y = topSpacing;
    for (const auto& line : lines) {
        if (y >= height) {
            break;
        }
        int x = renderAlignmentX(alignment, width, static_cast<int>(line.size()) * charWidth);
        const int lineStartX = x;
        for (char ch : line) {
            drawBuiltinChar(ch, pixels, width, height, x, y + ascent, scale, textColor);
            x += charWidth;
        }
        if (underline && !line.empty()) {
            const int glyphTop = std::max(0, y);
            const int glyphBottom = findInkBottom(pixels,
                                                  width,
                                                  height,
                                                  lineStartX,
                                                  x,
                                                  glyphTop,
                                                  std::min(height - 1, y + ascent));
            drawUnderline(pixels, width, height, std::min(height - 1, glyphBottom + 1), lineStartX, x, textColor);
        }
        y += lineAdvance;
    }

    auto bitmap = std::make_shared<Bitmap>(width, height, 32, std::move(pixels));
    bitmap->markScriptModified();
    return bitmap;
}

} // namespace

std::shared_ptr<Bitmap> SimpleTextRenderer::renderText(std::string text,
                                                       int width,
                                                       int height,
                                                       std::string fontName,
                                                       int fontSize,
                                                       std::string fontStyle,
                                                       std::string alignment,
                                                       int textColor,
                                                       int bgColor,
                                                       bool wordWrap,
                                                       bool antialias,
                                                       int fixedLineSpace,
                                                       int topSpacing) {
    return renderTextInternal(std::move(text),
                              width,
                              height,
                              std::move(fontName),
                              fontSize,
                              std::move(fontStyle),
                              std::move(alignment),
                              textColor,
                              bgColor,
                              wordWrap,
                              antialias,
                              fixedLineSpace,
                              topSpacing,
                              false);
}

std::shared_ptr<Bitmap> SimpleTextRenderer::renderLegacyStxtText(std::string text,
                                                                 int width,
                                                                 int height,
                                                                 std::string fontName,
                                                                 int fontSize,
                                                                 std::string fontStyle,
                                                                 std::string alignment,
                                                                 int textColor,
                                                                 int bgColor,
                                                                 bool wordWrap,
                                                                 bool antialias,
                                                                 int fixedLineSpace,
                                                                 int topSpacing) {
    return renderTextInternal(std::move(text),
                              width,
                              height,
                              std::move(fontName),
                              fontSize,
                              std::move(fontStyle),
                              std::move(alignment),
                              textColor,
                              bgColor,
                              wordWrap,
                              antialias,
                              fixedLineSpace,
                              topSpacing,
                              true);
}

std::shared_ptr<Bitmap> SimpleTextRenderer::renderTextInternal(std::string text,
                                                              int width,
                                                              int height,
                                                              std::string fontName,
                                                              int fontSize,
                                                              std::string fontStyle,
                                                              std::string alignment,
                                                              int textColor,
                                                              int bgColor,
                                                              bool wordWrap,
                                                              bool antialias,
                                                              int fixedLineSpace,
                                                              int topSpacing,
                                                              bool preferRegisteredDirectorFonts) {
    if (width <= 0) {
        width = 200;
    }
    if (height <= 0) {
        height = 1;
    }
    const auto style = lowerAscii(std::move(fontStyle));
    const bool wantsBold = style.find("bold") != std::string::npos;
    const bool wantsItalic = style.find("italic") != std::string::npos;
    const bool underline = style.find("underline") != std::string::npos;

    bool usedRealBold = false;
    auto font = resolveBitmapFont(fontName,
                                  fontSize,
                                  wantsBold,
                                  wantsItalic,
                                  &usedRealBold,
                                  preferRegisteredDirectorFonts);
    std::shared_ptr<Bitmap> bitmap;
    if (font != nullptr) {
        bitmap = renderWithBitmapFont(font,
                                      text,
                                      width,
                                      height,
                                      alignment,
                                      static_cast<std::uint32_t>(textColor),
                                      static_cast<std::uint32_t>(bgColor),
                                      wordWrap,
                                      fixedLineSpace,
                                      topSpacing,
                                      wantsBold && !usedRealBold,
                                      underline);
    } else {
        bitmap = renderWithBuiltinFont(text,
                                       width,
                                       height,
                                       fontSize,
                                       alignment,
                                       static_cast<std::uint32_t>(textColor),
                                       static_cast<std::uint32_t>(bgColor),
                                       wordWrap,
                                       fixedLineSpace,
                                       topSpacing,
                                       underline);
    }
    return antialias && bitmap != nullptr ? applyTextAA(*bitmap) : bitmap;
}

std::vector<int> SimpleTextRenderer::charPosToLoc(std::string text,
                                                  int charIndex,
                                                  std::string fontName,
                                                  int fontSize,
                                                  std::string,
                                                  int fixedLineSpace,
                                                  std::string alignment,
                                                  int fieldWidth) {
    auto font = resolveBitmapFont(fontName, fontSize);
    if (font != nullptr) {
        const int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : font->getLineHeight();
        if (text.empty() || charIndex <= 0) {
            const auto lines = TextRenderer::splitLines(text);
            const int firstLineWidth = lines.empty() ? 0 : font->getStringWidth(lines.front());
            return {alignmentOffset(alignment, fieldWidth, firstLineWidth), 0};
        }
        const auto lineInfo = TextRenderer::findCharLine(text, charIndex);
        const auto lines = TextRenderer::splitLines(text);
        const int lineNum = lineInfo.empty() ? 0 : lineInfo[0];
        const int charsOnLine = lineInfo.size() > 1 ? lineInfo[1] : 0;
        const std::string fullLine = lineNum < static_cast<int>(lines.size()) ? lines[static_cast<std::size_t>(lineNum)] : "";
        const std::string linePrefix = fullLine.substr(0, std::min(charsOnLine, static_cast<int>(fullLine.size())));
        const int x = font->getStringWidth(linePrefix) + (charsOnLine > 0 ? 1 : 0);
        const int alignX = alignmentOffset(alignment, fieldWidth, font->getStringWidth(fullLine));
        return {x + alignX, lineNum * lineHeight};
    }

    const int charWidth = builtinCharWidth(fontSize);
    const int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);
    if (text.empty() || charIndex <= 0) {
        return {alignmentOffset(alignment, fieldWidth, 0), 0};
    }
    const auto lineInfo = TextRenderer::findCharLine(text, charIndex);
    const auto lines = TextRenderer::splitLines(text);
    const int lineNum = lineInfo.empty() ? 0 : lineInfo[0];
    const int charsOnLine = lineInfo.size() > 1 ? lineInfo[1] : 0;
    const std::string fullLine = lineNum < static_cast<int>(lines.size()) ? lines[static_cast<std::size_t>(lineNum)] : "";
    const int x = charsOnLine * charWidth + (charsOnLine > 0 ? 1 : 0);
    const int alignX = alignmentOffset(alignment, fieldWidth, static_cast<int>(fullLine.size()) * charWidth);
    return {x + alignX, lineNum * lineHeight};
}

int SimpleTextRenderer::locToCharPos(std::string text,
                                     int x,
                                     int y,
                                     std::string fontName,
                                     int fontSize,
                                     std::string,
                                     int fixedLineSpace,
                                     std::string alignment,
                                     int fieldWidth) {
    if (text.empty()) {
        return 0;
    }

    const auto lines = TextRenderer::splitLines(text);
    auto font = resolveBitmapFont(fontName, fontSize);
    if (font != nullptr) {
        const int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : font->getLineHeight();
        const int lineIndex = std::max(0,
                                       std::min(y / std::max(1, lineHeight),
                                                static_cast<int>(lines.size()) - 1));
        const int charsBefore = TextRenderer::lineStartIndex(text, lineIndex);
        const auto& line = lines[static_cast<std::size_t>(lineIndex)];
        const int localX = x - alignmentOffset(alignment, fieldWidth, font->getStringWidth(line));
        int cursorX = 0;
        for (int index = 0; index < static_cast<int>(line.size()); ++index) {
            const int width = font->getCharWidth(static_cast<unsigned char>(line[static_cast<std::size_t>(index)]));
            if (cursorX + width / 2 >= localX) {
                return charsBefore + index;
            }
            cursorX += width;
        }
        return charsBefore + static_cast<int>(line.size());
    }

    const int charWidth = builtinCharWidth(fontSize);
    const int lineHeight = fixedLineSpace > 0 ? fixedLineSpace : builtinLineHeight(fontSize);
    const int lineIndex = std::max(0,
                                   std::min(y / std::max(1, lineHeight),
                                            static_cast<int>(lines.size()) - 1));
    const int charsBefore = TextRenderer::lineStartIndex(text, lineIndex);
    const auto& line = lines[static_cast<std::size_t>(lineIndex)];
    const int localX = x - alignmentOffset(alignment, fieldWidth, static_cast<int>(line.size()) * charWidth);
    const int charOnLine = std::min(static_cast<int>(line.size()), (localX + charWidth / 2) / std::max(1, charWidth));
    return charsBefore + std::max(0, charOnLine);
}

int SimpleTextRenderer::getLineHeight(std::string fontName,
                                      int fontSize,
                                      std::string,
                                      int fixedLineSpace) {
    if (fixedLineSpace > 0) {
        return fixedLineSpace;
    }
    auto font = resolveBitmapFont(fontName, fontSize);
    return font != nullptr ? font->getLineHeight() : builtinLineHeight(fontSize);
}

std::shared_ptr<BitmapFont> SimpleTextRenderer::resolveBitmapFont(const std::string& fontName,
                                                                  int fontSize,
                                                                  bool bold,
                                                                  bool italic,
                                                                  bool* usedRealBold,
                                                                  bool preferRegisteredDirectorFonts) {
    if (fontName.empty()) {
        return nullptr;
    }
    auto aliasFont = resolveDirectorFontAlias(fontName,
                                              fontSize,
                                              bold,
                                              italic,
                                              usedRealBold,
                                              preferRegisteredDirectorFonts);
    if (aliasFont != nullptr) {
        return aliasFont;
    }

    if (auto exact = FontRegistry::getBitmapFont(fontName, fontSize, bold, italic); exact != nullptr) {
        if (usedRealBold != nullptr) {
            *usedRealBold = bold && FontRegistry::hasEmbeddedBoldVariant(fontName);
        }
        return exact;
    }

    if (const auto resolved = FontRegistry::resolveFont(fontName); resolved.has_value()) {
        if (auto font = FontRegistry::getBitmapFont(*resolved, fontSize, bold, italic); font != nullptr) {
            if (usedRealBold != nullptr) {
                *usedRealBold = bold && FontRegistry::hasEmbeddedBoldVariant(*resolved);
            }
            return font;
        }
    }

    if (const auto fallback = FontRegistry::getFirstRegisteredFont(); fallback.has_value()) {
        const int fallbackSize = fontSize > 1 ? fontSize - 1 : fontSize;
        return FontRegistry::getBitmapFont(*fallback, fallbackSize);
    }
    return nullptr;
}

std::shared_ptr<BitmapFont> SimpleTextRenderer::resolveDirectorFontAlias(
    const std::string& fontName,
    int fontSize,
    bool bold,
    bool italic,
    bool* usedRealBold,
    bool preferRegisteredDirectorFonts) {
    const auto alias = FontRegistry::getFontAlias(fontName);
    const std::string resolvedName = alias.has_value() ? alias->fontName : fontName;
    const bool resolvedBold = bold || (alias.has_value() && alias->bold);
    const int aliasSize = directorAliasFontSize(fontSize);

    if (preferRegisteredDirectorFonts || alias.has_value()) {
        auto registered = resolveRegisteredDirectorFont(fontName,
                                                        resolvedName,
                                                        aliasSize,
                                                        resolvedBold,
                                                        italic,
                                                        usedRealBold);
        if (registered != nullptr) {
            return registered;
        }
    }

    if (!alias.has_value()) {
        return nullptr;
    }

    if (auto embedded = FontRegistry::getBitmapFont(resolvedName, aliasSize, resolvedBold, italic); embedded != nullptr) {
        if (usedRealBold != nullptr) {
            *usedRealBold = resolvedBold && FontRegistry::hasEmbeddedBoldVariant(resolvedName);
        }
        return embedded;
    }

    if (auto registered = FontRegistry::getBitmapFont(resolvedName, aliasSize); registered != nullptr) {
        return registered;
    }
    const auto resolved = FontRegistry::resolveFont(resolvedName);
    return resolved.has_value() ? FontRegistry::getBitmapFont(*resolved, aliasSize) : nullptr;
}

std::shared_ptr<BitmapFont> SimpleTextRenderer::resolveRegisteredDirectorFont(
    const std::string& originalName,
    const std::string& resolvedName,
    int fontSize,
    bool bold,
    bool italic,
    bool* usedRealBold) {
    if (italic) {
        return nullptr;
    }
    if (bold) {
        auto boldFont = resolveRegisteredPfrCandidate(originalName + "-Bold", fontSize);
        if (boldFont == nullptr) {
            boldFont = resolveRegisteredPfrCandidate(originalName + " Bold", fontSize);
        }
        if (boldFont == nullptr && originalName != resolvedName) {
            boldFont = resolveRegisteredPfrCandidate(resolvedName + "-Bold", fontSize);
            if (boldFont == nullptr) {
                boldFont = resolveRegisteredPfrCandidate(resolvedName + " Bold", fontSize);
            }
        }
        if (boldFont != nullptr) {
            if (usedRealBold != nullptr) {
                *usedRealBold = true;
            }
            return boldFont;
        }
    }

    auto exact = resolveRegisteredPfrCandidate(originalName, fontSize);
    if (exact == nullptr && originalName != resolvedName) {
        exact = resolveRegisteredPfrCandidate(resolvedName, fontSize);
    }
    return exact;
}

std::shared_ptr<BitmapFont> SimpleTextRenderer::resolveRegisteredPfrCandidate(
    const std::string& fontName,
    int fontSize) {
    const auto resolved = FontRegistry::resolveFont(fontName);
    if (!resolved.has_value() || !FontRegistry::hasPfrFont(*resolved)) {
        return nullptr;
    }
    return FontRegistry::getBitmapFont(*resolved, fontSize);
}

int SimpleTextRenderer::directorAliasFontSize(int fontSize) {
    return fontSize >= 11 ? fontSize - 1 : fontSize;
}

} // namespace libreshockwave::player::render::output
