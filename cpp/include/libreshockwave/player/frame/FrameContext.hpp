#pragma once

#include <functional>
#include <memory>
#include <set>
#include <string_view>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/PlayerEvent.hpp"
#include "libreshockwave/player/frame/FrameEvent.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::player::behavior {
class BehaviorManager;
}

namespace libreshockwave::player::event {
class EventDispatcher;
}

namespace libreshockwave::player::render {
class SpriteRegistry;
}

namespace libreshockwave::player::score {
class ScoreNavigator;
}

namespace libreshockwave::player::frame {

class FrameContext {
public:
    using EventListener = std::function<void(const FrameEvent&)>;
    using ActorListDispatcher = std::function<void(std::string_view handlerName)>;
    using TimeoutEventDispatcher = std::function<void(std::string_view handlerName)>;

    explicit FrameContext(DirectorFile* file = nullptr);
    FrameContext(DirectorFile* file,
                 score::ScoreNavigator* navigator,
                 behavior::BehaviorManager* behaviorManager,
                 event::EventDispatcher* eventDispatcher);
    ~FrameContext();

    FrameContext(const FrameContext&) = delete;
    FrameContext& operator=(const FrameContext&) = delete;
    FrameContext(FrameContext&&) noexcept;
    FrameContext& operator=(FrameContext&&) noexcept;

    void setDebugEnabled(bool enabled);
    [[nodiscard]] bool debugEnabled() const;
    void setEventListener(EventListener listener);
    void setActorListDispatcher(ActorListDispatcher dispatcher);
    void setTimeoutEventDispatcher(TimeoutEventDispatcher dispatcher);
    void setSpriteRegistry(render::SpriteRegistry* registry);

    [[nodiscard]] int currentFrame() const;
    [[nodiscard]] int effectiveFrame() const;
    [[nodiscard]] int frameCount() const;
    [[nodiscard]] score::ScoreNavigator& navigator();
    [[nodiscard]] const score::ScoreNavigator& navigator() const;
    [[nodiscard]] behavior::BehaviorManager& behaviorManager();
    [[nodiscard]] const behavior::BehaviorManager& behaviorManager() const;
    [[nodiscard]] event::EventDispatcher& eventDispatcher();
    [[nodiscard]] const event::EventDispatcher& eventDispatcher() const;
    [[nodiscard]] const std::set<int>& activeChannels() const;
    [[nodiscard]] bool inFrameScript() const;

    void goToFrame(int frame);
    void forceGoToFrame(int frame);
    void goToLabel(std::string_view label);
    void initializeFirstFrame();
    [[nodiscard]] bool executeFrame(bool suppressSpriteEnterFrameOnce = false);
    void dispatchBeginSpriteEvents();
    void rebindBehaviorsForLoadedCast(int castLibNumber);
    [[nodiscard]] int advanceFrame();
    void reset();

private:
    void bindDispatcher();
    void enterFrame(int frame);
    void beginSpritesForFrame(int frame);
    void ensureScoreSpriteState(int frame, int channel);
    void endSpritesLeavingFrame(int oldFrame, int newFrame);
    void initializeFrameScript(int frame);
    void dispatchToActorList(std::string_view handlerName);
    void dispatchEvent(PlayerEvent event);
    void dispatchBeginSprite();
    [[nodiscard]] bool hasScoreSpriteBehaviors(int frame) const;
    [[nodiscard]] bool hasFrameOneFrameScriptAndFrameTwoSpriteBehaviorStartupShape() const;
    void logEvent(std::string_view message) const;
    void notifyEvent(PlayerEvent event);

    DirectorFile* file_{nullptr};
    std::unique_ptr<score::ScoreNavigator> ownedNavigator_;
    score::ScoreNavigator* navigator_{nullptr};
    std::unique_ptr<behavior::BehaviorManager> ownedBehaviorManager_;
    behavior::BehaviorManager* behaviorManager_{nullptr};
    std::unique_ptr<event::EventDispatcher> ownedEventDispatcher_;
    event::EventDispatcher* eventDispatcher_{nullptr};
    render::SpriteRegistry* spriteRegistry_{nullptr};

    ActorListDispatcher actorListDispatcher_;
    TimeoutEventDispatcher timeoutEventDispatcher_;
    EventListener eventListener_;
    int currentFrame_{1};
    int pendingFrame_{0};
    bool inFrameScript_{false};
    std::set<int> activeChannels_;
    std::set<int> enteredChannels_;
    bool debugEnabled_{false};
};

} // namespace libreshockwave::player::frame
