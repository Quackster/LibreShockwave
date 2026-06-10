#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/bitmap/Palette.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/ScoreChunk.hpp"
#include "libreshockwave/player/render/pipeline/BitmapCache.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"

namespace libreshockwave::cast {
struct ShapeInfo;
}

namespace libreshockwave::player::render::output {
class TextRenderer;
}

namespace libreshockwave::player::render::pipeline {

class SpriteBaker {
public:
    using BitmapDecodeProvider = std::function<std::shared_ptr<const bitmap::Bitmap>(
        const chunks::CastMemberChunk& member,
        const bitmap::Palette* paletteOverride)>;
    using LiveBitmapProvider = std::function<std::shared_ptr<const bitmap::Bitmap>(const RenderSprite& sprite)>;
    using TextBakeProvider = std::function<std::shared_ptr<const bitmap::Bitmap>(const RenderSprite& sprite)>;
    using FilmLoopBakeProvider = std::function<std::shared_ptr<const bitmap::Bitmap>(
        const RenderSprite& sprite,
        int tickCounter)>;
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
    void setLiveBitmapProvider(LiveBitmapProvider provider);
    void setTextBakeProvider(TextBakeProvider provider);
    void setTextRenderer(output::TextRenderer* renderer);
    void setFilmLoopBakeProvider(FilmLoopBakeProvider provider);

    [[nodiscard]] BitmapCache& bitmapCache();
    [[nodiscard]] const BitmapCache& bitmapCache() const;

private:
    void registerDefaultSteps();

    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeBitmap(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeText(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeShape(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeFilmLoop(const RenderSprite& sprite);

    [[nodiscard]] bitmap::Bitmap processDecodedBitmap(const bitmap::Bitmap& raw, const RenderSprite& sprite) const;
    [[nodiscard]] bitmap::Bitmap processLiveBitmap(const bitmap::Bitmap& live, const RenderSprite& sprite) const;
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeDynamicText(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeFileBackedText(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeFileBackedFilmLoop(const RenderSprite& sprite);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> bakeFilmLoopMemberBitmap(
        const std::shared_ptr<chunks::CastMemberChunk>& member,
        const chunks::ScoreChunk::ChannelData& data);
    [[nodiscard]] std::shared_ptr<const bitmap::Bitmap> cachedBitmap(const RenderSprite& sprite) const;
    void cacheBitmap(const RenderSprite& sprite, std::shared_ptr<const bitmap::Bitmap> bitmap);

    [[nodiscard]] static bitmap::Bitmap drawShapeBitmap(const RenderSprite& sprite,
                                                        const ::libreshockwave::cast::ShapeInfo* shapeInfo);
    static void drawAuthoredShape(bitmap::Bitmap& bitmap,
                                  const RenderSprite& sprite,
                                  const ::libreshockwave::cast::ShapeInfo& shapeInfo);
    static void fillSolidShape(bitmap::Bitmap& bitmap, int rgb);
    static void drawRect(bitmap::Bitmap& bitmap, int x, int y, int width, int height, std::uint32_t argb);
    static void drawOval(bitmap::Bitmap& bitmap, int cx, int cy, int rx, int ry, std::uint32_t argb, bool filled);
    static void drawLine(bitmap::Bitmap& bitmap, int x0, int y0, int x1, int y1, std::uint32_t argb);

    BitmapCache ownedBitmapCache_;
    BitmapCache* bitmapCache_{nullptr};
    BitmapDecodeProvider bitmapDecodeProvider_;
    LiveBitmapProvider liveBitmapProvider_;
    TextBakeProvider textBakeProvider_;
    output::TextRenderer* textRenderer_{nullptr};
    FilmLoopBakeProvider filmLoopBakeProvider_;
    std::vector<SpriteBakeStep> bakeSteps_;
    int tickCounter_{0};
};

} // namespace libreshockwave::player::render::pipeline
