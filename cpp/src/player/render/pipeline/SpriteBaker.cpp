#include "libreshockwave/player/render/pipeline/SpriteBaker.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <optional>
#include <string>
#include <utility>

#include "libreshockwave/cast/CastMember.hpp"
#include "libreshockwave/cast/ShapeInfo.hpp"
#include "libreshockwave/id/Ids.hpp"
#include "libreshockwave/player/render/pipeline/InkProcessor.hpp"

namespace libreshockwave::player::render::pipeline {
namespace {

std::uint32_t opaqueArgb(int rgb) {
    return 0xFF000000U | static_cast<std::uint32_t>(rgb & 0x00FFFFFF);
}

const cast::ShapeInfo* dynamicShapeInfo(const RenderSprite& sprite) {
    const auto dynamic = sprite.dynamicMember();
    if (dynamic == nullptr || !dynamic->shapeInfo().has_value()) {
        return nullptr;
    }
    return &dynamic->shapeInfo().value();
}

bool shouldNeutralizeOpaqueWhiteForScriptCanvas(const RenderSprite& sprite, const bitmap::Bitmap& bitmap) {
    if (bitmap.bitDepth() != 32 || bitmap.isNativeAlpha() || !bitmap.isScriptModified()) {
        return false;
    }
    return sprite.inkMode() == id::InkMode::DARKEN || sprite.inkMode() == id::InkMode::LIGHTEN;
}

bool shouldPreserveOutlinedWhiteBodyForScriptCanvas(const RenderSprite& sprite, const bitmap::Bitmap& bitmap) {
    if (sprite.inkMode() != id::InkMode::MATTE ||
        bitmap.bitDepth() != 32 ||
        !bitmap.isScriptModified() ||
        bitmap.isNativeAlpha()) {
        return false;
    }

    const auto memberName = sprite.memberName();
    return memberName.has_value() &&
           (memberName->starts_with("chat_item_background_") ||
            memberName->starts_with("chat_item_sing_background_"));
}

} // namespace

SpriteBaker::SpriteBaker(BitmapCache* bitmapCache)
    : bitmapCache_(bitmapCache != nullptr ? bitmapCache : &ownedBitmapCache_) {
    registerDefaultSteps();
}

int SpriteBaker::tickCounter() const {
    return tickCounter_;
}

RenderSprite SpriteBaker::bake(const RenderSprite& sprite) {
    std::shared_ptr<const bitmap::Bitmap> baked;
    for (const auto& step : bakeSteps_) {
        if (step.supports && step.bake && step.supports(sprite)) {
            baked = step.bake(sprite);
            break;
        }
    }

    if (baked != nullptr &&
        (sprite.type() == SpriteType::Text || sprite.type() == SpriteType::Button) &&
        (baked->width() != sprite.width() || baked->height() != sprite.height())) {
        const int width = baked->width();
        const int height = baked->height();
        return sprite.withBakedBitmapAndSize(std::move(baked), width, height);
    }

    return sprite.withBakedBitmap(std::move(baked));
}

std::vector<RenderSprite> SpriteBaker::bakeSprites(const std::vector<RenderSprite>& sprites) {
    ++tickCounter_;
    std::vector<RenderSprite> result;
    result.reserve(sprites.size());
    for (const auto& sprite : sprites) {
        result.push_back(bake(sprite));
    }
    return result;
}

void SpriteBaker::registerBakeStep(SpriteBakeStep step) {
    bakeSteps_.push_back(std::move(step));
}

const std::vector<SpriteBaker::SpriteBakeStep>& SpriteBaker::bakeSteps() const {
    return bakeSteps_;
}

int SpriteBaker::bakeStepCount() const {
    return static_cast<int>(bakeSteps_.size());
}

void SpriteBaker::setBitmapDecodeProvider(BitmapDecodeProvider provider) {
    bitmapDecodeProvider_ = std::move(provider);
}

void SpriteBaker::setLiveBitmapProvider(LiveBitmapProvider provider) {
    liveBitmapProvider_ = std::move(provider);
}

void SpriteBaker::setTextBakeProvider(TextBakeProvider provider) {
    textBakeProvider_ = std::move(provider);
}

void SpriteBaker::setFilmLoopBakeProvider(FilmLoopBakeProvider provider) {
    filmLoopBakeProvider_ = std::move(provider);
}

BitmapCache& SpriteBaker::bitmapCache() {
    return *bitmapCache_;
}

const BitmapCache& SpriteBaker::bitmapCache() const {
    return *bitmapCache_;
}

void SpriteBaker::registerDefaultSteps() {
    registerBakeStep(SpriteBakeStep{
        "bitmap",
        [](const RenderSprite& sprite) { return sprite.type() == SpriteType::Bitmap; },
        [this](const RenderSprite& sprite) { return bakeBitmap(sprite); }
    });
    registerBakeStep(SpriteBakeStep{
        "text",
        [](const RenderSprite& sprite) {
            return sprite.type() == SpriteType::Text || sprite.type() == SpriteType::Button;
        },
        [this](const RenderSprite& sprite) { return bakeText(sprite); }
    });
    registerBakeStep(SpriteBakeStep{
        "shape",
        [](const RenderSprite& sprite) { return sprite.type() == SpriteType::Shape; },
        [this](const RenderSprite& sprite) { return bakeShape(sprite); }
    });
    registerBakeStep(SpriteBakeStep{
        "film-loop",
        [](const RenderSprite& sprite) { return sprite.type() == SpriteType::FilmLoop; },
        [this](const RenderSprite& sprite) { return bakeFilmLoop(sprite); }
    });
}

std::shared_ptr<const bitmap::Bitmap> SpriteBaker::bakeBitmap(const RenderSprite& sprite) {
    if (liveBitmapProvider_) {
        auto live = liveBitmapProvider_(sprite);
        if (live != nullptr && live->isScriptModified()) {
            return std::make_shared<bitmap::Bitmap>(processLiveBitmap(*live, sprite));
        }
    }

    if (auto cached = cachedBitmap(sprite)) {
        return cached;
    }

    auto member = sprite.castMember();
    if (member == nullptr || !bitmapDecodeProvider_) {
        return nullptr;
    }

    auto raw = bitmapDecodeProvider_(*member, nullptr);
    if (raw == nullptr) {
        bitmapCache_->markDecodeFailed(*member);
        return nullptr;
    }

    auto processed = std::make_shared<bitmap::Bitmap>(processDecodedBitmap(*raw, sprite));
    cacheBitmap(sprite, processed);
    return processed;
}

std::shared_ptr<const bitmap::Bitmap> SpriteBaker::bakeText(const RenderSprite& sprite) {
    return textBakeProvider_ ? textBakeProvider_(sprite) : nullptr;
}

std::shared_ptr<const bitmap::Bitmap> SpriteBaker::bakeShape(const RenderSprite& sprite) {
    const cast::ShapeInfo* shapeInfo = nullptr;
    auto member = sprite.castMember();
    std::optional<cast::ShapeInfo> parsedShape;
    if (member != nullptr && member->memberType() == cast::MemberType::Shape) {
        parsedShape = cast::ShapeInfo::parse(member->specificData());
        shapeInfo = &parsedShape.value();
    } else {
        shapeInfo = dynamicShapeInfo(sprite);
    }

    auto shape = std::make_shared<bitmap::Bitmap>(drawShapeBitmap(sprite, shapeInfo));
    return shape;
}

std::shared_ptr<const bitmap::Bitmap> SpriteBaker::bakeFilmLoop(const RenderSprite& sprite) {
    if (!filmLoopBakeProvider_) {
        return nullptr;
    }

    auto loop = filmLoopBakeProvider_(sprite, tickCounter_);
    if (loop == nullptr || !InkProcessor::shouldProcessInk(sprite.inkMode())) {
        return loop;
    }

    return std::make_shared<bitmap::Bitmap>(
        InkProcessor::applyInk(*loop, sprite.inkMode(), sprite.backColor(), false, loop->imagePalette().get()));
}

bitmap::Bitmap SpriteBaker::processDecodedBitmap(const bitmap::Bitmap& raw, const RenderSprite& sprite) const {
    bitmap::Bitmap processed = raw.copy();
    if (processed.bitDepth() <= 1 && sprite.hasForeColor() && InkProcessor::allowsColorize(sprite.inkMode())) {
        processed = InkProcessor::applyForeColorRemap(processed,
                                                      static_cast<std::uint32_t>(sprite.foreColor()),
                                                      static_cast<std::uint32_t>(sprite.backColor()));
    }
    if (InkProcessor::shouldProcessInk(sprite.inkMode())) {
        processed = InkProcessor::applyInk(processed,
                                           sprite.inkMode(),
                                           sprite.backColor(),
                                           processed.isNativeAlpha(),
                                           raw.imagePalette().get());
    }

    return BitmapCache::applyIndexedMatteColorRemapIfNeeded(&raw,
                                                            processed,
                                                            sprite.ink(),
                                                            sprite.foreColor(),
                                                            sprite.backColor(),
                                                            sprite.hasForeColor(),
                                                            sprite.hasBackColor(),
                                                            raw.imagePalette().get());
}

bitmap::Bitmap SpriteBaker::processLiveBitmap(const bitmap::Bitmap& live, const RenderSprite& sprite) const {
    bitmap::Bitmap source = live.copy();
    if (source.bitDepth() <= 1 && sprite.hasForeColor()) {
        source = InkProcessor::applyForeColorRemap(source,
                                                   static_cast<std::uint32_t>(sprite.foreColor()),
                                                   static_cast<std::uint32_t>(sprite.backColor()));
    }

    bitmap::Bitmap processed = source.copy();
    if (InkProcessor::shouldProcessInk(sprite.inkMode())) {
        bitmap::Bitmap inkSource = shouldNeutralizeOpaqueWhiteForScriptCanvas(sprite, source)
            ? InkProcessor::convertOpaqueWhiteToTransparent(source)
            : source.copy();
        const bool hasNativeAlpha = inkSource.bitDepth() == 32 && inkSource.isNativeAlpha();
        processed = shouldPreserveOutlinedWhiteBodyForScriptCanvas(sprite, inkSource)
            ? InkProcessor::applyInkPreservingOutlinedWhiteBody(inkSource,
                                                                sprite.inkMode(),
                                                                sprite.backColor(),
                                                                hasNativeAlpha,
                                                                inkSource.imagePalette().get())
            : InkProcessor::applyInk(inkSource,
                                     sprite.inkMode(),
                                     sprite.backColor(),
                                     hasNativeAlpha,
                                     inkSource.imagePalette().get());
    }

    bitmap::Bitmap result = BitmapCache::applyIndexedMatteColorRemapIfNeeded(&source,
                                                                              processed,
                                                                              sprite.ink(),
                                                                              sprite.foreColor(),
                                                                              sprite.backColor(),
                                                                              sprite.hasForeColor(),
                                                                              sprite.hasBackColor(),
                                                                              source.imagePalette().get());
    if (sprite.hasBackColor() &&
        InkProcessor::allowsColorize(sprite.inkMode()) &&
        (sprite.backColor() & 0x00FFFFFF) != 0x00FFFFFF) {
        result = InkProcessor::remapExactColor(result, 0x00FFFFFFU, static_cast<std::uint32_t>(sprite.backColor()));
    }
    return result;
}

std::shared_ptr<const bitmap::Bitmap> SpriteBaker::cachedBitmap(const RenderSprite& sprite) const {
    auto member = sprite.castMember();
    if (member == nullptr) {
        return nullptr;
    }
    return bitmapCache_->getCachedProcessed(*member,
                                            sprite.ink(),
                                            sprite.backColor(),
                                            sprite.foreColor(),
                                            sprite.hasForeColor(),
                                            sprite.hasBackColor());
}

void SpriteBaker::cacheBitmap(const RenderSprite& sprite, std::shared_ptr<const bitmap::Bitmap> bitmap) {
    auto member = sprite.castMember();
    if (member == nullptr || bitmap == nullptr) {
        return;
    }
    bitmapCache_->putProcessed(*member,
                               sprite.ink(),
                               sprite.backColor(),
                               sprite.foreColor(),
                               sprite.hasForeColor(),
                               sprite.hasBackColor(),
                               std::move(bitmap));
}

bitmap::Bitmap SpriteBaker::drawShapeBitmap(const RenderSprite& sprite, const cast::ShapeInfo* shapeInfo) {
    const int width = sprite.width() > 0 ? sprite.width() : 50;
    const int height = sprite.height() > 0 ? sprite.height() : 50;
    bitmap::Bitmap bitmap(width, height, 32);

    if (shapeInfo == nullptr) {
        fillSolidShape(bitmap, sprite.foreColor());
    } else {
        drawAuthoredShape(bitmap, sprite, *shapeInfo);
    }

    if (InkProcessor::shouldProcessInk(sprite.inkMode())) {
        return InkProcessor::applyInk(bitmap, sprite.inkMode(), sprite.backColor(), false, nullptr);
    }
    return bitmap;
}

void SpriteBaker::drawAuthoredShape(bitmap::Bitmap& bitmap,
                                    const RenderSprite& sprite,
                                    const cast::ShapeInfo& shapeInfo) {
    if (shapeInfo.isOutlineInvisible() && shapeInfo.shapeType != cast::ShapeType::Line) {
        return;
    }

    const std::uint32_t argb = opaqueArgb(sprite.foreColor());
    switch (shapeInfo.shapeType) {
        case cast::ShapeType::Rect:
        case cast::ShapeType::OvalRect:
            if (shapeInfo.isFilled()) {
                bitmap.fill(argb);
            } else {
                const int strokes = std::max(0, shapeInfo.lineThickness - 1);
                for (int i = 0; i < strokes; ++i) {
                    drawRect(bitmap, i, i, bitmap.width() - (i * 2), bitmap.height() - (i * 2), argb);
                }
            }
            break;
        case cast::ShapeType::Oval:
            drawOval(bitmap,
                     bitmap.width() / 2,
                     bitmap.height() / 2,
                     std::max(0, bitmap.width() / 2),
                     std::max(0, bitmap.height() / 2),
                     argb,
                     shapeInfo.isFilled());
            break;
        case cast::ShapeType::Line: {
            const int strokes = std::max(1, shapeInfo.lineThickness);
            const bool bottomToTop = shapeInfo.lineDirection == 6;
            const int startY = bottomToTop ? bitmap.height() - 1 : 0;
            const int endY = bottomToTop ? 0 : bitmap.height() - 1;
            for (int i = 0; i < strokes; ++i) {
                drawLine(bitmap,
                         0,
                         std::clamp(startY - i, 0, std::max(0, bitmap.height() - 1)),
                         std::max(0, bitmap.width() - 1),
                         std::clamp(endY - i, 0, std::max(0, bitmap.height() - 1)),
                         argb);
            }
            break;
        }
        case cast::ShapeType::Unknown:
            fillSolidShape(bitmap, sprite.foreColor());
            break;
    }
}

void SpriteBaker::fillSolidShape(bitmap::Bitmap& bitmap, int rgb) {
    bitmap.fill(opaqueArgb(rgb));
}

void SpriteBaker::drawRect(bitmap::Bitmap& bitmap, int x, int y, int width, int height, std::uint32_t argb) {
    if (width <= 0 || height <= 0) {
        return;
    }
    for (int px = x; px < x + width; ++px) {
        bitmap.setPixel(px, y, argb);
        bitmap.setPixel(px, y + height - 1, argb);
    }
    for (int py = y; py < y + height; ++py) {
        bitmap.setPixel(x, py, argb);
        bitmap.setPixel(x + width - 1, py, argb);
    }
}

void SpriteBaker::drawOval(bitmap::Bitmap& bitmap,
                           int cx,
                           int cy,
                           int rx,
                           int ry,
                           std::uint32_t argb,
                           bool filled) {
    if (rx <= 0 || ry <= 0) {
        return;
    }

    for (int y = 0; y < bitmap.height(); ++y) {
        for (int x = 0; x < bitmap.width(); ++x) {
            const double nx = (static_cast<double>(x) + 0.5 - static_cast<double>(cx)) / static_cast<double>(rx);
            const double ny = (static_cast<double>(y) + 0.5 - static_cast<double>(cy)) / static_cast<double>(ry);
            const double value = (nx * nx) + (ny * ny);
            if ((filled && value <= 1.0) || (!filled && value >= 0.75 && value <= 1.15)) {
                bitmap.setPixel(x, y, argb);
            }
        }
    }
}

void SpriteBaker::drawLine(bitmap::Bitmap& bitmap, int x0, int y0, int x1, int y1, std::uint32_t argb) {
    const int dx = std::abs(x1 - x0);
    const int sx = x0 < x1 ? 1 : -1;
    const int dy = -std::abs(y1 - y0);
    const int sy = y0 < y1 ? 1 : -1;
    int error = dx + dy;

    while (true) {
        bitmap.setPixel(x0, y0, argb);
        if (x0 == x1 && y0 == y1) {
            break;
        }
        const int e2 = 2 * error;
        if (e2 >= dy) {
            error += dy;
            x0 += sx;
        }
        if (e2 <= dx) {
            error += dx;
            y0 += sy;
        }
    }
}

} // namespace libreshockwave::player::render::pipeline
