#pragma once

#include <functional>
#include <vector>

#include "libreshockwave/player/input/InputEvent.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"

namespace libreshockwave::player::event {
class EventDispatcher;
}

namespace libreshockwave::player::input {
class InputState;
}

namespace libreshockwave::player::render::pipeline {
class StageRenderer;
}

namespace libreshockwave::player {

class InputHandler {
public:
    using CurrentFrameSupplier = std::function<int()>;
    using EventDispatcherSupplier = std::function<event::EventDispatcher*()>;
    using HitSpritesSupplier = std::function<std::vector<render::pipeline::RenderSprite>()>;

    InputHandler(input::InputState* inputState = nullptr,
                 render::pipeline::StageRenderer* stageRenderer = nullptr,
                 event::EventDispatcher* eventDispatcher = nullptr);

    void setInputState(input::InputState* inputState);
    void setStageRenderer(render::pipeline::StageRenderer* stageRenderer);
    void setEventDispatcher(event::EventDispatcher* eventDispatcher);
    void setEventDispatcherSupplier(EventDispatcherSupplier supplier);
    void setCurrentFrameSupplier(CurrentFrameSupplier supplier);
    void setHitSpritesSupplier(HitSpritesSupplier supplier);

    [[nodiscard]] int previousRolloverSprite() const;
    [[nodiscard]] int hitTestExact(int stageX, int stageY) const;

    void onMouseMove(int stageX, int stageY);
    void onMouseDown(int stageX, int stageY, bool rightButton = false);
    void onMouseUp(int stageX, int stageY, bool rightButton = false);
    void onBlur();
    void onKeyDown(int directorKeyCode, std::string keyChar, bool shift, bool ctrl, bool alt);
    void onKeyUp(int directorKeyCode, std::string keyChar, bool shift, bool ctrl, bool alt);

    [[nodiscard]] bool processInputEvents();

private:
    [[nodiscard]] event::EventDispatcher* eventDispatcher() const;
    [[nodiscard]] std::vector<render::pipeline::RenderSprite> hitSprites() const;
    [[nodiscard]] std::vector<int> getInteractiveHits(int stageX, int stageY, bool forceBoundingBox) const;
    void dispatchRolloverEvents();
    void dispatchInputEvent(const input::InputEvent& inputEvent);

    input::InputState* inputState_{nullptr};
    render::pipeline::StageRenderer* stageRenderer_{nullptr};
    event::EventDispatcher* eventDispatcher_{nullptr};
    EventDispatcherSupplier eventDispatcherSupplier_;
    CurrentFrameSupplier currentFrameSupplier_;
    HitSpritesSupplier hitSpritesSupplier_;
    int previousRolloverSprite_{0};
};

} // namespace libreshockwave::player
