#include "libreshockwave/player/web/WasmRuntime.hpp"

#include <algorithm>
#include <exception>
#include <utility>

#include "libreshockwave/player/InputHandler.hpp"
#include "libreshockwave/player/input/DirectorKeyCodes.hpp"
#include "libreshockwave/player/media/QueuedJpegDecoder.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::player::web {
namespace {

constexpr int RIGHT_MOUSE_BUTTON = 2;
constexpr int SHIFT_MODIFIER = 1;
constexpr int CTRL_MODIFIER = 2;
constexpr int ALT_MODIFIER = 4;

} // namespace

WasmRuntime::WasmRuntime() = default;
WasmRuntime::~WasmRuntime() = default;

int WasmRuntime::loadMovie(const std::vector<std::uint8_t>& data, std::string basePath) {
    lastError_.clear();
    try {
        if (player_) {
            if (auto* net = player_->netProvider()) {
                net->completeMovieNavigationTasks();
            }
            player_->shutdown();
        }
        resetHostQueues();

        auto nextPlayer = std::make_unique<WasmPlayer>();
        auto* rawPlayer = nextPlayer.get();
        nextPlayer->setGotoNetPageCallback([this](const std::string& url, const std::string& target) {
            pendingGotoNetPage_ = GotoNetPageRequest{url, target};
        });
        nextPlayer->setGotoNetMovieCallback([this](const std::string& url) {
            pendingGotoNetMovie_.assign(url.begin(), url.end());
            hasPendingGotoNetMovie_ = true;
        });
        nextPlayer->setErrorListener([this](std::string_view message, std::string_view detail) {
            lastError_ = std::string(message);
            if (!detail.empty()) {
                if (!lastError_.empty()) {
                    lastError_ += ": ";
                }
                lastError_ += std::string(detail);
            }
        });

        const bool loaded = nextPlayer->loadMovie(data,
                                                  std::move(basePath),
                                                  [this, rawPlayer](int castLibNumber, const std::string& fileName) {
                                                      tryLoadCachedCastData(*rawPlayer, castLibNumber, fileName);
                                                  });
        player_ = std::move(nextPlayer);
        if (!loaded || player_->player() == nullptr) {
            return 0;
        }
        return packDimensions(player_->stageWidth(), player_->stageHeight());
    } catch (const std::exception& error) {
        player_.reset();
        resetHostQueues();
        captureError("loadMovie", error);
    } catch (...) {
        player_.reset();
        resetHostQueues();
        captureUnknownError("loadMovie");
    }
    return 0;
}

void WasmRuntime::shutdown() {
    if (player_) {
        if (auto* net = player_->netProvider()) {
            net->completeMovieNavigationTasks();
        }
        player_->shutdown();
    }
    resetHostQueues();
}

WasmPlayer* WasmRuntime::player() {
    return player_.get();
}

const WasmPlayer* WasmRuntime::player() const {
    return player_.get();
}

int WasmRuntime::preloadCasts() {
    if (!player_) {
        return 0;
    }
    try {
        lastError_.clear();
        return player_->preloadCasts();
    } catch (const std::exception& error) {
        captureError("preloadCasts", error);
    } catch (...) {
        captureUnknownError("preloadCasts");
    }
    return 0;
}

void WasmRuntime::play() {
    if (!player_) {
        return;
    }
    try {
        lastError_.clear();
        player_->play();
    } catch (const std::exception& error) {
        captureError("play", error);
    } catch (...) {
        captureUnknownError("play");
    }
}

int WasmRuntime::tick() {
    if (!player_) {
        return 0;
    }
    try {
        lastError_.clear();
        return player_->tick() ? 1 : 0;
    } catch (const std::exception& error) {
        captureError("tick", error);
    } catch (...) {
        captureUnknownError("tick");
    }
    return 1;
}

void WasmRuntime::pause() {
    if (player_) {
        player_->pause();
    }
}

void WasmRuntime::stop() {
    if (player_) {
        player_->stop();
    }
}

void WasmRuntime::goToFrame(int frame) {
    if (!player_) {
        return;
    }
    try {
        lastError_.clear();
        player_->goToFrame(frame);
    } catch (const std::exception& error) {
        captureError("goToFrame", error);
    } catch (...) {
        captureUnknownError("goToFrame");
    }
}

void WasmRuntime::stepForward() {
    if (!player_) {
        return;
    }
    try {
        lastError_.clear();
        player_->stepFrame();
    } catch (const std::exception& error) {
        captureError("stepForward", error);
    } catch (...) {
        captureUnknownError("stepForward");
    }
}

void WasmRuntime::stepBackward() {
    if (!player_) {
        return;
    }
    const int frame = player_->currentFrame();
    if (frame > 1) {
        goToFrame(frame - 1);
    }
}

void WasmRuntime::setScriptTimeoutMs(int milliseconds) {
    if (player_) {
        player_->setScriptTimeoutMs(milliseconds);
    }
}

int WasmRuntime::scriptTimeoutMs() const {
    return player_ != nullptr ? player_->scriptTimeoutMs() : 0;
}

int WasmRuntime::currentFrame() const {
    return player_ != nullptr ? player_->currentFrame() : 0;
}

int WasmRuntime::frameCount() const {
    return player_ != nullptr ? player_->frameCount() : 0;
}

int WasmRuntime::tempo() const {
    return player_ != nullptr ? player_->tempo() : 15;
}

void WasmRuntime::setPuppetTempo(int tempo) {
    if (player_) {
        player_->setPuppetTempo(tempo);
    }
}

int WasmRuntime::stageWidth() const {
    return player_ != nullptr ? player_->stageWidth() : 640;
}

int WasmRuntime::stageHeight() const {
    return player_ != nullptr ? player_->stageHeight() : 480;
}

void WasmRuntime::setExternalParam(std::string key, std::string value) {
    if (player_ == nullptr || player_->player() == nullptr) {
        return;
    }

    auto params = player_->player()->externalParams();
    auto found = std::find_if(params.begin(), params.end(), [&key](const auto& entry) {
        return entry.first == key;
    });
    if (found != params.end()) {
        found->second = std::move(value);
    } else {
        params.emplace_back(std::move(key), std::move(value));
    }
    player_->player()->setExternalParams(std::move(params));
}

void WasmRuntime::clearExternalParams() {
    if (player_ != nullptr && player_->player() != nullptr) {
        player_->player()->setExternalParams({});
    }
}

void WasmRuntime::mouseMove(int stageX, int stageY) {
    if (player_ != nullptr && player_->player() != nullptr) {
        player_->player()->inputHandler().onMouseMove(stageX, stageY);
    }
}

void WasmRuntime::mouseDown(int stageX, int stageY, int button) {
    if (player_ != nullptr && player_->player() != nullptr) {
        player_->player()->inputHandler().onMouseDown(stageX, stageY, button == RIGHT_MOUSE_BUTTON);
    }
}

void WasmRuntime::mouseUp(int stageX, int stageY, int button) {
    if (player_ != nullptr && player_->player() != nullptr) {
        player_->player()->inputHandler().onMouseUp(stageX, stageY, button == RIGHT_MOUSE_BUTTON);
    }
}

void WasmRuntime::blur() {
    if (player_ != nullptr && player_->player() != nullptr) {
        player_->player()->inputHandler().onBlur();
    }
}

void WasmRuntime::keyDown(int browserKeyCode, std::string keyChar, int modifiers) {
    if (player_ == nullptr || player_->player() == nullptr) {
        return;
    }
    player_->player()->inputHandler().onKeyDown(input::DirectorKeyCodes::fromBrowserKeyCode(browserKeyCode),
                                                std::move(keyChar),
                                                (modifiers & SHIFT_MODIFIER) != 0,
                                                (modifiers & CTRL_MODIFIER) != 0,
                                                (modifiers & ALT_MODIFIER) != 0);
}

void WasmRuntime::keyUp(int browserKeyCode, std::string keyChar, int modifiers) {
    if (player_ == nullptr || player_->player() == nullptr) {
        return;
    }
    player_->player()->inputHandler().onKeyUp(input::DirectorKeyCodes::fromBrowserKeyCode(browserKeyCode),
                                              std::move(keyChar),
                                              (modifiers & SHIFT_MODIFIER) != 0,
                                              (modifiers & CTRL_MODIFIER) != 0,
                                              (modifiers & ALT_MODIFIER) != 0);
}

int WasmRuntime::pendingGotoNetPageCount() const {
    return pendingGotoNetPage_.has_value() ? 1 : 0;
}

std::optional<WasmRuntime::GotoNetPageRequest> WasmRuntime::popNextGotoNetPage() {
    if (!pendingGotoNetPage_.has_value()) {
        return std::nullopt;
    }
    auto request = std::move(*pendingGotoNetPage_);
    pendingGotoNetPage_.reset();
    return request;
}

int WasmRuntime::pendingGotoNetMovieCount() const {
    return hasPendingGotoNetMovie_ ? 1 : 0;
}

std::optional<std::string> WasmRuntime::popNextGotoNetMovie() {
    if (!hasPendingGotoNetMovie_) {
        return std::nullopt;
    }
    std::string request;
    if (!pendingGotoNetMovie_.empty()) {
        request.assign(reinterpret_cast<const char*>(pendingGotoNetMovie_.data()), pendingGotoNetMovie_.size());
    }
    pendingGotoNetMovie_.clear();
    hasPendingGotoNetMovie_ = false;
    return request;
}

void WasmRuntime::drainGotoNetRequests() {
    resetHostQueues();
}

int WasmRuntime::pendingFetchCount() const {
    const auto* net = netProvider();
    return net != nullptr ? static_cast<int>(net->pendingRequests().size()) : 0;
}

const net::QueuedNetProvider::PendingRequest* WasmRuntime::pendingFetch(int index) const {
    const auto* net = netProvider();
    return net != nullptr ? net->getRequest(index) : nullptr;
}

void WasmRuntime::drainPendingFetches() {
    if (auto* net = netProvider()) {
        net->drainPendingRequests();
    }
}

void WasmRuntime::deliverFetchResult(int taskId, std::vector<std::uint8_t> data) {
    if (auto* net = netProvider()) {
        try {
            lastError_.clear();
            net->onFetchComplete(taskId, std::move(data));
        } catch (const std::exception& error) {
            captureError("deliverFetchResult", error);
        } catch (...) {
            captureUnknownError("deliverFetchResult");
        }
    }
}

void WasmRuntime::deliverFetchStatus(int taskId, int byteCount) {
    if (auto* net = netProvider()) {
        try {
            lastError_.clear();
            net->onFetchStatusComplete(taskId, byteCount);
        } catch (const std::exception& error) {
            captureError("deliverFetchStatus", error);
        } catch (...) {
            captureUnknownError("deliverFetchStatus");
        }
    }
}

void WasmRuntime::deliverFetchError(int taskId, int status) {
    if (auto* net = netProvider()) {
        try {
            lastError_.clear();
            net->onFetchError(taskId, status);
        } catch (const std::exception& error) {
            captureError("deliverFetchError", error);
        } catch (...) {
            captureUnknownError("deliverFetchError");
        }
    }
}

int WasmRuntime::pendingJpegDecodeCount() const {
    const auto* decoder = jpegDecoder();
    return decoder != nullptr ? decoder->pendingCount() : 0;
}

int WasmRuntime::pendingJpegDecodeId(int index) const {
    const auto* decoder = jpegDecoder();
    return decoder != nullptr ? decoder->pendingId(index) : 0;
}

int WasmRuntime::preparePendingJpegDecodeData(int id) {
    auto* decoder = jpegDecoder();
    return decoder != nullptr ? decoder->prepareData(id) : 0;
}

const std::vector<std::uint8_t>* WasmRuntime::currentJpegDecodeData() const {
    const auto* decoder = jpegDecoder();
    return decoder != nullptr ? decoder->currentData() : nullptr;
}

void WasmRuntime::deliverJpegDecodeResult(int id,
                                          int width,
                                          int height,
                                          const std::vector<std::uint8_t>& rgba) {
    if (auto* decoder = jpegDecoder()) {
        decoder->deliverDecoded(id, width, height, rgba);
    }
}

int WasmRuntime::audioPendingCount() const {
    const auto* audio = audioBackend();
    return audio != nullptr ? audio->pendingCount() : 0;
}

const audio::QueuedAudioBackend::SoundCommand* WasmRuntime::audioPending(int index) const {
    const auto* audio = audioBackend();
    return audio != nullptr ? audio->getPending(index) : nullptr;
}

void WasmRuntime::drainAudioPending() {
    if (auto* audio = audioBackend()) {
        audio->drainPending();
    }
}

void WasmRuntime::audioNotifyStopped(int channelNum) {
    if (auto* audio = audioBackend()) {
        audio->notifyStopped(channelNum);
    }
}

int WasmRuntime::multiuserPendingCount() const {
    const auto* bridge = multiuserBridge();
    return bridge != nullptr ? static_cast<int>(bridge->pendingRequests().size()) : 0;
}

const xtra::QueuedMultiuserBridge::PendingRequest* WasmRuntime::multiuserPending(int index) const {
    const auto* bridge = multiuserBridge();
    return bridge != nullptr ? bridge->getRequest(index) : nullptr;
}

void WasmRuntime::drainMultiuserPending() {
    if (auto* bridge = multiuserBridge()) {
        bridge->drainPendingRequests();
    }
}

void WasmRuntime::multiuserDeliverConnected(int instanceId) {
    if (auto* bridge = multiuserBridge()) {
        bridge->notifyConnected(instanceId);
    }
}

void WasmRuntime::multiuserDeliverDisconnected(int instanceId) {
    if (auto* bridge = multiuserBridge()) {
        bridge->notifyDisconnected(instanceId);
    }
}

void WasmRuntime::multiuserDeliverError(int instanceId, int errorCode) {
    if (auto* bridge = multiuserBridge()) {
        bridge->notifyError(instanceId, errorCode);
    }
}

void WasmRuntime::multiuserDeliverMessageBytes(int instanceId, const std::vector<std::uint8_t>& data) {
    if (auto* bridge = multiuserBridge()) {
        bridge->deliverMessageBytes(instanceId, data);
    }
}

const std::string& WasmRuntime::lastError() const {
    return lastError_;
}

std::string WasmRuntime::takeLastError() {
    auto result = std::move(lastError_);
    lastError_.clear();
    return result;
}

void WasmRuntime::resetHostQueues() {
    pendingGotoNetPage_.reset();
    pendingGotoNetMovie_.clear();
    hasPendingGotoNetMovie_ = false;
}

void WasmRuntime::captureError(std::string context, const std::exception& error) {
    lastError_ = "[" + std::move(context) + "] " + error.what();
}

void WasmRuntime::captureUnknownError(std::string context) {
    lastError_ = "[" + std::move(context) + "] unknown exception";
}

net::QueuedNetProvider* WasmRuntime::netProvider() {
    return player_ != nullptr ? player_->netProvider() : nullptr;
}

const net::QueuedNetProvider* WasmRuntime::netProvider() const {
    return player_ != nullptr ? player_->netProvider() : nullptr;
}

audio::QueuedAudioBackend* WasmRuntime::audioBackend() {
    return player_ != nullptr ? player_->audioBackend() : nullptr;
}

const audio::QueuedAudioBackend* WasmRuntime::audioBackend() const {
    return player_ != nullptr ? player_->audioBackend() : nullptr;
}

xtra::QueuedMultiuserBridge* WasmRuntime::multiuserBridge() {
    return player_ != nullptr ? player_->multiuserBridge() : nullptr;
}

const xtra::QueuedMultiuserBridge* WasmRuntime::multiuserBridge() const {
    return player_ != nullptr ? player_->multiuserBridge() : nullptr;
}

media::QueuedJpegDecoder* WasmRuntime::jpegDecoder() {
    return player_ != nullptr ? player_->jpegDecoder() : nullptr;
}

const media::QueuedJpegDecoder* WasmRuntime::jpegDecoder() const {
    return player_ != nullptr ? player_->jpegDecoder() : nullptr;
}

void WasmRuntime::tryLoadCachedCastData(WasmPlayer& target, int castLibNumber, const std::string& fileName) {
    if (target.player() == nullptr) {
        return;
    }

    const std::string baseName = util::getFileNameWithoutExtension(util::getFileName(fileName));
    if (baseName.empty()) {
        return;
    }

    auto cached = target.player()->castLibManager().getCachedExternalData(baseName);
    if (!cached.has_value()) {
        return;
    }

    try {
        (void)target.player()->loadExternalCastFromCachedData(castLibNumber, *cached, [&target] {
            target.bumpCastRevision();
        });
    } catch (const std::exception& error) {
        captureError("castDataRequestCallback", error);
    } catch (...) {
        captureUnknownError("castDataRequestCallback");
    }
}

int WasmRuntime::packDimensions(int width, int height) {
    return ((width & 0xFFFF) << 16) | (height & 0xFFFF);
}

} // namespace libreshockwave::player::web
