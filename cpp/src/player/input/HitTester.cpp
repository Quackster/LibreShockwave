#include "libreshockwave/player/input/HitTester.hpp"

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/cast/BitmapInfo.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"

namespace libreshockwave::player::input {
namespace {

bool never(int) {
    return false;
}

bool containsPoint(const render::pipeline::RenderSprite& sprite, int stageX, int stageY) {
    const int left = sprite.x();
    const int top = sprite.y();
    const int right = left + sprite.width();
    const int bottom = top + sprite.height();
    return stageX >= left && stageX < right && stageY >= top && stageY < bottom;
}

} // namespace

int HitTester::hitTest(const std::vector<render::pipeline::RenderSprite>& sprites, int stageX, int stageY) {
    return hitTest(sprites, stageX, stageY, never);
}

int HitTester::hitTest(const std::vector<render::pipeline::RenderSprite>& sprites,
                       int stageX,
                       int stageY,
                       const ChannelPredicate& forceBoundingBox) {
    const auto* sprite = findHitSprite(sprites, stageX, stageY, forceBoundingBox);
    return sprite != nullptr ? sprite->channel() : 0;
}

std::optional<render::pipeline::SpriteType> HitTester::hitTestType(
    const std::vector<render::pipeline::RenderSprite>& sprites,
    int stageX,
    int stageY) {
    return hitTestType(sprites, stageX, stageY, never);
}

std::optional<render::pipeline::SpriteType> HitTester::hitTestType(
    const std::vector<render::pipeline::RenderSprite>& sprites,
    int stageX,
    int stageY,
    const ChannelPredicate& forceBoundingBox) {
    const auto* sprite = findHitSprite(sprites, stageX, stageY, forceBoundingBox);
    if (sprite == nullptr) {
        return std::nullopt;
    }
    return sprite->type();
}

std::vector<int> HitTester::hitTestAll(const std::vector<render::pipeline::RenderSprite>& sprites,
                                       int stageX,
                                       int stageY,
                                       const ChannelPredicate& filter) {
    std::vector<int> result;
    for (auto it = sprites.rbegin(); it != sprites.rend(); ++it) {
        const auto& sprite = *it;
        if (!sprite.isVisible() || sprite.channel() <= 0 || !containsPoint(sprite, stageX, stageY)) {
            continue;
        }
        if (filter(sprite.channel()) || hitTestSpritePixel(sprite, stageX, stageY)) {
            result.push_back(sprite.channel());
        }
    }
    return result;
}

bool HitTester::hitTestSpritePixel(const render::pipeline::RenderSprite& sprite, int stageX, int stageY) {
    const auto baked = sprite.bakedBitmap();
    if (!baked) {
        return true;
    }

    const int spriteWidth = sprite.width() > 0 ? sprite.width() : baked->width();
    const int spriteHeight = sprite.height() > 0 ? sprite.height() : baked->height();
    if (spriteWidth <= 0 || spriteHeight <= 0 || baked->width() <= 0 || baked->height() <= 0) {
        return true;
    }

    const int localX = stageX - sprite.x();
    const int localY = stageY - sprite.y();
    if (localX < 0 || localY < 0 || localX >= spriteWidth || localY >= spriteHeight) {
        return false;
    }

    const auto alphaRule = getAlphaHitRule(sprite);
    if (!alphaRule.enabled || alphaRule.threshold <= 0) {
        return true;
    }

    int srcX = (localX * baked->width()) / spriteWidth;
    int srcY = (localY * baked->height()) / spriteHeight;

    if (sprite.isFlipH() != sprite.hasDirectorHorizontalMirror()) {
        srcX = baked->width() - 1 - srcX;
    }
    if (sprite.isFlipV()) {
        srcY = baked->height() - 1 - srcY;
    }

    const int alpha = static_cast<int>((baked->getPixel(srcX, srcY) >> 24) & 0xFFU);
    return alpha >= alphaRule.threshold;
}

AlphaHitRule HitTester::getAlphaHitRule(const render::pipeline::RenderSprite& sprite) {
    const auto baked = sprite.bakedBitmap();
    const auto dynamicMember = sprite.dynamicMember();
    if (dynamicMember) {
        if (usesDynamicInkTransparency(sprite)) {
            return AlphaHitRule{true, 1};
        }
        return AlphaHitRule{};
    }

    if (!baked || !baked->isNativeAlpha() || baked->bitDepth() != 32) {
        return AlphaHitRule{};
    }

    const auto castMember = sprite.castMember();
    if (!castMember || !castMember->isBitmap() || castMember->specificData().size() < 10) {
        return AlphaHitRule{};
    }

    const auto info = cast::BitmapInfo::parse(castMember->specificData());
    if (info.bitDepth != 32) {
        return AlphaHitRule{};
    }

    return AlphaHitRule{true, info.alphaThreshold};
}

bool HitTester::usesDynamicInkTransparency(const render::pipeline::RenderSprite& sprite) {
    switch (sprite.inkMode()) {
        case id::InkMode::TRANSPARENT:
        case id::InkMode::REVERSE:
        case id::InkMode::GHOST:
        case id::InkMode::NOT_COPY:
        case id::InkMode::NOT_TRANSPARENT:
        case id::InkMode::NOT_REVERSE:
        case id::InkMode::NOT_GHOST:
        case id::InkMode::MATTE:
        case id::InkMode::MASK:
        case id::InkMode::ADD_PIN:
        case id::InkMode::ADD:
        case id::InkMode::SUBTRACT_PIN:
        case id::InkMode::SUBTRACT:
        case id::InkMode::BACKGROUND_TRANSPARENT:
        case id::InkMode::BLEND:
        case id::InkMode::LIGHTEN:
        case id::InkMode::DARKEN:
            return true;
        case id::InkMode::COPY:
        case id::InkMode::LIGHTEST:
        case id::InkMode::DARKEST:
            return false;
    }
    return false;
}

const render::pipeline::RenderSprite* HitTester::findHitSprite(
    const std::vector<render::pipeline::RenderSprite>& sprites,
    int stageX,
    int stageY,
    const ChannelPredicate& forceBoundingBox) {
    for (auto it = sprites.rbegin(); it != sprites.rend(); ++it) {
        const auto& sprite = *it;
        if (!sprite.isVisible() || sprite.channel() <= 0 || !containsPoint(sprite, stageX, stageY)) {
            continue;
        }
        if (forceBoundingBox(sprite.channel()) || hitTestSpritePixel(sprite, stageX, stageY)) {
            return &sprite;
        }
    }
    return nullptr;
}

} // namespace libreshockwave::player::input
