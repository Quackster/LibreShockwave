#include "libreshockwave/player/web/WasmPlayer.hpp"

#include <algorithm>
#include <cctype>
#include <exception>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/player/audio/QueuedAudioBackend.hpp"
#include "libreshockwave/player/media/QueuedJpegDecoder.hpp"
#include "libreshockwave/player/net/QueuedNetProvider.hpp"
#include "libreshockwave/player/xtra/QueuedMultiuserBridge.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::player::web {
namespace {

std::string toLower(std::string_view value) {
    std::string lowered(value);
    std::transform(lowered.begin(), lowered.end(), lowered.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return lowered;
}

bool equalsIgnoreCase(std::string_view lhs, std::string_view rhs) {
    return toLower(lhs) == toLower(rhs);
}

} // namespace

WasmPlayer::WasmPlayer() = default;
WasmPlayer::~WasmPlayer() = default;

bool WasmPlayer::loadMovie(const std::vector<std::uint8_t>& data,
                           std::string basePath,
                           CastDataRequestCallback castDataRequestCallback) {
    std::shared_ptr<DirectorFile> loadedFile;
    try {
        loadedFile = DirectorFile::load(data);
    } catch (const std::exception& error) {
        if (errorListener_) {
            errorListener_("Movie load failed", error.what());
        }
        return false;
    } catch (...) {
        if (errorListener_) {
            errorListener_("Movie load failed", "unknown exception");
        }
        return false;
    }
    if (loadedFile == nullptr) {
        return false;
    }

    loadedFile->setBasePath(toMovieDirectory(basePath));

    auto nextNetProvider = std::make_unique<net::QueuedNetProvider>(basePath);
    auto nextAudioBackend = std::make_unique<audio::QueuedAudioBackend>();
    auto nextMultiuserBridge = std::make_unique<xtra::QueuedMultiuserBridge>();
    auto nextJpegDecoder = std::make_unique<media::QueuedJpegDecoder>();
    auto nextPlayer = std::make_unique<Player>(loadedFile,
                                               nextNetProvider.get(),
                                               std::move(castDataRequestCallback));

    if (player_) {
        player_->shutdown();
    }

    file_ = std::move(loadedFile);
    player_ = std::move(nextPlayer);
    netProvider_ = std::move(nextNetProvider);
    audioBackend_ = std::move(nextAudioBackend);
    multiuserBridge_ = std::move(nextMultiuserBridge);
    jpegDecoder_ = std::move(nextJpegDecoder);
    castRevision_ = 0;

    installPlayerCallbacks();
    return true;
}

void WasmPlayer::shutdown() {
    if (player_) {
        player_->shutdown();
    }
}

void WasmPlayer::setGotoNetPageCallback(PageNavigationCallback callback) {
    gotoNetPageCallback_ = std::move(callback);
    if (player_) {
        installPlayerCallbacks();
    }
}

void WasmPlayer::setGotoNetMovieCallback(MovieNavigationCallback callback) {
    gotoNetMovieCallback_ = std::move(callback);
    if (player_) {
        installPlayerCallbacks();
    }
}

void WasmPlayer::setErrorListener(ErrorListener listener) {
    errorListener_ = std::move(listener);
    if (player_) {
        player_->setErrorListener(errorListener_);
    }
}

bool WasmPlayer::tick() {
    if (player_ == nullptr || player_->state() == PlayerState::Stopped) {
        return false;
    }
    if (player_->state() == PlayerState::Paused) {
        return true;
    }
    (void)player_->tick();
    return true;
}

int WasmPlayer::preloadCasts() {
    return player_ != nullptr ? player_->preloadAllCasts() : 0;
}

void WasmPlayer::play() {
    if (player_) {
        player_->play();
    }
}

void WasmPlayer::pause() {
    if (player_) {
        player_->pause();
    }
}

void WasmPlayer::stop() {
    if (player_) {
        player_->stop();
    }
}

void WasmPlayer::goToFrame(int frame) {
    if (player_) {
        player_->goToFrame(frame);
    }
}

void WasmPlayer::stepFrame() {
    if (player_) {
        player_->stepFrame();
    }
}

void WasmPlayer::setScriptTimeoutMs(int milliseconds) {
    if (player_) {
        player_->vm().setTickDeadlineMs(milliseconds);
    }
}

int WasmPlayer::scriptTimeoutMs() const {
    return player_ != nullptr ? static_cast<int>(player_->vm().tickDeadlineMs()) : 0;
}

int WasmPlayer::currentFrame() const {
    return player_ != nullptr ? player_->currentFrame() : 0;
}

int WasmPlayer::frameCount() const {
    return player_ != nullptr ? player_->frameCount() : 0;
}

int WasmPlayer::tempo() const {
    return player_ != nullptr ? player_->tempo() : 15;
}

void WasmPlayer::setPuppetTempo(int tempo) {
    if (player_) {
        player_->setTempo(tempo);
    }
}

int WasmPlayer::stageWidth() const {
    return player_ != nullptr ? player_->stageRenderer().stageWidth() : 640;
}

int WasmPlayer::stageHeight() const {
    return player_ != nullptr ? player_->stageRenderer().stageHeight() : 480;
}

std::shared_ptr<DirectorFile> WasmPlayer::file() const {
    return file_;
}

Player* WasmPlayer::player() {
    return player_.get();
}

const Player* WasmPlayer::player() const {
    return player_.get();
}

net::QueuedNetProvider* WasmPlayer::netProvider() {
    return netProvider_.get();
}

const net::QueuedNetProvider* WasmPlayer::netProvider() const {
    return netProvider_.get();
}

audio::QueuedAudioBackend* WasmPlayer::audioBackend() {
    return audioBackend_.get();
}

const audio::QueuedAudioBackend* WasmPlayer::audioBackend() const {
    return audioBackend_.get();
}

xtra::QueuedMultiuserBridge* WasmPlayer::multiuserBridge() {
    return multiuserBridge_.get();
}

const xtra::QueuedMultiuserBridge* WasmPlayer::multiuserBridge() const {
    return multiuserBridge_.get();
}

media::QueuedJpegDecoder* WasmPlayer::jpegDecoder() {
    return jpegDecoder_.get();
}

const media::QueuedJpegDecoder* WasmPlayer::jpegDecoder() const {
    return jpegDecoder_.get();
}

int WasmPlayer::castRevision() const {
    return castRevision_;
}

void WasmPlayer::bumpCastRevision() {
    ++castRevision_;
}

std::string WasmPlayer::toMovieDirectory(std::string_view basePath) {
    std::string clean(basePath);
    if (const auto queryStart = clean.find('?'); queryStart != std::string::npos) {
        clean = clean.substr(0, queryStart);
    }
    if (const auto hashStart = clean.find('#'); hashStart != std::string::npos) {
        clean = clean.substr(0, hashStart);
    }

    const auto slash = clean.find_last_of("/\\");
    if (slash == std::string::npos) {
        return clean;
    }
    return clean.substr(0, slash + 1);
}

bool WasmPlayer::isAlreadyLoadedCastRequest(std::string_view url) const {
    if (player_ == nullptr) {
        return false;
    }

    const std::string fileName = util::getFileName(url);
    const std::string baseName = util::getFileNameWithoutExtension(fileName);
    if (baseName.empty()) {
        return false;
    }

    for (const auto& [number, castLib] : player_->castLibManager().castLibs()) {
        (void)number;
        if (castLib == nullptr) {
            continue;
        }

        const bool nameMatches = !castLib->name().empty() && equalsIgnoreCase(castLib->name(), baseName);
        const std::string castFileBaseName =
            util::getFileNameWithoutExtension(util::getFileName(castLib->fileName()));
        const bool fileMatches = !castFileBaseName.empty() && equalsIgnoreCase(castFileBaseName, baseName);
        if (!nameMatches && !fileMatches) {
            continue;
        }

        if (!castLib->isExternal() && castLib->isLoaded()) {
            return true;
        }
        if (castLib->isExternal() && castLib->isFetched()) {
            return true;
        }
    }
    return false;
}

void WasmPlayer::installPlayerCallbacks() {
    if (player_ == nullptr || netProvider_ == nullptr ||
        audioBackend_ == nullptr || multiuserBridge_ == nullptr || jpegDecoder_ == nullptr) {
        return;
    }

    player_->movieProperties().setGotoNetPageHandler([this](const std::string& url, const std::string& target) {
        if (gotoNetPageCallback_) {
            gotoNetPageCallback_(url, target);
        }
    });

    player_->movieProperties().setGotoNetMovieHandler([this](const std::string& url) {
        const int requestId = netProvider_ != nullptr ? netProvider_->beginMovieNavigation(url) : 0;
        if (gotoNetMovieCallback_) {
            gotoNetMovieCallback_(url);
        }
        return requestId;
    });

    netProvider_->setFetchCompleteCallback([this](const std::string& url, const std::vector<std::uint8_t>& data) {
        if (player_) {
            player_->onNetFetchComplete(url, data);
        }
    });
    netProvider_->setSatisfiedFetchPredicate([this](std::string_view url) {
        return isAlreadyLoadedCastRequest(url);
    });

    player_->registerMultiuserXtra(*multiuserBridge_);
    player_->setAudioBackend(audioBackend_.get());
    player_->setErrorListener(errorListener_);
    jpegDecoder_->install();
}

} // namespace libreshockwave::player::web
