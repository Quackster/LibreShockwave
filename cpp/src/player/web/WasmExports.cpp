#include "libreshockwave/player/web/WasmExports.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/Player.hpp"
#include "libreshockwave/player/MovieProperties.hpp"

namespace libreshockwave::player::web {
namespace {

constexpr int DEFAULT_STRING_BUFFER_SIZE = 65536;

struct ExportState {
    WasmRuntime runtime;
    std::vector<std::uint8_t> movieBuffer;
    std::vector<std::uint8_t> stringBuffer = std::vector<std::uint8_t>(DEFAULT_STRING_BUFFER_SIZE);
    std::vector<std::uint8_t> netBuffer;
    std::vector<std::uint8_t> audioBuffer;
};

ExportState& state() {
    static ExportState exportState;
    return exportState;
}

std::uintptr_t addressOf(std::vector<std::uint8_t>& data) {
    return data.empty() ? 0 : reinterpret_cast<std::uintptr_t>(data.data());
}

std::uintptr_t addressOf(const std::vector<std::uint8_t>* data) {
    return data == nullptr || data->empty() ? 0 : reinterpret_cast<std::uintptr_t>(data->data());
}

void resizeBuffer(std::vector<std::uint8_t>& buffer, int size) {
    if (size <= 0) {
        buffer.clear();
        return;
    }
    buffer.resize(static_cast<std::size_t>(size));
}

int clampedLength(int requested, std::size_t available) {
    if (requested <= 0 || available == 0) {
        return 0;
    }
    return std::min(requested, static_cast<int>(available));
}

std::string readString(const std::vector<std::uint8_t>& buffer, int offset, int requestedLength) {
    if (offset < 0 || static_cast<std::size_t>(offset) >= buffer.size()) {
        return "";
    }
    const auto start = static_cast<std::size_t>(offset);
    const int len = clampedLength(requestedLength, buffer.size() - start);
    if (len <= 0) {
        return "";
    }
    return std::string(reinterpret_cast<const char*>(buffer.data() + start), static_cast<std::size_t>(len));
}

std::vector<std::uint8_t> readBytes(const std::vector<std::uint8_t>& buffer, int requestedLength) {
    const int len = clampedLength(requestedLength, buffer.size());
    if (len <= 0) {
        return {};
    }
    return std::vector<std::uint8_t>(buffer.begin(), buffer.begin() + len);
}

int writeBytes(std::string_view value) {
    auto& buffer = state().stringBuffer;
    const int len = std::min(static_cast<int>(value.size()), static_cast<int>(buffer.size()));
    if (len > 0) {
        std::memcpy(buffer.data(), value.data(), static_cast<std::size_t>(len));
    }
    return len;
}

int writeBytes(const std::vector<std::uint8_t>& value) {
    auto& buffer = state().stringBuffer;
    const int len = std::min(static_cast<int>(value.size()), static_cast<int>(buffer.size()));
    if (len > 0) {
        std::memcpy(buffer.data(), value.data(), static_cast<std::size_t>(len));
    }
    return len;
}

int packTwoLengths(int high, int low) {
    return ((high & 0xFFFF) << 16) | (low & 0xFFFF);
}

Player* activePlayer() {
    auto* wrapper = state().runtime.player();
    return wrapper != nullptr ? wrapper->player() : nullptr;
}

} // namespace

WasmRuntime& wasmExportRuntime() {
    return state().runtime;
}

} // namespace libreshockwave::player::web

using libreshockwave::lingo::Datum;
using libreshockwave::player::web::state;
using namespace libreshockwave::player::web;

std::uintptr_t libreshockwave_wasm_allocate_buffer(int size) {
    resizeBuffer(state().movieBuffer, size);
    return addressOf(state().movieBuffer);
}

std::uintptr_t libreshockwave_wasm_get_string_buffer_address() {
    return addressOf(state().stringBuffer);
}

int libreshockwave_wasm_get_string_buffer_capacity() {
    return static_cast<int>(state().stringBuffer.size());
}

int libreshockwave_wasm_load_movie(int movieSize, int basePathLen) {
    auto& exportState = state();
    const auto movieData = readBytes(exportState.movieBuffer, movieSize);
    const auto basePath = readString(exportState.stringBuffer, 0, basePathLen);
    return exportState.runtime.loadMovie(movieData, basePath);
}

void libreshockwave_wasm_set_initial_builtin_symbol(int keyLen, int valueLen) {
    auto* player = activePlayer();
    if (player == nullptr || keyLen <= 0 || valueLen <= 0) {
        return;
    }
    const auto key = readString(state().stringBuffer, 0, keyLen);
    const auto value = readString(state().stringBuffer, keyLen, valueLen);
    if (!key.empty() && !value.empty()) {
        player->setInitialBuiltinVariable(key, Datum::symbol(value));
    }
}

void libreshockwave_wasm_set_movie_property(int keyLen, int valueLen) {
    auto* player = activePlayer();
    if (player == nullptr || keyLen <= 0) {
        return;
    }
    const auto key = readString(state().stringBuffer, 0, keyLen);
    const auto value = readString(state().stringBuffer, keyLen, std::max(0, valueLen));
    if (!key.empty()) {
        (void)player->movieProperties().setMovieProp(key, Datum::of(value));
    }
}

int libreshockwave_wasm_read_next_goto_net_page() {
    auto request = state().runtime.popNextGotoNetPage();
    if (!request.has_value()) {
        return 0;
    }

    auto& buffer = state().stringBuffer;
    const int urlLen = std::min(static_cast<int>(request->url.size()), 0xFFFF);
    int targetLen = std::min(static_cast<int>(request->target.size()), 0xFFFF);
    if (urlLen + targetLen > static_cast<int>(buffer.size())) {
        targetLen = std::max(0, static_cast<int>(buffer.size()) - urlLen);
    }
    if (urlLen > 0) {
        std::memcpy(buffer.data(), request->url.data(), static_cast<std::size_t>(urlLen));
    }
    if (targetLen > 0) {
        std::memcpy(buffer.data() + urlLen, request->target.data(), static_cast<std::size_t>(targetLen));
    }
    return packTwoLengths(urlLen, targetLen);
}

int libreshockwave_wasm_read_next_goto_net_movie() {
    auto request = state().runtime.popNextGotoNetMovie();
    return request.has_value() ? writeBytes(*request) : 0;
}

int libreshockwave_wasm_preload_casts() {
    return state().runtime.preloadCasts();
}

void libreshockwave_wasm_play() {
    state().runtime.play();
}

int libreshockwave_wasm_tick() {
    return state().runtime.tick();
}

void libreshockwave_wasm_pause() {
    state().runtime.pause();
}

void libreshockwave_wasm_stop() {
    state().runtime.stop();
}

void libreshockwave_wasm_go_to_frame(int frame) {
    state().runtime.goToFrame(frame);
}

void libreshockwave_wasm_step_forward() {
    state().runtime.stepForward();
}

void libreshockwave_wasm_step_backward() {
    state().runtime.stepBackward();
}

int libreshockwave_wasm_current_frame() {
    return state().runtime.currentFrame();
}

int libreshockwave_wasm_frame_count() {
    return state().runtime.frameCount();
}

int libreshockwave_wasm_tempo() {
    return state().runtime.tempo();
}

void libreshockwave_wasm_set_puppet_tempo(int tempo) {
    state().runtime.setPuppetTempo(tempo);
}

int libreshockwave_wasm_stage_width() {
    return state().runtime.stageWidth();
}

int libreshockwave_wasm_stage_height() {
    return state().runtime.stageHeight();
}

void libreshockwave_wasm_set_external_param(int keyLen, int valueLen) {
    const auto key = readString(state().stringBuffer, 0, keyLen);
    const auto value = readString(state().stringBuffer, keyLen, valueLen);
    state().runtime.setExternalParam(key, value);
}

void libreshockwave_wasm_clear_external_params() {
    state().runtime.clearExternalParams();
}

void libreshockwave_wasm_mouse_move(int stageX, int stageY) {
    state().runtime.mouseMove(stageX, stageY);
}

void libreshockwave_wasm_mouse_down(int stageX, int stageY, int button) {
    state().runtime.mouseDown(stageX, stageY, button);
}

void libreshockwave_wasm_mouse_up(int stageX, int stageY, int button) {
    state().runtime.mouseUp(stageX, stageY, button);
}

void libreshockwave_wasm_blur() {
    state().runtime.blur();
}

void libreshockwave_wasm_key_down(int browserKeyCode, int keyCharLen, int modifiers) {
    state().runtime.keyDown(browserKeyCode, readString(state().stringBuffer, 0, keyCharLen), modifiers);
}

void libreshockwave_wasm_key_up(int browserKeyCode, int keyCharLen, int modifiers) {
    state().runtime.keyUp(browserKeyCode, readString(state().stringBuffer, 0, keyCharLen), modifiers);
}

int libreshockwave_wasm_get_pending_fetch_count() {
    return state().runtime.pendingFetchCount();
}

int libreshockwave_wasm_get_pending_fetch_task_id(int index) {
    const auto* request = state().runtime.pendingFetch(index);
    return request != nullptr ? request->taskId : 0;
}

int libreshockwave_wasm_get_pending_fetch_url(int index) {
    const auto* request = state().runtime.pendingFetch(index);
    return request != nullptr ? writeBytes(request->url) : 0;
}

int libreshockwave_wasm_get_pending_fetch_method(int index) {
    const auto* request = state().runtime.pendingFetch(index);
    return request != nullptr && request->method == "POST" ? 1 : 0;
}

int libreshockwave_wasm_get_pending_fetch_post_data(int index) {
    const auto* request = state().runtime.pendingFetch(index);
    return request != nullptr && request->postData.has_value() ? writeBytes(*request->postData) : 0;
}

int libreshockwave_wasm_get_pending_fetch_fallback_count(int index) {
    const auto* request = state().runtime.pendingFetch(index);
    if (request == nullptr || request->fallbacks.size() <= 1) {
        return 0;
    }
    return static_cast<int>(request->fallbacks.size() - 1);
}

int libreshockwave_wasm_get_pending_fetch_fallback_url(int index, int fallbackIndex) {
    const auto* request = state().runtime.pendingFetch(index);
    if (request == nullptr || fallbackIndex < 0) {
        return 0;
    }
    const auto actualIndex = static_cast<std::size_t>(fallbackIndex + 1);
    if (actualIndex >= request->fallbacks.size()) {
        return 0;
    }
    return writeBytes(request->fallbacks[actualIndex]);
}

void libreshockwave_wasm_drain_pending_fetches() {
    state().runtime.drainPendingFetches();
}

std::uintptr_t libreshockwave_wasm_allocate_net_buffer(int size) {
    resizeBuffer(state().netBuffer, size);
    return addressOf(state().netBuffer);
}

std::uintptr_t libreshockwave_wasm_get_net_buffer_address() {
    return addressOf(state().netBuffer);
}

void libreshockwave_wasm_deliver_fetch_result(int taskId, int dataSize) {
    state().runtime.deliverFetchResult(taskId, readBytes(state().netBuffer, dataSize));
}

void libreshockwave_wasm_deliver_fetch_status(int taskId, int byteCount) {
    state().runtime.deliverFetchStatus(taskId, byteCount);
}

void libreshockwave_wasm_deliver_fetch_error(int taskId, int status) {
    state().runtime.deliverFetchError(taskId, status);
}

int libreshockwave_wasm_get_pending_jpeg_decode_count() {
    return state().runtime.pendingJpegDecodeCount();
}

int libreshockwave_wasm_get_pending_jpeg_decode_id(int index) {
    return state().runtime.pendingJpegDecodeId(index);
}

int libreshockwave_wasm_get_pending_jpeg_decode_data(int id) {
    return state().runtime.preparePendingJpegDecodeData(id);
}

std::uintptr_t libreshockwave_wasm_get_pending_jpeg_decode_data_address() {
    return addressOf(state().runtime.currentJpegDecodeData());
}

void libreshockwave_wasm_deliver_jpeg_decode_result(int id, int width, int height, int dataLen) {
    state().runtime.deliverJpegDecodeResult(id, width, height, readBytes(state().netBuffer, dataLen));
}

int libreshockwave_wasm_get_audio_pending_count() {
    return state().runtime.audioPendingCount();
}

int libreshockwave_wasm_get_audio_pending_action(int index) {
    const auto* command = state().runtime.audioPending(index);
    return command != nullptr ? writeBytes(command->action) : 0;
}

int libreshockwave_wasm_get_audio_pending_channel(int index) {
    const auto* command = state().runtime.audioPending(index);
    return command != nullptr ? command->channelNum : 0;
}

int libreshockwave_wasm_get_audio_pending_format(int index) {
    const auto* command = state().runtime.audioPending(index);
    return command != nullptr && command->format.has_value() ? writeBytes(*command->format) : 0;
}

int libreshockwave_wasm_get_audio_pending_loop_count(int index) {
    const auto* command = state().runtime.audioPending(index);
    return command != nullptr ? command->loopCount : 0;
}

int libreshockwave_wasm_get_audio_pending_volume(int index) {
    const auto* command = state().runtime.audioPending(index);
    return command != nullptr ? command->volume : 0;
}

int libreshockwave_wasm_get_audio_pending_data(int index) {
    const auto* command = state().runtime.audioPending(index);
    if (command == nullptr || !command->audioData.has_value()) {
        return 0;
    }
    state().audioBuffer = *command->audioData;
    return static_cast<int>(state().audioBuffer.size());
}

std::uintptr_t libreshockwave_wasm_get_audio_buffer_address() {
    return addressOf(state().audioBuffer);
}

void libreshockwave_wasm_drain_audio_pending() {
    state().runtime.drainAudioPending();
}

void libreshockwave_wasm_audio_notify_stopped(int channelNum) {
    state().runtime.audioNotifyStopped(channelNum);
}

int libreshockwave_wasm_get_mus_pending_count() {
    return state().runtime.multiuserPendingCount();
}

int libreshockwave_wasm_get_mus_pending_type(int index) {
    const auto* request = state().runtime.multiuserPending(index);
    return request != nullptr ? request->type : -1;
}

int libreshockwave_wasm_get_mus_pending_instance_id(int index) {
    const auto* request = state().runtime.multiuserPending(index);
    return request != nullptr ? request->instanceId : 0;
}

int libreshockwave_wasm_get_mus_pending_host(int index) {
    const auto* request = state().runtime.multiuserPending(index);
    return request != nullptr ? writeBytes(request->host) : 0;
}

int libreshockwave_wasm_get_mus_pending_port(int index) {
    const auto* request = state().runtime.multiuserPending(index);
    return request != nullptr ? request->port : 0;
}

int libreshockwave_wasm_get_mus_pending_send_data(int index) {
    const auto* request = state().runtime.multiuserPending(index);
    return request != nullptr ? writeBytes(request->wireBytes()) : 0;
}

void libreshockwave_wasm_drain_mus_pending() {
    state().runtime.drainMultiuserPending();
}

void libreshockwave_wasm_mus_deliver_connected(int instanceId) {
    state().runtime.multiuserDeliverConnected(instanceId);
}

void libreshockwave_wasm_mus_deliver_disconnected(int instanceId) {
    state().runtime.multiuserDeliverDisconnected(instanceId);
}

void libreshockwave_wasm_mus_deliver_error(int instanceId, int errorCode) {
    state().runtime.multiuserDeliverError(instanceId, errorCode);
}

void libreshockwave_wasm_mus_deliver_message(int instanceId, int dataLen) {
    state().runtime.multiuserDeliverMessageBytes(instanceId, readBytes(state().stringBuffer, dataLen));
}

int libreshockwave_wasm_get_last_error() {
    const auto error = state().runtime.takeLastError();
    return error.empty() ? 0 : writeBytes(error);
}
