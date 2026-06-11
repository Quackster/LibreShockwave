#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/player/Player.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::player::audio {
class QueuedAudioBackend;
}

namespace libreshockwave::player::media {
class QueuedJpegDecoder;
}

namespace libreshockwave::player::net {
class QueuedNetProvider;
}

namespace libreshockwave::player::xtra {
class QueuedMultiuserBridge;
}

namespace libreshockwave::player::web {

class WasmPlayer final {
public:
    using CastDataRequestCallback = Player::CastDataRequestCallback;
    using ErrorListener = Player::ErrorListener;
    using PageNavigationCallback = std::function<void(const std::string& url, const std::string& target)>;
    using MovieNavigationCallback = std::function<void(const std::string& url)>;

    WasmPlayer();
    ~WasmPlayer();

    WasmPlayer(const WasmPlayer&) = delete;
    WasmPlayer& operator=(const WasmPlayer&) = delete;
    WasmPlayer(WasmPlayer&&) = delete;
    WasmPlayer& operator=(WasmPlayer&&) = delete;

    [[nodiscard]] bool loadMovie(const std::vector<std::uint8_t>& data,
                                 std::string basePath = {},
                                 CastDataRequestCallback castDataRequestCallback = {});
    void shutdown();

    void setGotoNetPageCallback(PageNavigationCallback callback);
    void setGotoNetMovieCallback(MovieNavigationCallback callback);
    void setErrorListener(ErrorListener listener);

    [[nodiscard]] bool tick();
    [[nodiscard]] int preloadCasts();
    void play();
    void pause();
    void stop();
    void goToFrame(int frame);
    void stepFrame();

    [[nodiscard]] int currentFrame() const;
    [[nodiscard]] int frameCount() const;
    [[nodiscard]] int tempo() const;
    void setPuppetTempo(int tempo);
    [[nodiscard]] int stageWidth() const;
    [[nodiscard]] int stageHeight() const;

    [[nodiscard]] std::shared_ptr<DirectorFile> file() const;
    [[nodiscard]] Player* player();
    [[nodiscard]] const Player* player() const;
    [[nodiscard]] net::QueuedNetProvider* netProvider();
    [[nodiscard]] const net::QueuedNetProvider* netProvider() const;
    [[nodiscard]] audio::QueuedAudioBackend* audioBackend();
    [[nodiscard]] const audio::QueuedAudioBackend* audioBackend() const;
    [[nodiscard]] xtra::QueuedMultiuserBridge* multiuserBridge();
    [[nodiscard]] const xtra::QueuedMultiuserBridge* multiuserBridge() const;
    [[nodiscard]] media::QueuedJpegDecoder* jpegDecoder();
    [[nodiscard]] const media::QueuedJpegDecoder* jpegDecoder() const;

    [[nodiscard]] int castRevision() const;
    void bumpCastRevision();

    [[nodiscard]] static std::string toMovieDirectory(std::string_view basePath);

private:
    [[nodiscard]] bool isAlreadyLoadedCastRequest(std::string_view url) const;
    void installPlayerCallbacks();

    std::shared_ptr<DirectorFile> file_;
    std::unique_ptr<Player> player_;
    std::unique_ptr<net::QueuedNetProvider> netProvider_;
    std::unique_ptr<audio::QueuedAudioBackend> audioBackend_;
    std::unique_ptr<xtra::QueuedMultiuserBridge> multiuserBridge_;
    std::unique_ptr<media::QueuedJpegDecoder> jpegDecoder_;
    PageNavigationCallback gotoNetPageCallback_;
    MovieNavigationCallback gotoNetMovieCallback_;
    ErrorListener errorListener_;
    int castRevision_{0};
};

} // namespace libreshockwave::player::web
