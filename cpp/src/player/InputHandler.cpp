#include "libreshockwave/player/InputHandler.hpp"

#include <algorithm>
#include <utility>

#include "libreshockwave/player/PlayerEvent.hpp"
#include "libreshockwave/player/event/EventDispatcher.hpp"
#include "libreshockwave/player/input/HitTester.hpp"
#include "libreshockwave/player/input/InputState.hpp"
#include "libreshockwave/player/render/SpriteRegistry.hpp"
#include "libreshockwave/player/render/pipeline/StageRenderer.hpp"

namespace libreshockwave::player {

InputHandler::InputHandler(input::InputState* inputState,
                           render::pipeline::StageRenderer* stageRenderer,
                           event::EventDispatcher* eventDispatcher)
    : inputState_(inputState),
      stageRenderer_(stageRenderer),
      eventDispatcher_(eventDispatcher) {}

void InputHandler::setInputState(input::InputState* inputState) {
    inputState_ = inputState;
}

void InputHandler::setStageRenderer(render::pipeline::StageRenderer* stageRenderer) {
    stageRenderer_ = stageRenderer;
}

void InputHandler::setEventDispatcher(event::EventDispatcher* eventDispatcher) {
    eventDispatcher_ = eventDispatcher;
}

void InputHandler::setEventDispatcherSupplier(EventDispatcherSupplier supplier) {
    eventDispatcherSupplier_ = std::move(supplier);
}

void InputHandler::setCurrentFrameSupplier(CurrentFrameSupplier supplier) {
    currentFrameSupplier_ = std::move(supplier);
}

void InputHandler::setHitSpritesSupplier(HitSpritesSupplier supplier) {
    hitSpritesSupplier_ = std::move(supplier);
}

int InputHandler::previousRolloverSprite() const {
    return previousRolloverSprite_;
}

int InputHandler::hitTestExact(int stageX, int stageY) const {
    if (eventDispatcher() == nullptr) {
        return 0;
    }

    auto exactHits = getInteractiveHits(stageX, stageY, false);
    if (!exactHits.empty()) {
        return exactHits.front();
    }

    auto boundingHits = getInteractiveHits(stageX, stageY, true);
    return boundingHits.empty() ? 0 : boundingHits.front();
}

void InputHandler::onMouseMove(int stageX, int stageY) {
    if (inputState_ == nullptr) {
        return;
    }
    inputState_->setMousePosition(stageX, stageY);
    inputState_->setRolloverSprite(hitTestExact(stageX, stageY));
}

void InputHandler::onMouseDown(int stageX, int stageY, bool rightButton) {
    if (inputState_ == nullptr) {
        return;
    }
    inputState_->setMousePosition(stageX, stageY);
    if (rightButton) {
        inputState_->setRightMouseDown(true);
        inputState_->queueEvent(input::InputEvent::rightMouseDown(stageX, stageY));
        return;
    }

    inputState_->setMouseDown(true);
    inputState_->setClickOnSprite(hitTestExact(stageX, stageY));
    inputState_->setClickLoc(stageX, stageY);
    inputState_->updateDoubleClick(stageX, stageY);
    inputState_->queueEvent(input::InputEvent::mouseDown(stageX, stageY));
}

void InputHandler::onMouseUp(int stageX, int stageY, bool rightButton) {
    if (inputState_ == nullptr) {
        return;
    }
    inputState_->setMousePosition(stageX, stageY);
    if (rightButton) {
        inputState_->setRightMouseDown(false);
        inputState_->queueEvent(input::InputEvent::rightMouseUp(stageX, stageY));
        return;
    }

    inputState_->setMouseDown(false);
    inputState_->queueEvent(input::InputEvent::mouseUp(stageX, stageY));
}

void InputHandler::onBlur() {
    if (inputState_ == nullptr) {
        return;
    }
    const int stageX = inputState_->mouseH();
    const int stageY = inputState_->mouseV();
    if (inputState_->isMouseDown()) {
        inputState_->setMouseDown(false);
        inputState_->queueEvent(input::InputEvent::mouseUp(stageX, stageY));
    }
    if (inputState_->isRightMouseDown()) {
        inputState_->setRightMouseDown(false);
        inputState_->queueEvent(input::InputEvent::rightMouseUp(stageX, stageY));
    }
    inputState_->setRolloverSprite(0);
}

void InputHandler::onKeyDown(int directorKeyCode, std::string keyChar, bool shift, bool ctrl, bool alt) {
    if (inputState_ == nullptr) {
        return;
    }
    inputState_->setLastKey(keyChar);
    inputState_->setLastKeyCode(directorKeyCode);
    inputState_->setShiftDown(shift);
    inputState_->setControlDown(ctrl);
    inputState_->setAltDown(alt);
    inputState_->queueEvent(input::InputEvent::keyDown(directorKeyCode, std::move(keyChar)));
}

void InputHandler::onKeyUp(int directorKeyCode, std::string keyChar, bool shift, bool ctrl, bool alt) {
    if (inputState_ == nullptr) {
        return;
    }
    inputState_->setShiftDown(shift);
    inputState_->setControlDown(ctrl);
    inputState_->setAltDown(alt);
    inputState_->queueEvent(input::InputEvent::keyUp(directorKeyCode, std::move(keyChar)));
}

bool InputHandler::processInputEvents() {
    if (inputState_ == nullptr) {
        return false;
    }

    bool hadEvents = false;
    while (auto queued = inputState_->pollEvent()) {
        dispatchInputEvent(*queued);
        hadEvents = true;
    }

    dispatchRolloverEvents();

    if (hadEvents && stageRenderer_ != nullptr) {
        stageRenderer_->spriteRegistry().bumpRevision();
    }

    if (inputState_->keyboardFocusSprite() > 0) {
        inputState_->incrementCaretBlink();
    }

    return hadEvents;
}

event::EventDispatcher* InputHandler::eventDispatcher() const {
    if (eventDispatcherSupplier_) {
        return eventDispatcherSupplier_();
    }
    return eventDispatcher_;
}

std::vector<render::pipeline::RenderSprite> InputHandler::hitSprites() const {
    if (hitSpritesSupplier_) {
        return hitSpritesSupplier_();
    }
    if (stageRenderer_ == nullptr) {
        return {};
    }

    const auto& baked = stageRenderer_->lastBakedSprites();
    if (!baked.empty()) {
        return baked;
    }

    if (currentFrameSupplier_) {
        const int frame = currentFrameSupplier_();
        if (frame > 0) {
            return stageRenderer_->getSpritesForFrame(frame);
        }
    }
    return {};
}

std::vector<int> InputHandler::getInteractiveHits(int stageX, int stageY, bool forceBoundingBox) const {
    auto* dispatcher = eventDispatcher();
    if (dispatcher == nullptr) {
        return {};
    }

    auto sprites = hitSprites();
    auto hitChannels = input::HitTester::hitTestAll(
        sprites,
        stageX,
        stageY,
        [dispatcher, forceBoundingBox](int channel) {
            return forceBoundingBox && dispatcher->isSpriteMouseInteractive(channel);
        });

    std::vector<int> interactive;
    interactive.reserve(hitChannels.size());
    std::copy_if(hitChannels.begin(), hitChannels.end(), std::back_inserter(interactive), [dispatcher](int channel) {
        return dispatcher->isSpriteMouseInteractive(channel);
    });
    return interactive;
}

void InputHandler::dispatchRolloverEvents() {
    if (inputState_ == nullptr) {
        return;
    }

    auto* dispatcher = eventDispatcher();
    if (dispatcher == nullptr) {
        return;
    }

    const int currentRollover = inputState_->rolloverSprite();
    if (currentRollover != previousRolloverSprite_) {
        if (previousRolloverSprite_ > 0) {
            dispatcher->dispatchSpriteEvent(previousRolloverSprite_, PlayerEvent::MouseLeave);
        }
        if (currentRollover > 0) {
            dispatcher->dispatchSpriteEvent(currentRollover, PlayerEvent::MouseEnter);
        }
        previousRolloverSprite_ = currentRollover;
    }

    if (currentRollover > 0) {
        dispatcher->dispatchSpriteEvent(currentRollover, PlayerEvent::MouseWithin);
    }
}

void InputHandler::dispatchInputEvent(const input::InputEvent& inputEvent) {
    if (inputState_ == nullptr) {
        return;
    }

    auto* dispatcher = eventDispatcher();
    if (dispatcher == nullptr) {
        return;
    }

    switch (inputEvent.type) {
        case input::InputEventType::MouseDown: {
            const int hitSprite = hitTestExact(inputEvent.stageX, inputEvent.stageY);
            const int lastClicked = inputState_->clickOnSprite();
            if (lastClicked > 0 && lastClicked != hitSprite) {
                dispatcher->dispatchSpriteEvent(lastClicked, "mouseUpOutSide");
            }

            inputState_->setClickOnSprite(hitSprite);
            dispatcher->resetEventStopped();
            if (hitSprite > 0) {
                dispatcher->dispatchSpriteEvent(hitSprite, PlayerEvent::MouseDown);
            }
            dispatcher->dispatchFrameAndMovieEvent(PlayerEvent::MouseDown);
            break;
        }
        case input::InputEventType::MouseUp: {
            const int pressedSprite = inputState_->clickOnSprite();
            const int releaseSprite = hitTestExact(inputEvent.stageX, inputEvent.stageY);
            dispatcher->resetEventStopped();
            if (pressedSprite > 0 && releaseSprite == pressedSprite) {
                dispatcher->dispatchSpriteEvent(pressedSprite, PlayerEvent::MouseUp);
            } else if (pressedSprite > 0 && dispatcher->spriteHasHandler(pressedSprite, "mouseUpOutSide")) {
                dispatcher->dispatchSpriteEvent(pressedSprite, "mouseUpOutSide");
            } else if (pressedSprite > 0 && dispatcher->spriteHasHandler(pressedSprite, handlerName(PlayerEvent::MouseUp))) {
                dispatcher->dispatchSpriteEvent(pressedSprite, PlayerEvent::MouseUp);
            } else if (releaseSprite > 0) {
                dispatcher->dispatchSpriteEvent(releaseSprite, PlayerEvent::MouseUp);
            }
            inputState_->setClickOnSprite(0);
            dispatcher->dispatchFrameAndMovieEvent(PlayerEvent::MouseUp);
            break;
        }
        case input::InputEventType::RightMouseDown:
            dispatcher->dispatchFrameAndMovieEvent(PlayerEvent::RightMouseDown);
            break;
        case input::InputEventType::RightMouseUp:
            dispatcher->dispatchFrameAndMovieEvent(PlayerEvent::RightMouseUp);
            break;
        case input::InputEventType::KeyDown: {
            inputState_->setLastKey(inputEvent.keyChar);
            inputState_->setLastKeyCode(inputEvent.keyCode);
            const int focusSprite = inputState_->keyboardFocusSprite();
            if (focusSprite > 0) {
                dispatcher->dispatchSpriteEvent(focusSprite, PlayerEvent::KeyDown);
            }
            dispatcher->dispatchFrameAndMovieEvent(PlayerEvent::KeyDown);
            break;
        }
        case input::InputEventType::KeyUp: {
            inputState_->setLastKey(inputEvent.keyChar);
            inputState_->setLastKeyCode(inputEvent.keyCode);
            const int focusSprite = inputState_->keyboardFocusSprite();
            if (focusSprite > 0) {
                dispatcher->dispatchSpriteEvent(focusSprite, PlayerEvent::KeyUp);
            }
            dispatcher->dispatchFrameAndMovieEvent(PlayerEvent::KeyUp);
            break;
        }
    }
}

} // namespace libreshockwave::player
