#pragma once

#include <cstdint>
#include <exception>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/player/audio/QueuedAudioBackend.hpp"
#include "libreshockwave/player/net/QueuedNetProvider.hpp"
#include "libreshockwave/player/web/WasmPlayer.hpp"
#include "libreshockwave/player/xtra/QueuedMultiuserBridge.hpp"

namespace libreshockwave::player::web {

class WasmRuntime final {
public:
    struct GotoNetPageRequest {
        std::string url;
        std::string target;

        friend bool operator==(const GotoNetPageRequest&, const GotoNetPageRequest&) = default;
    };

    WasmRuntime();
    ~WasmRuntime();

    WasmRuntime(const WasmRuntime&) = delete;
    WasmRuntime& operator=(const WasmRuntime&) = delete;
    WasmRuntime(WasmRuntime&&) = delete;
    WasmRuntime& operator=(WasmRuntime&&) = delete;

    [[nodiscard]] int loadMovie(const std::vector<std::uint8_t>& data, std::string basePath = {});
    void shutdown();

    [[nodiscard]] WasmPlayer* player();
    [[nodiscard]] const WasmPlayer* player() const;

    [[nodiscard]] int preloadCasts();
    void play();
    [[nodiscard]] int tick();
    void pause();
    void stop();
    void goToFrame(int frame);
    void stepForward();
    void stepBackward();
    [[nodiscard]] int currentFrame() const;
    [[nodiscard]] int frameCount() const;
    [[nodiscard]] int tempo() const;
    void setPuppetTempo(int tempo);
    [[nodiscard]] int stageWidth() const;
    [[nodiscard]] int stageHeight() const;

    void setExternalParam(std::string key, std::string value);
    void clearExternalParams();
    void mouseMove(int stageX, int stageY);
    void mouseDown(int stageX, int stageY, int button);
    void mouseUp(int stageX, int stageY, int button);
    void blur();
    void keyDown(int browserKeyCode, std::string keyChar, int modifiers);
    void keyUp(int browserKeyCode, std::string keyChar, int modifiers);

    [[nodiscard]] int pendingGotoNetPageCount() const;
    [[nodiscard]] std::optional<GotoNetPageRequest> popNextGotoNetPage();
    [[nodiscard]] int pendingGotoNetMovieCount() const;
    [[nodiscard]] std::optional<std::string> popNextGotoNetMovie();
    void drainGotoNetRequests();

    [[nodiscard]] int pendingFetchCount() const;
    [[nodiscard]] const net::QueuedNetProvider::PendingRequest* pendingFetch(int index) const;
    void drainPendingFetches();
    void deliverFetchResult(int taskId, std::vector<std::uint8_t> data);
    void deliverFetchStatus(int taskId, int byteCount);
    void deliverFetchError(int taskId, int status);

    [[nodiscard]] int pendingJpegDecodeCount() const;
    [[nodiscard]] int pendingJpegDecodeId(int index) const;
    [[nodiscard]] int preparePendingJpegDecodeData(int id);
    [[nodiscard]] const std::vector<std::uint8_t>* currentJpegDecodeData() const;
    void deliverJpegDecodeResult(int id, int width, int height, const std::vector<std::uint8_t>& rgba);

    [[nodiscard]] int audioPendingCount() const;
    [[nodiscard]] const audio::QueuedAudioBackend::SoundCommand* audioPending(int index) const;
    void drainAudioPending();
    void audioNotifyStopped(int channelNum);

    [[nodiscard]] int multiuserPendingCount() const;
    [[nodiscard]] const xtra::QueuedMultiuserBridge::PendingRequest* multiuserPending(int index) const;
    void drainMultiuserPending();
    void multiuserDeliverConnected(int instanceId);
    void multiuserDeliverDisconnected(int instanceId);
    void multiuserDeliverError(int instanceId, int errorCode);
    void multiuserDeliverMessageBytes(int instanceId, const std::vector<std::uint8_t>& data);

    [[nodiscard]] const std::string& lastError() const;
    [[nodiscard]] std::string takeLastError();

private:
    void resetHostQueues();
    void captureError(std::string context, const std::exception& error);
    void captureUnknownError(std::string context);
    [[nodiscard]] net::QueuedNetProvider* netProvider();
    [[nodiscard]] const net::QueuedNetProvider* netProvider() const;
    [[nodiscard]] audio::QueuedAudioBackend* audioBackend();
    [[nodiscard]] const audio::QueuedAudioBackend* audioBackend() const;
    [[nodiscard]] xtra::QueuedMultiuserBridge* multiuserBridge();
    [[nodiscard]] const xtra::QueuedMultiuserBridge* multiuserBridge() const;
    [[nodiscard]] media::QueuedJpegDecoder* jpegDecoder();
    [[nodiscard]] const media::QueuedJpegDecoder* jpegDecoder() const;
    void tryLoadCachedCastData(WasmPlayer& target, int castLibNumber, const std::string& fileName);
    [[nodiscard]] static int packDimensions(int width, int height);

    std::unique_ptr<WasmPlayer> player_;
    std::vector<GotoNetPageRequest> pendingGotoNetPages_;
    std::vector<std::string> pendingGotoNetMovies_;
    std::string lastError_;
};

} // namespace libreshockwave::player::web
