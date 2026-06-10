#include "libreshockwave/bitmap/Drawing.hpp"

#include <cmath>
#include <cstdlib>

#include "libreshockwave/bitmap/Bitmap.hpp"

namespace libreshockwave::bitmap::Drawing {

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

} // namespace libreshockwave::bitmap::Drawing
