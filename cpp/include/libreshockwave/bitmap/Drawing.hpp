#pragma once

#include <cstdint>
#include <memory>

namespace libreshockwave::bitmap {

class Bitmap;

namespace Drawing {

void fillRect(Bitmap& dest, int x, int y, int width, int height, std::uint32_t argb);
void drawRect(Bitmap& dest, int x, int y, int width, int height, std::uint32_t argb);
void drawLine(Bitmap& dest, int x0, int y0, int x1, int y1, std::uint32_t argb);
void fillEllipse(Bitmap& dest, int cx, int cy, int rx, int ry, std::uint32_t argb);
void drawEllipse(Bitmap& dest, int cx, int cy, int rx, int ry, std::uint32_t argb);
[[nodiscard]] std::shared_ptr<Bitmap> createMatte(const Bitmap& src, int alphaThreshold = 0);
[[nodiscard]] std::shared_ptr<Bitmap> createMask(const Bitmap& src, int alphaThreshold = 0);

} // namespace Drawing

} // namespace libreshockwave::bitmap
