#include "libreshockwave/player/Player.hpp"

#include <algorithm>
#include <optional>
#include <string>
#include <utility>

#include "libreshockwave/cast/CastMember.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/PlayerEvent.hpp"
#include "libreshockwave/player/event/EventDispatcher.hpp"
#include "libreshockwave/player/score/ScoreNavigator.hpp"

namespace libreshockwave::player {
namespace {

constexpr int DEFAULT_TEMPO = 15;

int configuredTempo(const std::shared_ptr<DirectorFile>& file) {
    if (file == nullptr) {
        return DEFAULT_TEMPO;
    }
    const int tempo = file->tempo();
    return tempo > 0 ? tempo : DEFAULT_TEMPO;
}

} // namespace

Player::Player(std::shared_ptr<DirectorFile> file)
    : file_(std::move(file)),
      frameContext_(file_.get()),
      stageRenderer_(file_.get()),
      netManager_(),
      movieProperties_(file_.get()),
      spriteProperties_(&stageRenderer_.spriteRegistry()),
      castLibManager_(file_),
      timeoutManager_(),
      soundManager_(file_.get()),
      bitmapCache_(),
      spriteBaker_(&bitmapCache_),
      frameRenderPipeline_(&stageRenderer_, &spriteBaker_),
      inputState_(),
      bitmapResolver_(file_, &castLibManager_, &frameContext_),
      cursorManager_(&inputState_, &stageRenderer_.spriteRegistry()),
      inputHandler_(&inputState_, &stageRenderer_, &frameContext_.eventDispatcher()),
      tempo_(configuredTempo(file_)) {
    wireComponents();
}

std::shared_ptr<DirectorFile> Player::file() const { return file_; }
frame::FrameContext& Player::frameContext() { return frameContext_; }
score::ScoreNavigator& Player::navigator() { return frameContext_.navigator(); }
behavior::BehaviorManager& Player::behaviorManager() { return frameContext_.behaviorManager(); }
event::EventDispatcher& Player::eventDispatcher() { return frameContext_.eventDispatcher(); }
render::pipeline::StageRenderer& Player::stageRenderer() { return stageRenderer_; }
net::NetManager& Player::netManager() { return netManager_; }
MovieProperties& Player::movieProperties() { return movieProperties_; }
SpriteProperties& Player::spriteProperties() { return spriteProperties_; }
cast::CastLibManager& Player::castLibManager() { return castLibManager_; }
timeout::TimeoutManager& Player::timeoutManager() { return timeoutManager_; }
audio::SoundManager& Player::soundManager() { return soundManager_; }
render::pipeline::BitmapCache& Player::bitmapCache() { return bitmapCache_; }
render::pipeline::SpriteBaker& Player::spriteBaker() { return spriteBaker_; }
render::pipeline::FrameRenderPipeline& Player::frameRenderPipeline() { return frameRenderPipeline_; }
input::InputState& Player::inputState() { return inputState_; }
BitmapResolver& Player::bitmapResolver() { return bitmapResolver_; }
CursorManager& Player::cursorManager() { return cursorManager_; }
InputHandler& Player::inputHandler() { return inputHandler_; }
lingo::builtin::BuiltinRegistry& Player::builtinRegistry() { return builtinRegistry_; }
lingo::builtin::BuiltinContext& Player::builtinContext() { return builtinContext_; }

PlayerState Player::state() const { return state_; }
int Player::currentFrame() const { return frameContext_.currentFrame(); }
int Player::effectiveFrame() const { return frameContext_.effectiveFrame(); }
int Player::frameCount() const { return frameContext_.frameCount(); }

int Player::tempo() const {
    const int puppetTempo = movieProperties_.puppetTempo();
    if (puppetTempo > 0) {
        return puppetTempo;
    }
    if (file_ != nullptr) {
        const int scoreTempo = file_->getScoreTempo(currentFrame() - 1);
        if (scoreTempo > 0) {
            return scoreTempo;
        }
    }
    return tempo_;
}

int Player::baseTempo() const { return tempo_; }

void Player::setTempo(int tempo) {
    tempo_ = tempo > 0 ? tempo : DEFAULT_TEMPO;
    inputState_.setCaretBlinkRate(tempo_);
}

void Player::setEventListener(std::function<void(const PlayerEventInfo&)> listener) {
    eventListener_ = std::move(listener);
}

void Player::setDebugEnabled(bool enabled) {
    debugEnabled_ = enabled;
    builtinContext_.debugPlaybackEnabled = enabled;
    frameContext_.setDebugEnabled(enabled);
}

bool Player::debugEnabled() const { return debugEnabled_; }

void Player::play() {
    const bool wasStopped = state_ == PlayerState::Stopped;
    state_ = PlayerState::Playing;
    if (wasStopped) {
        prepareMovieFoundation();
    }
}

void Player::pause() {
    if (state_ == PlayerState::Playing) {
        state_ = PlayerState::Paused;
    }
}

void Player::resume() {
    if (state_ == PlayerState::Paused) {
        state_ = PlayerState::Playing;
    }
}

void Player::stop() {
    if (state_ == PlayerState::Stopped) {
        return;
    }

    eventDispatcher().dispatchToMovieScripts(PlayerEvent::StopMovie);
    frameContext_.reset();
    stageRenderer_.reset();
    timeoutManager_.clear();
    soundManager_.stopAll();
    state_ = PlayerState::Stopped;
}

void Player::goToFrame(int frame) {
    frameContext_.goToFrame(frame);
}

void Player::goToLabel(std::string_view label) {
    frameContext_.goToLabel(label);
}

void Player::stepFrame() {
    if (state_ == PlayerState::Stopped) {
        prepareMovieFoundation();
        state_ = PlayerState::Paused;
    }

    (void)inputHandler_.processInputEvents();
    (void)frameContext_.executeFrame();
    (void)frameContext_.advanceFrame();
}

bool Player::tick() {
    if (state_ != PlayerState::Playing) {
        return state_ == PlayerState::Paused;
    }

    (void)inputHandler_.processInputEvents();
    (void)frameContext_.executeFrame();
    (void)frameContext_.advanceFrame();
    return true;
}

render::pipeline::FrameSnapshot Player::frameSnapshot() {
    const int frame = currentFrame();
    return frameRenderPipeline_.renderFrame(
        frame,
        stageRenderer_.stageWidth(),
        stageRenderer_.stageHeight(),
        stageRenderer_.backgroundColor(),
        stageRenderer_.renderableStageImage(),
        "Frame " + std::to_string(frame) + " | " + std::string(name(state_)));
}

void Player::wireComponents() {
    frameContext_.setSpriteRegistry(&stageRenderer_.spriteRegistry());
    frameContext_.setEventListener([this](const frame::FrameEvent& event) {
        if (eventListener_) {
            eventListener_(PlayerEventInfo{event.event, event.frame, 0});
        }
        if (event.event == PlayerEvent::EnterFrame) {
            stageRenderer_.onFrameEnter(event.frame);
        }
    });

    if (file_ != nullptr && !file_->basePath().empty()) {
        netManager_.setBasePath(file_->basePath());
    }

    movieProperties_.setInputState(&inputState_);
    movieProperties_.setEffectiveFrameSupplier([this] {
        return effectiveFrame();
    });
    movieProperties_.setFrameCountSupplier([this] {
        return frameCount();
    });
    movieProperties_.setTempoSupplier([this] {
        return tempo();
    });
    movieProperties_.setTempoSetter([this](int value) {
        setTempo(value);
    });
    movieProperties_.setCastLibCountSupplier([this] {
        return castLibManager_.getCastLibCount();
    });
    movieProperties_.setStageBackgroundColorSupplier([this] {
        return stageRenderer_.backgroundColor();
    });
    movieProperties_.setStageBackgroundColorSetter([this](int rgb) {
        stageRenderer_.setBackgroundColor(rgb);
    });
    movieProperties_.setStageImageSupplier([this] {
        return lingo::Datum::imageRef(stageRenderer_.stageImage());
    });
    movieProperties_.setGoToFrameHandler([this](int frame) {
        goToFrame(frame);
    });
    movieProperties_.setGoToLabelHandler([this](const std::string& label) {
        goToLabel(label);
    });
    movieProperties_.setFrameForLabelResolver([this](const std::string& label) {
        return std::max(navigator().getFrameForLabel(label), 0);
    });
    movieProperties_.setMarkerFrameResolver([this](int offset) {
        return navigator().getMarkerFrame(currentFrame(), offset);
    });
    movieProperties_.setGotoNetPageHandler([this](const std::string& url, const std::string& target) {
        (void)target;
        (void)netManager_.preloadNetThing(url);
    });
    movieProperties_.setGotoNetMovieHandler([this](const std::string& url) {
        return netManager_.preloadNetThing(url);
    });

    spriteProperties_.setMemberInfoResolver([this](int castLib,
                                                   int memberNum) -> std::optional<SpriteProperties::MemberInfo> {
        auto member = castLibManager_.resolveMember(castLib, memberNum);
        if (!member) {
            return std::nullopt;
        }
        SpriteProperties::MemberInfo info;
        info.width = member->width();
        info.height = member->height();
        info.regX = member->regX();
        info.regY = member->regY();
        info.bitmap = member->isBitmap();
        info.bitmapWidth = member->width();
        info.bitmapHeight = member->height();
        info.bitmapRegX = member->regX();
        info.bitmapRegY = member->regY();
        info.hasImage = member->isBitmap();
        return info;
    });
    spriteProperties_.setMemberNameResolver(
        [this](const std::string& memberName) -> std::optional<lingo::Datum::CastMemberRef> {
            auto member = castLibManager_.findCastMemberByName(memberName);
            if (!member) {
                return std::nullopt;
            }
            return lingo::Datum::CastMemberRef::of(
                id::CastLibId(member->castLib()),
                id::MemberId(member->memberNum())
            );
        });

    spriteBaker_.setBitmapDecodeProvider([this](const chunks::CastMemberChunk& member,
                                                const bitmap::Palette* palette) {
        return bitmapResolver_.decodeBitmapForProvider(member, palette);
    });

    cursorManager_.setSpriteProvider([this] {
        return stageRenderer_.lastBakedSprites();
    });
    cursorManager_.setMemberInfoResolver([this](int castLib,
                                                int memberNum) -> std::optional<CursorManager::MemberInfo> {
        auto member = castLibManager_.resolveMember(castLib, memberNum);
        if (!member) {
            return std::nullopt;
        }
        CursorManager::MemberInfo info;
        info.memberType = member->memberType();
        info.editable = member->isText();
        info.regX = member->regX();
        info.regY = member->regY();
        return info;
    });
    cursorManager_.setBitmapResolver([this](int castLib, int memberNum) {
        return bitmapResolver_.decodeBitmap(castLibManager_.getCastMember(castLib, memberNum));
    });
    cursorManager_.setInteractivePredicate([this](int channel) {
        return eventDispatcher().isSpriteMouseInteractive(channel);
    });
    cursorManager_.setGlobalCursorSupplier([this] {
        return movieProperties_.getMovieProp("cursor");
    });

    inputHandler_.setCurrentFrameSupplier([this] {
        return currentFrame();
    });
    inputHandler_.setEventDispatcherSupplier([this] {
        return &eventDispatcher();
    });

    builtinContext_ = lingo::builtin::BuiltinContext{};
    builtinContext_.movieProperties = &movieProperties_;
    builtinContext_.netManager = &netManager_;
    builtinContext_.soundManager = &soundManager_;
    builtinContext_.spriteProperties = &spriteProperties_;
    builtinContext_.timeoutManager = &timeoutManager_;
    builtinContext_.debugPlaybackEnabled = debugEnabled_;
    castLibManager_.installBuiltinCallbacks(builtinContext_);
    builtinContext_.imagePaletteResolver = [this](const lingo::Datum& paletteRef)
        -> std::optional<lingo::builtin::BuiltinContext::ResolvedPalette> {
        const auto* member = paletteRef.asCastMemberRef();
        if (member == nullptr || member->memberNum() <= 0) {
            return std::nullopt;
        }

        const int castLib = member->castLib > 0 ? member->castLib : 1;
        auto palette = bitmapResolver_.resolvePaletteByMember(castLib, member->memberNum());
        if (palette == nullptr) {
            return std::nullopt;
        }
        return lingo::builtin::BuiltinContext::ResolvedPalette{
            palette,
            lingo::Datum::CastMemberRef::of(id::CastLibId(castLib), id::MemberId(member->memberNum())),
            std::nullopt
        };
    };
}

void Player::prepareMovieFoundation() {
    castLibManager_.preloadCasts(2);
    frameContext_.initializeFirstFrame();
    frameContext_.dispatchBeginSpriteEvents();
    castLibManager_.preloadCasts(1);
}

} // namespace libreshockwave::player
