#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/bitmap/Palette.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/player/render/pipeline/BitmapCache.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"

namespace libreshockwave::cast {
struct ShapeInfo;
}

namespace libreshockwave::player::render::pipeline {

class SpriteBaker {
public:
    using BitmapDecodeProvider = std::function<std::shared_ptr<const bitmap::Bitmap>(
        const chunks::CastMemberChunk& member,
        const bitmap::Palette* paletteOverride)>;
    using TextBakeProvider = std::function<std::shared_ptr<const bitmap::Bitmap>(const RenderSprite& sprite)>;
    using SupportsFn = std::function<bool(const RenderSprite& sprite)>;
    using BakeFn = std::function<std::shared_ptr<const bitmap::Bitmap>(const RenderSprite& sprite)>;

    struct SpriteBakeStep {
        std::string name;
        SupportsFn supports;
        BakeFn bake;
    };

    explicit SpriteBaker(BitmapCache* bitmapCache = nullptr);

    [[nodiscard]] int tickCounter() const;
    [[nodiscard]] RenderSprite bake(const RenderSprite& sprite);
    [[nodiscard]] std::vector<RenderSprite> bakeSprites(const std::vector<RenderSprite>& sprites);

    void registerBakeStep(SpriteBakeStep step);
    [[nodiscard]] const std::vector<SpriteBakeStep>& bakeSteps() const;
    [[nodiscard]] int bakeStepCount() const;

    void setBitmapDecodeProvider(BitmapDecodeProvider provider);
    void setTextBakeProvider(TextBakeProvider provider);

    [[nodiscard]] BitmapCache& bitmapCache();
    [[nodiscard]] const BitmapCache& bitmapCache() const;

private:
    void registerDefaultSteps();

    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeBitmap(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeText(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeShape(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeFilmLoop(const RenderSprite& sprite);

    [[nodiscard]] bitmap::Bitmap processDecodedBitmap(const bitmap::Bitmap& raw, const RenderSprite& sprite) const;
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> cachedBitmap(const RenderSprite& sprite) const;
    void cacheBitmap(const RenderSprite& sprite, std::shared_ptr<const bitmap::Bitmap> bitmap);

    [[nodiscard]] static bitmap::Bitmap drawShapeBitmap(const RenderSprite& sprite, const cast::ShapeInfo* shapeInfo);
    static void drawAuthoredShape(bitmap::Bitmap& bitmap, const RenderSprite& sprite, const cast::ShapeInfo& shapeInfo);
    static void fillSolidShape(bitmap::Bitmap& bitmap, int rgb);
    static void drawRect(bitmap::Bitmap& bitmap, int x, int y, int width, int height, std::uint32_t argb);
    static void drawOval(bitmap::Bitmap& bitmap, int cx, int cy, int rx, int ry, std::uint32_t argb, bool filled);
    static void drawLine(bitmap::Bitmap& bitmap, int x0, int y0, int x1, int y1, std::uint32_t argb);
    [[nodiscard]] static bitmap::Bitmap applySimpleTransparencyInk(const bitmap::Bitmap& src,
                                                                   const RenderSprite& sprite);

    BitmapCache ownedBitmapCache_;
    BitmapCache* bitmapCache_{nullptr};
    BitmapDecodeProvider bitmapDecodeProvider_;
    TextBakeProvider textBakeProvider_;
    std::vector<SpriteBakeStep> bakeSteps_;
    int tickCounter_{0};
};

} // namespace libreshockwave::player::render::pipeline
