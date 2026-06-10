#pragma once

#include <functional>
#include <memory>
#include <string_view>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"
#include "libreshockwave/player/BitmapResolver.hpp"
#include "libreshockwave/player/CursorManager.hpp"
#include "libreshockwave/player/InputHandler.hpp"
#include "libreshockwave/player/MovieProperties.hpp"
#include "libreshockwave/player/PlayerEventInfo.hpp"
#include "libreshockwave/player/PlayerState.hpp"
#include "libreshockwave/player/SpriteProperties.hpp"
#include "libreshockwave/player/audio/SoundManager.hpp"
#include "libreshockwave/player/cast/CastLibManager.hpp"
#include "libreshockwave/player/frame/FrameContext.hpp"
#include "libreshockwave/player/input/InputState.hpp"
#include "libreshockwave/player/net/NetManager.hpp"
#include "libreshockwave/player/render/pipeline/BitmapCache.hpp"
#include "libreshockwave/player/render/pipeline/FrameRenderPipeline.hpp"
#include "libreshockwave/player/render/pipeline/FrameSnapshot.hpp"
#include "libreshockwave/player/render/pipeline/SpriteBaker.hpp"
#include "libreshockwave/player/render/pipeline/StageRenderer.hpp"
#include "libreshockwave/player/timeout/TimeoutManager.hpp"

namespace libreshockwave::player::behavior {
class BehaviorManager;
}

namespace libreshockwave::player::event {
class EventDispatcher;
}

namespace libreshockwave::player::score {
class ScoreNavigator;
}

namespace libreshockwave::player {

class Player {
public:
    explicit Player(std::shared_ptr<DirectorFile> file = nullptr);

    Player(const Player&) = delete;
    Player& operator=(const Player&) = delete;
    Player(Player&&) = delete;
    Player& operator=(Player&&) = delete;

    [[nodiscard]] std::shared_ptr<DirectorFile> file() const;
    [[nodiscard]] frame::FrameContext& frameContext();
    [[nodiscard]] score::ScoreNavigator& navigator();
    [[nodiscard]] behavior::BehaviorManager& behaviorManager();
    [[nodiscard]] event::EventDispatcher& eventDispatcher();
    [[nodiscard]] render::pipeline::StageRenderer& stageRenderer();
    [[nodiscard]] net::NetManager& netManager();
    [[nodiscard]] MovieProperties& movieProperties();
    [[nodiscard]] SpriteProperties& spriteProperties();
    [[nodiscard]] cast::CastLibManager& castLibManager();
    [[nodiscard]] timeout::TimeoutManager& timeoutManager();
    [[nodiscard]] audio::SoundManager& soundManager();
    [[nodiscard]] render::pipeline::BitmapCache& bitmapCache();
    [[nodiscard]] render::pipeline::SpriteBaker& spriteBaker();
    [[nodiscard]] render::pipeline::FrameRenderPipeline& frameRenderPipeline();
    [[nodiscard]] input::InputState& inputState();
    [[nodiscard]] BitmapResolver& bitmapResolver();
    [[nodiscard]] CursorManager& cursorManager();
    [[nodiscard]] InputHandler& inputHandler();
    [[nodiscard]] lingo::builtin::BuiltinRegistry& builtinRegistry();
    [[nodiscard]] lingo::builtin::BuiltinContext& builtinContext();

    [[nodiscard]] PlayerState state() const;
    [[nodiscard]] int currentFrame() const;
    [[nodiscard]] int effectiveFrame() const;
    [[nodiscard]] int frameCount() const;
    [[nodiscard]] int tempo() const;
    [[nodiscard]] int baseTempo() const;
    void setTempo(int tempo);

    void setEventListener(std::function<void(const PlayerEventInfo&)> listener);
    void setDebugEnabled(bool enabled);
    [[nodiscard]] bool debugEnabled() const;

    void play();
    void pause();
    void resume();
    void stop();
    void goToFrame(int frame);
    void goToLabel(std::string_view label);
    void stepFrame();
    [[nodiscard]] bool tick();
    [[nodiscard]] render::pipeline::FrameSnapshot frameSnapshot();

private:
    void wireComponents();
    void prepareMovieFoundation();

    std::shared_ptr<DirectorFile> file_;
    frame::FrameContext frameContext_;
    render::pipeline::StageRenderer stageRenderer_;
    net::NetManager netManager_;
    MovieProperties movieProperties_;
    SpriteProperties spriteProperties_;
    cast::CastLibManager castLibManager_;
    timeout::TimeoutManager timeoutManager_;
    audio::SoundManager soundManager_;
    render::pipeline::BitmapCache bitmapCache_;
    render::pipeline::SpriteBaker spriteBaker_;
    render::pipeline::FrameRenderPipeline frameRenderPipeline_;
    input::InputState inputState_;
    BitmapResolver bitmapResolver_;
    CursorManager cursorManager_;
    InputHandler inputHandler_;
    lingo::builtin::BuiltinRegistry builtinRegistry_;
    lingo::builtin::BuiltinContext builtinContext_;
    PlayerState state_{PlayerState::Stopped};
    int tempo_{15};
    bool debugEnabled_{false};
    std::function<void(const PlayerEventInfo&)> eventListener_;
};

} // namespace libreshockwave::player
