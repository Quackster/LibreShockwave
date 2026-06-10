#include "libreshockwave/player/Player.hpp"

#include <algorithm>
#include <memory>
#include <optional>
#include <string>
#include <utility>

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/cast/CastMember.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/vm/dispatch/ImageMethodDispatcher.hpp"
#include "libreshockwave/lingo/xtra/MultiuserXtra.hpp"
#include "libreshockwave/lingo/xtra/XmlParserXtra.hpp"
#include "libreshockwave/player/ExternalCastLoadHandler.hpp"
#include "libreshockwave/player/PlayerEvent.hpp"
#include "libreshockwave/player/debug/LifecycleDiagnostics.hpp"
#include "libreshockwave/player/event/EventDispatcher.hpp"
#include "libreshockwave/player/score/ScoreNavigator.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::player {
namespace {

constexpr int DEFAULT_TEMPO = 15;

class TickDeadlineGuard {
public:
    explicit TickDeadlineGuard(lingo::vm::LingoVM& vm)
        : vm_(vm) {
        vm_.armTickDeadline();
    }

    ~TickDeadlineGuard() {
        vm_.setTickDeadline(0);
    }

private:
    lingo::vm::LingoVM& vm_;
};

struct ResolvedScriptTarget {
    std::shared_ptr<chunks::ScriptChunk> script;
    std::shared_ptr<const chunks::ScriptNamesChunk> scriptNames;
};

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
      xtraManager_(),
      movieProperties_(file_.get()),
      spriteProperties_(&stageRenderer_.spriteRegistry()),
      castLibManager_(file_, [this](int castLibNumber, const std::string& fileName) {
          loadCastFromNetCache(castLibNumber, fileName);
      }),
      timeoutManager_(),
      soundManager_(file_.get()),
      bitmapCache_(),
      spriteBaker_(&bitmapCache_),
      frameRenderPipeline_(&stageRenderer_, &spriteBaker_),
      inputState_(),
      bitmapResolver_(file_, &castLibManager_, &frameContext_),
      cursorManager_(&inputState_, &stageRenderer_.spriteRegistry()),
      inputHandler_(&inputState_, &stageRenderer_, &frameContext_.eventDispatcher(), &castLibManager_),
      vm_(file_.get()),
      tempo_(configuredTempo(file_)) {
    xtraManager_.registerXtra(std::make_unique<lingo::xtra::XmlParserXtra>());
    socketMultiuserBridge_ = std::make_unique<xtra::SocketMultiuserBridge>();
    registerMultiuserXtra(*socketMultiuserBridge_);
    wireComponents();
}

Player::~Player() {
    lingo::vm::dispatch::ImageMethodDispatcher::clearImageMutationCallback(this);
}

std::shared_ptr<DirectorFile> Player::file() const { return file_; }
frame::FrameContext& Player::frameContext() { return frameContext_; }
score::ScoreNavigator& Player::navigator() { return frameContext_.navigator(); }
behavior::BehaviorManager& Player::behaviorManager() { return frameContext_.behaviorManager(); }
event::EventDispatcher& Player::eventDispatcher() { return frameContext_.eventDispatcher(); }
render::pipeline::StageRenderer& Player::stageRenderer() { return stageRenderer_; }
net::NetManager& Player::netManager() { return netManager_; }
lingo::xtra::XtraManager& Player::xtraManager() { return xtraManager_; }
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
lingo::vm::LingoVM& Player::vm() { return vm_; }
lingo::builtin::BuiltinRegistry& Player::builtinRegistry() { return vm_.builtinRegistry(); }
lingo::builtin::BuiltinContext& Player::builtinContext() { return vm_.builtinContext(); }

void Player::receiveUpdate(const lingo::Datum& target) {
    if (target.isVoid()) {
        return;
    }
    if (std::find(updatingObjects_.begin(), updatingObjects_.end(), target) == updatingObjects_.end()) {
        updatingObjects_.push_back(target);
    }
}

void Player::removeUpdate(const lingo::Datum& target) {
    updatingObjects_.erase(std::remove(updatingObjects_.begin(), updatingObjects_.end(), target),
                           updatingObjects_.end());
}

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

void Player::setCastLoadedListener(std::function<void()> listener) {
    castLoadedListener_ = std::move(listener);
}

void Player::setExternalCastLoadListener(std::function<void(const ExternalCastLoadEvent&)> listener) {
    externalCastLoadListener_ = std::move(listener);
}

void Player::addExternalCastLoadHandler(ExternalCastLoadHandler* handler) {
    if (handler != nullptr) {
        externalCastLoadHandlers_.push_back(handler);
    }
}

void Player::setDebugEnabled(bool enabled) {
    debugEnabled_ = enabled;
    vm_.builtinContext().debugPlaybackEnabled = enabled;
    frameContext_.setDebugEnabled(enabled);
}

void Player::setTextRenderer(render::output::TextRenderer* renderer) {
    spriteBaker_.setTextRenderer(renderer);
    castLibManager_.setTextRenderer(renderer);
}

void Player::registerMultiuserXtra(lingo::xtra::MultiuserNetBridge& bridge) {
    xtraManager_.registerXtra(std::make_unique<lingo::xtra::MultiuserXtra>(
        &bridge,
        [this](const lingo::Datum& target,
               const std::string& handlerName,
               const std::vector<lingo::Datum>& args) {
            try {
                if (target.type() == lingo::DatumType::ScriptInstanceRef &&
                    vm_.builtinContext().callTargetHandler) {
                    (void)vm_.builtinContext().callTargetHandler(target, handlerName, args);
                    return;
                }
                (void)vm_.callHandler(handlerName, args);
            } catch (...) {
                // Match Java Player callback behavior: Xtra callbacks do not break frame polling.
            }
        }));
}

void Player::setExternalParams(std::vector<std::pair<std::string, std::string>> params) {
    externalParams_ = std::move(params);
    vm_.builtinContext().externalParams = externalParams_;
}

const std::vector<std::pair<std::string, std::string>>& Player::externalParams() const {
    return externalParams_;
}

void Player::setInitialBuiltinVariable(std::string variableName, lingo::Datum defaultValue) {
    if (variableName.empty()) {
        return;
    }
    for (auto& entry : initialBuiltinVariables_) {
        if (entry.first == variableName) {
            entry.second = defaultValue.deepCopy();
            return;
        }
    }
    initialBuiltinVariables_.emplace_back(std::move(variableName), defaultValue.deepCopy());
}

void Player::setInitialBuiltinVariables(std::vector<std::pair<std::string, lingo::Datum>> values) {
    initialBuiltinVariables_.clear();
    for (auto& [name, value] : values) {
        setInitialBuiltinVariable(std::move(name), std::move(value));
    }
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

    if (timeoutSystemEventDispatcher_) {
        timeoutSystemEventDispatcher_("stopMovie");
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

    TickDeadlineGuard deadlineGuard(vm_);
    (void)inputHandler_.processInputEvents();
    (void)frameContext_.executeFrame();
    if (timeoutProcessor_) {
        timeoutProcessor_();
    }
    xtraManager_.tickAll();
    vm_.flushDeferredTasks();
    (void)frameContext_.advanceFrame();
}

bool Player::tick() {
    if (state_ != PlayerState::Playing) {
        return state_ == PlayerState::Paused;
    }

    TickDeadlineGuard deadlineGuard(vm_);
    (void)inputHandler_.processInputEvents();
    (void)frameContext_.executeFrame();
    if (timeoutProcessor_) {
        timeoutProcessor_();
    }
    xtraManager_.tickAll();
    processUpdatingObjects();
    vm_.flushDeferredTasks();
    (void)frameContext_.advanceFrame();
    return true;
}

int Player::preloadAllCasts() {
    int count = 0;
    for (const auto& [_, castLib] : castLibManager_.castLibs()) {
        if (!castLib || !castLib->isExternal() || castLib->isLoaded() || castLib->isFetching()) {
            continue;
        }
        const std::string fileName = util::getFileName(castLib->fileName());
        if (fileName.empty()) {
            continue;
        }
        castLib->markFetching();
        (void)netManager_.preloadNetThing(fileName);
        ++count;
    }
    return count;
}

void Player::onSynchronousExternalCastLoad(int castLibNumber) {
    if (castLibNumber <= 0) {
        return;
    }

    bitmapCache_.clear();
    vm_.invalidateHandlerCache();
    stageRenderer_.spriteRegistry().bumpRevision();
    frameContext_.rebindBehaviorsForLoadedCast(castLibNumber);
    notifyExternalCastLoaded(castLibNumber);
}

bool Player::loadExternalCastFromCachedData(int castLibNumber, const std::vector<std::uint8_t>& data) {
    return loadExternalCastFromCachedData(castLibNumber, data, {});
}

bool Player::loadExternalCastFromCachedData(int castLibNumber,
                                            const std::vector<std::uint8_t>& data,
                                            std::function<void()> afterLoad) {
    if (castLibNumber <= 0 || data.empty()) {
        return false;
    }
    return applyExternalCastDataNow(castLibNumber, data, std::move(afterLoad));
}

bool Player::applyExternalCastDataNow(int castLibNumber,
                                      const std::vector<std::uint8_t>& data,
                                      std::function<void()> afterLoad) {
    if (!castLibManager_.setExternalCastData(castLibNumber, data)) {
        return false;
    }
    onSynchronousExternalCastLoad(castLibNumber);
    if (afterLoad) {
        afterLoad();
    }
    return true;
}

void Player::notifyExternalCastLoaded(int castLibNumber) {
    auto castLib = castLibManager_.getCastLib(castLibNumber);
    if (!castLib) {
        return;
    }

    const std::string fileName = castLib->fileName();
    debug::LifecycleDiagnostics::logExternalCastLoaded(castLibNumber, fileName);
    ExternalCastLoadEvent event{castLibNumber, fileName};
    for (auto* handler : externalCastLoadHandlers_) {
        if (handler != nullptr) {
            handler->onExternalCastLoaded(*this, castLibNumber, fileName);
        }
    }
    if (externalCastLoadListener_) {
        externalCastLoadListener_(event);
    }
    if (castLoadedListener_) {
        castLoadedListener_();
    }
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
    frameContext_.setScriptNameResolver([this](const std::shared_ptr<chunks::ScriptChunk>& script) {
        return file_ != nullptr ? file_->getScriptName(script) : std::string();
    });
    castLibManager_.setMemberSlotRetiredCallback([this](int castLib, int memberNum) {
        (void)stageRenderer_.spriteRegistry().clearDynamicMemberBindings(castLib, memberNum);
    });
    lingo::vm::dispatch::ImageMethodDispatcher::setImageMutationCallback(this, [this] {
        stageRenderer_.spriteRegistry().bumpRevision();
    });

    if (file_ != nullptr && !file_->basePath().empty()) {
        netManager_.setBasePath(file_->basePath());
    }
    netManager_.setCompletionCallback([this](const std::string& url, const std::vector<std::uint8_t>& data) {
        handleExternalCastFetch(url, data);
    });

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

    stageRenderer_.setCastMemberResolver([this](int castLib, int memberNum)
        -> std::shared_ptr<const ::libreshockwave::cast::CastMember> {
        return castLibManager_.resolveMember(castLib, memberNum);
    });

    spriteBaker_.setBitmapDecodeProvider([this](const chunks::CastMemberChunk& member,
                                                const bitmap::Palette* palette) {
        return bitmapResolver_.decodeBitmapForProvider(member, palette);
    });
    spriteBaker_.setLiveBitmapProvider([this](const render::pipeline::RenderSprite& sprite)
        -> std::shared_ptr<const bitmap::Bitmap> {
        if (auto dynamicMember = sprite.dynamicMember()) {
            return dynamicMember->runtimeBitmap();
        }
        if (auto member = sprite.castMember()) {
            if (auto runtimeMember = castLibManager_.findRuntimeMember(
                    std::const_pointer_cast<chunks::CastMemberChunk>(member))) {
                return runtimeMember->runtimeBitmap();
            }
        }
        return nullptr;
    });
    spriteBaker_.setPaletteVersionProvider([this](const render::pipeline::RenderSprite& sprite)
        -> std::optional<int> {
        if (auto member = sprite.castMember()) {
            if (auto runtimeMember = castLibManager_.findRuntimeMember(
                    std::const_pointer_cast<chunks::CastMemberChunk>(member))) {
                return runtimeMember->paletteVersion();
            }
        }
        return std::nullopt;
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
    movieProperties_.setXtraNamesSupplier([this] {
        return xtraManager_.registeredXtraNames();
    });

    inputHandler_.setCurrentFrameSupplier([this] {
        return currentFrame();
    });
    inputHandler_.setEventDispatcherSupplier([this] {
        return &eventDispatcher();
    });

    auto& context = vm_.builtinContext();
    context.movieProperties = &movieProperties_;
    context.netManager = &netManager_;
    context.soundManager = &soundManager_;
    context.spriteProperties = &spriteProperties_;
    context.timeoutManager = &timeoutManager_;
    context.externalParams = externalParams_;
    context.debugPlaybackEnabled = debugEnabled_;
    castLibManager_.installBuiltinCallbacks(context);
    context.xtraRegisteredResolver = [this](const std::string& name) {
        return xtraManager_.isXtraRegistered(name);
    };
    context.xtraInstanceCreator = [this](const std::string& name, const std::vector<lingo::Datum>& args) {
        return xtraManager_.createInstance(name, args);
    };
    context.xtraHandler = [this](const lingo::Datum::XtraInstance& instance,
                                 const std::string& handlerName,
                                 const std::vector<lingo::Datum>& args) {
        return xtraManager_.callHandler(instance, handlerName, args);
    };
    context.xtraPropertyGetter = [this](const lingo::Datum::XtraInstance& instance,
                                        const std::string& propertyName) {
        return xtraManager_.getProperty(instance, propertyName);
    };
    context.xtraPropertySetter = [this](const lingo::Datum::XtraInstance& instance,
                                        const std::string& propertyName,
                                        const lingo::Datum& value) {
        xtraManager_.setProperty(instance, propertyName, value);
    };
    context.spriteMethodHandler = [this](int channel,
                                         const std::string& methodName,
                                         const std::vector<lingo::Datum>& args) {
        return spriteProperties_.callSpriteMethod(channel, methodName, args);
    };
    context.imagePaletteResolver = [this](const lingo::Datum& paletteRef)
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

    auto findEventHandler = [this](const event::EventTarget& target,
                                   std::string_view handlerName)
        -> std::optional<lingo::vm::HandlerRef> {
        const chunks::ScriptChunk* script = target.script.get();
        auto scriptNames = target.scriptNames;

        if (script == nullptr && target.scriptInstance) {
            const auto* scriptRef = target.scriptInstance->type() == lingo::DatumType::ScriptInstanceRef
                ? &target.scriptInstance->scriptInstanceValue().scriptRef()
                : nullptr;
            if (scriptRef != nullptr && scriptRef->has_value()) {
                const int castLib = (*scriptRef)->castLib > 0 ? (*scriptRef)->castLib : 1;
                const int memberNum = (*scriptRef)->memberNum();
                std::optional<ResolvedScriptTarget> resolved;

                if (auto castLibRef = castLibManager_.getCastLib(castLib)) {
                    if (auto resolvedScript = castLibRef->getScript(memberNum)) {
                        const auto source = castLibRef->sourceFile();
                        resolved = ResolvedScriptTarget{
                            resolvedScript,
                            source ? source->getScriptNamesForScript(resolvedScript) : castLibRef->scriptNames()
                        };
                    }
                }

                if (!resolved.has_value() && file_ != nullptr) {
                    if (auto member = file_->getCastMemberByNumber(castLib, memberNum); member && member->isScript()) {
                        if (auto resolvedScript = file_->getScriptForCastMember(member, file_->getMappedCastChunk(castLib))) {
                            resolved = ResolvedScriptTarget{
                                resolvedScript,
                                file_->getScriptNamesForScript(resolvedScript)
                            };
                        }
                    }
                }

                if (resolved.has_value()) {
                    script = resolved->script.get();
                    scriptNames = resolved->scriptNames;
                }
            }
        }

        if (script == nullptr) {
            return std::nullopt;
        }
        if (scriptNames) {
            if (auto handler = script->findHandler(handlerName, scriptNames.get())) {
                return lingo::vm::HandlerRef{script, *handler};
            }
        }
        if (auto handler = vm_.findHandler(*script, handlerName)) {
            return handler;
        }
        return std::nullopt;
    };

    context.scriptHandlerFinder = [findEventHandler](
        int castLib,
        int memberNum,
        const std::string& handlerName)
        -> std::optional<lingo::builtin::BuiltinContext::ScriptHandlerLocation> {
        event::EventTarget target;
        target.kind = event::EventTargetKind::ScriptInstance;
        target.scriptInstance = lingo::Datum::scriptInstance(
            "script",
            lingo::Datum::CastMemberRef{castLib, memberNum});
        const auto handler = findEventHandler(target, handlerName);
        if (!handler || handler->script == nullptr) {
            return std::nullopt;
        }
        return lingo::builtin::BuiltinContext::ScriptHandlerLocation{handler->script, handler->handler};
    };

    auto executeTarget = [this, findEventHandler](const event::EventTarget& target,
                                                  std::string_view handlerName,
                                                  const std::vector<lingo::Datum>& args)
        -> std::optional<std::pair<lingo::Datum, bool>> {
        auto handler = findEventHandler(target, handlerName);
        if (!handler || handler->script == nullptr) {
            return std::nullopt;
        }

        lingo::Datum receiver = lingo::Datum::voidValue();
        if (target.behavior) {
            receiver = target.behavior->toDatum();
        } else if (target.scriptInstance) {
            receiver = *target.scriptInstance;
        }

        bool passed = false;
        vm_.resetEventStopped();
        vm_.setPassCallback([this, &passed] {
            passed = true;
            eventDispatcher().pass();
        });
        lingo::Datum result = lingo::Datum::voidValue();
        try {
            result = vm_.executeHandler(*handler->script, handler->handler, args, receiver);
        } catch (...) {
            vm_.clearPassCallback();
            vm_.resetEventStopped();
            throw;
        }
        vm_.clearPassCallback();
        if (vm_.eventStopped()) {
            eventDispatcher().stopEvent();
            vm_.resetEventStopped();
        }
        return std::make_pair(result, passed);
    };

    auto invokeTarget = [executeTarget](const event::EventTarget& target,
                                        std::string_view handlerName,
                                        const std::vector<lingo::Datum>& args) {
        auto result = executeTarget(target, handlerName, args);
        if (!result.has_value()) {
            return event::HandlerResult{false, false};
        }
        return event::HandlerResult{true, result->second};
    };

    context.callTargetHandler = [this, executeTarget](const lingo::Datum& target,
                                                      const std::string& handlerName,
                                                      const std::vector<lingo::Datum>& args) {
        if (target.type() == lingo::DatumType::ScriptInstanceRef) {
            event::EventTarget eventTarget;
            eventTarget.kind = event::EventTargetKind::ScriptInstance;
            eventTarget.scriptInstance = target;
            const auto result = executeTarget(eventTarget, handlerName, args);
            return result.has_value() ? result->first : lingo::Datum::voidValue();
        }

        int channel = 0;
        if (const auto* sprite = target.asSpriteRef()) {
            channel = sprite->channel;
        } else if (target.isInt() || target.isFloat()) {
            channel = target.intValue();
        }
        if (channel <= 0) {
            return lingo::Datum::voidValue();
        }

        const auto scriptInstances = spriteProperties_.getScriptInstanceList(channel);
        if (!scriptInstances.has_value()) {
            return spriteProperties_.callSpriteMethod(channel, handlerName, args);
        }

        lingo::Datum lastResult = lingo::Datum::voidValue();
        for (const auto& scriptInstance : *scriptInstances) {
            if (scriptInstance.type() != lingo::DatumType::ScriptInstanceRef) {
                continue;
            }
            event::EventTarget eventTarget;
            eventTarget.kind = event::EventTargetKind::ScriptInstance;
            eventTarget.channel = channel;
            eventTarget.scriptInstance = scriptInstance;
            const auto result = executeTarget(eventTarget, handlerName, args);
            if (result.has_value()) {
                lastResult = result->first;
            }
        }
        return lastResult;
    };
    context.alertHookHandler = [this, executeTarget](const std::string& alertType, const std::string& text) {
        const auto& hook = movieProperties_.alertHook();
        if (hook.type() != lingo::DatumType::ScriptInstanceRef) {
            return false;
        }

        event::EventTarget eventTarget;
        eventTarget.kind = event::EventTargetKind::ScriptInstance;
        eventTarget.scriptInstance = hook;
        try {
            const auto result = executeTarget(
                eventTarget,
                "alertHook",
                {lingo::Datum::of(alertType), lingo::Datum::of(text)});
            return result.has_value() && result->first.boolValue();
        } catch (...) {
            return false;
        }
    };

    eventDispatcher().setScriptNamesResolver([this](const std::shared_ptr<chunks::ScriptChunk>& script) {
        return file_ != nullptr ? file_->getScriptNamesForScript(script) : nullptr;
    });
    eventDispatcher().setMovieScriptSupplier([this] {
        std::vector<event::EventDispatcher::MovieScriptTarget> targets;
        if (file_ != nullptr) {
            targets.reserve(file_->scripts().size());
            for (const auto& script : file_->scripts()) {
                if (!script || script->scriptType() != chunks::ScriptChunkType::MovieScript) {
                    continue;
                }
                targets.push_back(event::EventDispatcher::MovieScriptTarget{
                    script,
                    file_->getScriptNamesForScript(script)
                });
            }
        }

        for (const auto& [_, castLib] : castLibManager_.castLibs()) {
            if (!castLib || !castLib->isExternal() || !castLib->isLoaded()) {
                continue;
            }
            const auto source = castLib->sourceFile();
            const auto defaultNames = castLib->scriptNames();
            for (const auto& script : castLib->allScripts()) {
                if (!script || script->scriptType() != chunks::ScriptChunkType::MovieScript) {
                    continue;
                }
                targets.push_back(event::EventDispatcher::MovieScriptTarget{
                    script,
                    source ? source->getScriptNamesForScript(script) : defaultNames
                });
            }
        }
        return targets;
    });
    eventDispatcher().setRespondsPredicate([findEventHandler](const event::EventTarget& target,
                                                              std::string_view handlerName) {
        return findEventHandler(target, handlerName).has_value();
    });
    eventDispatcher().setHandlerInvoker([invokeTarget](const event::EventTarget& target,
                                                       std::string_view handlerName,
                                                       const std::vector<lingo::Datum>& args) {
        return invokeTarget(target, handlerName, args);
    });
    timeoutSystemEventDispatcher_ = [this, invokeTarget](std::string_view handlerName) {
        timeoutManager_.dispatchSystemEvent(handlerName, [invokeTarget](const lingo::Datum& target,
                                                                       std::string_view systemHandler) {
            event::EventTarget eventTarget;
            eventTarget.kind = event::EventTargetKind::ScriptInstance;
            eventTarget.scriptInstance = target;
            (void)invokeTarget(eventTarget, systemHandler, {});
        });
    };
    timeoutProcessor_ = [this, invokeTarget] {
        timeoutManager_.processTimeouts([this, invokeTarget](const timeout::TimeoutEntry& entry) {
            vm_.resetErrorState();
            lingo::Datum resolvedTarget = entry.target;
            if (resolvedTarget.type() != lingo::DatumType::ScriptInstanceRef && !resolvedTarget.isVoid()) {
                const auto resolved = vm_.callHandler("getobject", {resolvedTarget});
                if (resolved.type() == lingo::DatumType::ScriptInstanceRef) {
                    resolvedTarget = resolved;
                }
                vm_.resetErrorState();
            }

            const std::vector<lingo::Datum> args{lingo::Datum::timeoutRef(entry.name)};
            if (resolvedTarget.type() == lingo::DatumType::ScriptInstanceRef) {
                event::EventTarget target;
                target.kind = event::EventTargetKind::ScriptInstance;
                target.scriptInstance = resolvedTarget;
                const auto result = invokeTarget(target, entry.handler, args);
                if (result.handled) {
                    return;
                }
            }

            (void)vm_.callHandler(entry.handler, args);
        });
    };
    frameContext_.setTimeoutEventDispatcher(timeoutSystemEventDispatcher_);
    frameContext_.setActorListDispatcher([this, invokeTarget](std::string_view handlerName) {
        const auto& actorList = movieProperties_.actorList();
        if (!actorList.isList()) {
            return;
        }

        std::vector<lingo::Datum> actors;
        for (const auto& item : actorList.listValue().items()) {
            if (item.type() == lingo::DatumType::ScriptInstanceRef) {
                actors.push_back(item);
            }
        }

        for (const auto& actor : actors) {
            event::EventTarget target;
            target.kind = event::EventTargetKind::ScriptInstance;
            target.scriptInstance = actor;
            (void)invokeTarget(target, handlerName, {actor});
        }
    });
}

void Player::prepareMovieFoundation() {
    applyInitialBuiltinVariables();
    (void)preloadAllCasts();
    castLibManager_.preloadCasts(2);
    if (timeoutSystemEventDispatcher_) {
        timeoutSystemEventDispatcher_("prepareMovie");
    }
    eventDispatcher().dispatchToMovieScripts(PlayerEvent::PrepareMovie);
    castLibManager_.preloadCasts(1);
    frameContext_.initializeFirstFrame();
    frameContext_.dispatchBeginSpriteEvents();
    if (timeoutSystemEventDispatcher_) {
        timeoutSystemEventDispatcher_("prepareFrame");
    }
    eventDispatcher().dispatchGlobalEvent(PlayerEvent::PrepareFrame);
    eventDispatcher().dispatchToMovieScripts(PlayerEvent::StartMovie);
    if (timeoutSystemEventDispatcher_) {
        timeoutSystemEventDispatcher_("startMovie");
    }
    eventDispatcher().dispatchGlobalEvent(PlayerEvent::EnterFrame);
    if (timeoutSystemEventDispatcher_) {
        timeoutSystemEventDispatcher_("exitFrame");
    }
    eventDispatcher().dispatchSpriteAndMovieEvent(handlerName(PlayerEvent::ExitFrame));
    castLibManager_.preloadCasts(1);
}

void Player::applyInitialBuiltinVariables() {
    for (const auto& [name, value] : initialBuiltinVariables_) {
        if (vm_.globals().find(name) == vm_.globals().end()) {
            vm_.setGlobal(name, value.deepCopy());
        }
    }
}

void Player::processUpdatingObjects() {
    if (updatingObjects_.empty() || !vm_.builtinContext().callTargetHandler) {
        return;
    }

    const auto snapshot = updatingObjects_;
    for (const auto& target : snapshot) {
        if (target.type() != lingo::DatumType::ScriptInstanceRef) {
            continue;
        }
        try {
            (void)vm_.builtinContext().callTargetHandler(target, "update", {});
        } catch (...) {
            // Java's update-provider dispatch suppresses per-target handler failures.
        }
    }
}

void Player::loadCastFromNetCache(int castLibNumber, const std::string& fileName) {
    if (fileName.empty()) {
        return;
    }

    if (auto data = netManager_.getCachedData(fileName); data.has_value()) {
        (void)loadExternalCastFromCachedData(castLibNumber, *data);
        return;
    }

    const auto baseName = util::getFileNameWithoutExtension(util::getFileName(fileName));
    if (baseName.empty()) {
        return;
    }
    if (auto cached = castLibManager_.getCachedExternalData(baseName); cached.has_value()) {
        (void)loadExternalCastFromCachedData(castLibNumber, *cached);
    }
}

void Player::handleExternalCastFetch(const std::string& url, const std::vector<std::uint8_t>& data) {
    castLibManager_.cacheExternalData(url, data);
    try {
        auto castNumbers = castLibManager_.getRequestedExternalCastSlots(url);
        for (const int castLibNumber : castLibManager_.getMatchingCastLibNumbersByUrl(url)) {
            if (std::find(castNumbers.begin(), castNumbers.end(), castLibNumber) == castNumbers.end()) {
                castNumbers.push_back(castLibNumber);
            }
        }

        for (const int castLibNumber : castNumbers) {
            (void)loadExternalCastFromCachedData(castLibNumber, data);
        }
    } catch (...) {
    }
}

} // namespace libreshockwave::player
