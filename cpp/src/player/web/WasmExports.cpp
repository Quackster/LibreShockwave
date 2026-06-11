#include "libreshockwave/player/web/WasmExports.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cctype>
#include <cstring>
#include <exception>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/cast/CastMember.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/vm/DebugConfig.hpp"
#include "libreshockwave/player/Player.hpp"
#include "libreshockwave/player/InputHandler.hpp"
#include "libreshockwave/player/MovieProperties.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"

namespace libreshockwave::player::web {
namespace {

constexpr int DEFAULT_STRING_BUFFER_SIZE = 65536;

struct ExportState {
    WasmRuntime runtime;
    std::vector<std::uint8_t> movieBuffer;
    std::vector<std::uint8_t> stringBuffer = std::vector<std::uint8_t>(DEFAULT_STRING_BUFFER_SIZE);
    std::vector<std::uint8_t> netBuffer;
    std::vector<std::uint8_t> audioBuffer;
    std::vector<std::uint8_t> renderBuffer;
    std::vector<std::uint8_t> cursorBuffer;
    int cursorBitmapWidth{0};
    int cursorBitmapHeight{0};
    int cursorRegX{0};
    int cursorRegY{0};
    std::optional<InputHandler::CaretInfo> caretInfo;
    std::vector<InputHandler::SelectionRect> selectionRects;
    std::string debugLog;
    std::vector<lingo::xtra::MultiuserNetBridge::NetMessage> debugMusMessages;
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

std::string datumDebugString(const lingo::Datum& value) {
    try {
        return value.stringValue();
    } catch (...) {
        return value.typeString();
    }
}

const lingo::xtra::MultiuserNetBridge::NetMessage* debugMusMessageAt(int index) {
    const auto& messages = state().debugMusMessages;
    if (index < 0 || index >= static_cast<int>(messages.size())) {
        return nullptr;
    }
    return &messages[static_cast<std::size_t>(index)];
}

std::string scriptTypeName(chunks::ScriptChunkType type) {
    switch (type) {
        case chunks::ScriptChunkType::Score: return "score";
        case chunks::ScriptChunkType::Behavior: return "behavior";
        case chunks::ScriptChunkType::MovieScript: return "movie";
        case chunks::ScriptChunkType::Parent: return "parent";
        case chunks::ScriptChunkType::Unknown: return "unknown";
    }
    return "unknown";
}

bool equalsIgnoreCase(std::string_view lhs, std::string_view rhs) {
    if (lhs.size() != rhs.size()) {
        return false;
    }
    for (std::size_t index = 0; index < lhs.size(); ++index) {
        if (std::tolower(static_cast<unsigned char>(lhs[index])) !=
            std::tolower(static_cast<unsigned char>(rhs[index]))) {
            return false;
        }
    }
    return true;
}

std::optional<lingo::vm::HandlerRef> findPlayerHandler(Player& player, std::string_view handlerName) {
    if (auto handler = player.vm().findHandler(handlerName)) {
        return handler;
    }

    for (const auto& [_, castLib] : player.castLibManager().castLibs()) {
        if (!castLib || !castLib->isFetched()) {
            continue;
        }
        const auto sourceFile = castLib->sourceFile();
        const auto fallbackNames = castLib->scriptNames();
        for (const auto& script : castLib->allScripts()) {
            if (!script) {
                continue;
            }
            const auto names = sourceFile ? sourceFile->getScriptNamesForScript(script) : fallbackNames;
            if (names) {
                if (auto handler = script->findHandler(handlerName, names.get())) {
                    return lingo::vm::HandlerRef{script.get(), *handler};
                }
            }
            if (auto handler = player.vm().findHandler(*script, handlerName)) {
                return handler;
            }
        }
    }
    return std::nullopt;
}

bool hasNamedHandler(const chunks::ScriptChunk& script,
                     const chunks::ScriptNamesChunk* names,
                     std::string_view handlerName) {
    if (script.findHandler(handlerName, names)) {
        return true;
    }
    if (names != nullptr) {
        return false;
    }
    for (const auto& handler : script.handlers()) {
        if (equalsIgnoreCase(script.getHandlerName(handler, nullptr), handlerName) ||
            equalsIgnoreCase("#" + std::to_string(handler.nameId), handlerName)) {
            return true;
        }
    }
    return false;
}

void appendScriptMatchDiagnostics(std::ostringstream& out,
                                  std::string_view sourceLabel,
                                  DirectorFile* sourceFile,
                                  const std::vector<std::shared_ptr<chunks::ScriptChunk>>& scripts,
                                  const std::shared_ptr<chunks::ScriptNamesChunk>& fallbackNames) {
    constexpr std::array<std::string_view, 4> targets{
        "Logon",
        "sendFuseMsg",
        "DefaultMessageHandler",
        "EPDefaultMessageHandler",
    };

    int movieScripts = 0;
    int matchedScripts = 0;
    for (const auto& script : scripts) {
        if (!script) {
            continue;
        }
        if (script->resolvedScriptType() == chunks::ScriptChunkType::MovieScript) {
            ++movieScripts;
        }

        const auto scriptNames = sourceFile != nullptr ? sourceFile->getScriptNamesForScript(script) : fallbackNames;
        std::vector<std::string_view> matches;
        for (const auto target : targets) {
            if (hasNamedHandler(*script, scriptNames.get(), target)) {
                matches.push_back(target);
            }
        }
        if (matches.empty()) {
            continue;
        }

        ++matchedScripts;
        out << "scriptMatch source=" << sourceLabel
            << " id=" << script->id().value()
            << " type=" << scriptTypeName(script->resolvedScriptType())
            << " name=\"" << script->displayName() << "\" handlers=";
        for (std::size_t index = 0; index < matches.size(); ++index) {
            if (index > 0) {
                out << ',';
            }
            out << matches[index];
        }
        out << '\n';
    }

    out << "scriptSource source=" << sourceLabel
        << " scripts=" << scripts.size()
        << " movieScripts=" << movieScripts
        << " matchedScripts=" << matchedScripts
        << '\n';
}

lingo::Datum callPlayerHandler(Player& player,
                               std::string_view handlerName,
                               const std::vector<lingo::Datum>& args = {}) {
    if (auto handler = findPlayerHandler(player, handlerName)) {
        return player.vm().executeHandler(*handler->script, handler->handler, args);
    }
    return player.vm().callBuiltin(handlerName, args);
}

int packTwoLengths(int high, int low) {
    return ((high & 0xFFFF) << 16) | (low & 0xFFFF);
}

Player* activePlayer() {
    auto* wrapper = state().runtime.player();
    return wrapper != nullptr ? wrapper->player() : nullptr;
}

WasmPlayer* activeWasmPlayer() {
    return state().runtime.player();
}

xtra::QueuedMultiuserBridge* activeMultiuserBridge() {
    auto* wrapper = activeWasmPlayer();
    return wrapper != nullptr ? wrapper->multiuserBridge() : nullptr;
}

std::vector<std::uint8_t> bitmapToRgba(const bitmap::Bitmap& bitmap, bool paletteWhiteTransparent) {
    std::vector<std::uint8_t> rgba;
    rgba.resize(static_cast<std::size_t>(std::max(0, bitmap.width())) *
                static_cast<std::size_t>(std::max(0, bitmap.height())) * 4U);
    const auto& pixels = bitmap.pixels();
    const std::size_t count = std::min(pixels.size(), rgba.size() / 4U);
    for (std::size_t i = 0; i < count; ++i) {
        const std::uint32_t pixel = pixels[i];
        std::uint8_t a = static_cast<std::uint8_t>((pixel >> 24) & 0xFFU);
        std::uint8_t r = static_cast<std::uint8_t>((pixel >> 16) & 0xFFU);
        std::uint8_t g = static_cast<std::uint8_t>((pixel >> 8) & 0xFFU);
        std::uint8_t b = static_cast<std::uint8_t>(pixel & 0xFFU);

        if (paletteWhiteTransparent) {
            if (r == 255 && g == 255 && b == 255) {
                a = 0;
                r = 0;
                g = 0;
                b = 0;
            } else {
                a = 255;
            }
        } else if (a == 0) {
            r = 0;
            g = 0;
            b = 0;
        }

        const std::size_t offset = i * 4U;
        rgba[offset] = r;
        rgba[offset + 1U] = g;
        rgba[offset + 2U] = b;
        rgba[offset + 3U] = a;
    }
    return rgba;
}

std::string spriteDiagnostics(bool textOnly) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return "";
    }

    std::ostringstream out;
    out << "state=" << name(player->state())
        << " frame=" << player->currentFrame()
        << " stage=" << player->stageRenderer().stageWidth() << "x" << player->stageRenderer().stageHeight()
        << " sprites=" << player->stageRenderer().lastBakedSprites().size()
        << '\n';

    for (const auto& sprite : player->stageRenderer().lastBakedSprites()) {
        if (textOnly && sprite.type() != render::pipeline::SpriteType::Text &&
            sprite.type() != render::pipeline::SpriteType::Button) {
            continue;
        }

        out << "ch=" << sprite.channel()
            << " z=" << sprite.locZ()
            << " loc=" << sprite.x() << ',' << sprite.y()
            << ' ' << sprite.width() << 'x' << sprite.height()
            << " type=" << render::pipeline::name(sprite.type())
            << " ink=" << sprite.ink()
            << " blend=" << sprite.blend()
            << " fore=" << sprite.foreColor()
            << " back=" << sprite.backColor()
            << " member=" << sprite.memberName().value_or("")
            << " visible=" << (sprite.isVisible() ? 1 : 0);

        if (const auto& baked = sprite.bakedBitmap()) {
            int transparent = 0;
            int translucent = 0;
            for (const auto pixel : baked->pixels()) {
                const int alpha = static_cast<int>((pixel >> 24) & 0xFFU);
                if (alpha == 0) {
                    ++transparent;
                } else if (alpha < 255) {
                    ++translucent;
                }
            }
            out << " baked=" << baked->width() << 'x' << baked->height()
                << " depth=" << baked->bitDepth()
                << " transparent=" << transparent
                << " translucent=" << translucent;
        }

        if (const auto& castMember = sprite.castMember()) {
            out << " castName=" << castMember->name()
                << " castId=" << castMember->id().value()
                << " castType=" << ::libreshockwave::cast::name(castMember->memberType());
        }

        if (const auto& dynamicMember = sprite.dynamicMember()) {
            out << " dynName=" << dynamicMember->name()
                << " dynNum=" << dynamicMember->memberNum()
                << " dynType=" << ::libreshockwave::cast::name(dynamicMember->memberType());
            const auto text = dynamicMember->textContent();
            if (!text.empty()) {
                out << " text=\"" << text << '"';
            }
        }

        out << '\n';
    }
    return out.str();
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

void libreshockwave_wasm_set_script_timeout_ms(int milliseconds) {
    state().runtime.setScriptTimeoutMs(milliseconds);
}

int libreshockwave_wasm_get_script_timeout_ms() {
    return state().runtime.scriptTimeoutMs();
}

void libreshockwave_wasm_set_debug_playback_enabled(int enabled) {
    ::libreshockwave::lingo::vm::DebugConfig::setDebugPlaybackEnabled(enabled != 0);
    if (auto* player = activePlayer()) {
        player->builtinContext().debugPlaybackEnabled = enabled != 0;
    }
}

void libreshockwave_wasm_add_trace_handler(int nameLen) {
    if (auto* player = activePlayer()) {
        player->vm().addTraceHandler(readString(state().stringBuffer, 0, nameLen));
    }
}

void libreshockwave_wasm_remove_trace_handler(int nameLen) {
    if (auto* player = activePlayer()) {
        player->vm().removeTraceHandler(readString(state().stringBuffer, 0, nameLen));
    }
}

void libreshockwave_wasm_clear_trace_handlers() {
    if (auto* player = activePlayer()) {
        player->vm().clearTraceHandlers();
    }
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

int libreshockwave_wasm_render() {
    auto* player = activePlayer();
    if (player == nullptr) {
        state().renderBuffer.clear();
        return 0;
    }
    try {
        const auto bitmap = player->frameSnapshot().renderFrame();
        state().renderBuffer = bitmapToRgba(bitmap, false);
        return static_cast<int>(state().renderBuffer.size());
    } catch (const std::exception& error) {
        state().renderBuffer.clear();
        state().debugLog += std::string("[render] ") + error.what() + "\n";
    } catch (...) {
        state().renderBuffer.clear();
        state().debugLog += "[render] unknown exception\n";
    }
    return 0;
}

std::uintptr_t libreshockwave_wasm_get_render_buffer_address() {
    return addressOf(state().renderBuffer);
}

int libreshockwave_wasm_get_sprite_count() {
    auto* player = activePlayer();
    return player != nullptr ? static_cast<int>(player->stageRenderer().lastBakedSprites().size()) : 0;
}

int libreshockwave_wasm_get_cursor_type() {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }
    try {
        return player->cursorManager().getCursorAtMouse();
    } catch (...) {
        return 0;
    }
}

int libreshockwave_wasm_update_cursor_bitmap() {
    auto* player = activePlayer();
    if (player == nullptr) {
        state().cursorBuffer.clear();
        state().cursorBitmapWidth = 0;
        state().cursorBitmapHeight = 0;
        state().cursorRegX = 0;
        state().cursorRegY = 0;
        return 0;
    }
    try {
        const auto cursor = player->cursorManager().getCursorBitmap();
        if (!cursor.has_value()) {
            state().cursorBuffer.clear();
            state().cursorBitmapWidth = 0;
            state().cursorBitmapHeight = 0;
            state().cursorRegX = 0;
            state().cursorRegY = 0;
            return 0;
        }
        state().cursorBitmapWidth = cursor->width();
        state().cursorBitmapHeight = cursor->height();
        const auto regPoint = player->cursorManager().getCursorRegPoint();
        state().cursorRegX = regPoint.has_value() ? regPoint->at(0) : 0;
        state().cursorRegY = regPoint.has_value() ? regPoint->at(1) : 0;
        state().cursorBuffer = bitmapToRgba(*cursor, cursor->bitDepth() <= 8);
        return state().cursorBuffer.empty() ? 0 : 1;
    } catch (...) {
        state().cursorBuffer.clear();
        return 0;
    }
}

int libreshockwave_wasm_get_cursor_bitmap_width() {
    return state().cursorBitmapWidth;
}

int libreshockwave_wasm_get_cursor_bitmap_height() {
    return state().cursorBitmapHeight;
}

int libreshockwave_wasm_get_cursor_bitmap_length() {
    return static_cast<int>(state().cursorBuffer.size());
}

std::uintptr_t libreshockwave_wasm_get_cursor_bitmap_address() {
    return addressOf(state().cursorBuffer);
}

int libreshockwave_wasm_get_cursor_reg_point_x() {
    return state().cursorRegX;
}

int libreshockwave_wasm_get_cursor_reg_point_y() {
    return state().cursorRegY;
}

int libreshockwave_wasm_is_caret_visible() {
    auto* player = activePlayer();
    state().caretInfo.reset();
    if (player == nullptr) {
        return 0;
    }
    state().caretInfo = player->inputHandler().getCaretInfo();
    return state().caretInfo.has_value() ? 1 : 0;
}

int libreshockwave_wasm_get_caret_x() {
    return state().caretInfo.has_value() ? state().caretInfo->x : 0;
}

int libreshockwave_wasm_get_caret_y() {
    return state().caretInfo.has_value() ? state().caretInfo->y : 0;
}

int libreshockwave_wasm_get_caret_height() {
    return state().caretInfo.has_value() ? state().caretInfo->height : 0;
}

int libreshockwave_wasm_get_selection_rect_count() {
    auto* player = activePlayer();
    state().selectionRects.clear();
    if (player == nullptr) {
        return 0;
    }
    state().selectionRects = player->inputHandler().getSelectionInfo();
    return static_cast<int>(state().selectionRects.size());
}

const ::libreshockwave::player::InputHandler::SelectionRect* selectionRectAt(int index) {
    if (index < 0 || static_cast<std::size_t>(index) >= state().selectionRects.size()) {
        return nullptr;
    }
    return &state().selectionRects[static_cast<std::size_t>(index)];
}

int libreshockwave_wasm_get_selection_rect_x(int index) {
    const auto* rect = selectionRectAt(index);
    return rect != nullptr ? rect->x : 0;
}

int libreshockwave_wasm_get_selection_rect_y(int index) {
    const auto* rect = selectionRectAt(index);
    return rect != nullptr ? rect->y : 0;
}

int libreshockwave_wasm_get_selection_rect_w(int index) {
    const auto* rect = selectionRectAt(index);
    return rect != nullptr ? rect->width : 0;
}

int libreshockwave_wasm_get_selection_rect_h(int index) {
    const auto* rect = selectionRectAt(index);
    return rect != nullptr ? rect->height : 0;
}

void libreshockwave_wasm_paste_text(int textLen) {
    if (auto* player = activePlayer()) {
        player->inputHandler().onPasteText(readString(state().stringBuffer, 0, textLen));
    }
}

int libreshockwave_wasm_get_selected_text_length() {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }
    const auto text = player->inputHandler().getSelectedText();
    return text.has_value() ? writeBytes(*text) : 0;
}

int libreshockwave_wasm_cut_selected_text() {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }
    const auto text = player->inputHandler().cutSelectedText();
    return text.has_value() ? writeBytes(*text) : 0;
}

void libreshockwave_wasm_select_all() {
    if (auto* player = activePlayer()) {
        player->inputHandler().selectAll();
    }
}

int libreshockwave_wasm_get_debug_log() {
    if (state().debugLog.empty()) {
        return 0;
    }
    const auto log = std::move(state().debugLog);
    state().debugLog.clear();
    return writeBytes(log);
}

int libreshockwave_wasm_get_call_stack() {
    auto* player = activePlayer();
    return player != nullptr ? writeBytes(player->formatLingoCallStack()) : 0;
}

int libreshockwave_wasm_get_window_sprite_diagnostics() {
    return writeBytes(spriteDiagnostics(false));
}

int libreshockwave_wasm_get_visible_text_diagnostics() {
    return writeBytes(spriteDiagnostics(true));
}

int libreshockwave_wasm_get_bootstrap_diagnostics() {
    auto* wrapper = activeWasmPlayer();
    auto* player = activePlayer();
    if (wrapper == nullptr || player == nullptr) {
        return 0;
    }
    std::ostringstream out;
    out << "state=" << name(player->state())
        << " frame=" << player->currentFrame()
        << " frameCount=" << player->frameCount()
        << " stage=" << wrapper->stageWidth() << 'x' << wrapper->stageHeight()
        << " castRevision=" << wrapper->castRevision()
        << " pendingFetches=" << state().runtime.pendingFetchCount()
        << " pendingAudio=" << state().runtime.audioPendingCount()
        << " pendingMus=" << state().runtime.multiuserPendingCount()
        << " pendingJpeg=" << state().runtime.pendingJpegDecodeCount()
        << '\n';
    return writeBytes(out.str());
}

int libreshockwave_wasm_get_script_diagnostics() {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }

    std::ostringstream out;
    out << "handler Logon=" << (findPlayerHandler(*player, "Logon").has_value() ? "yes" : "no")
        << " sendFuseMsg=" << (findPlayerHandler(*player, "sendFuseMsg").has_value() ? "yes" : "no")
        << " DefaultMessageHandler="
        << (findPlayerHandler(*player, "DefaultMessageHandler").has_value() ? "yes" : "no")
        << '\n';

    if (const auto file = player->file()) {
        appendScriptMatchDiagnostics(out, "main", file.get(), file->scripts(), file->scriptNames());
    } else {
        out << "scriptSource source=main scripts=0 movieScripts=0 matchedScripts=0\n";
    }

    for (const auto& [number, castLib] : player->castLibManager().castLibs()) {
        if (!castLib) {
            continue;
        }
        auto sourceFile = castLib->sourceFile();
        out << "castLib number=" << number
            << " name=\"" << castLib->name() << '"'
            << " file=\"" << castLib->fileName() << '"'
            << " external=" << (castLib->isExternal() ? 1 : 0)
            << " fetched=" << (castLib->isFetched() ? 1 : 0)
            << " loaded=" << (castLib->isLoaded() ? 1 : 0)
            << " preload=" << castLib->preloadMode()
            << " sourceFile=" << (sourceFile ? 1 : 0)
            << '\n';
        if (castLib->isFetched()) {
            const auto& scripts = castLib->allScripts();
            appendScriptMatchDiagnostics(out,
                                         "castLib" + std::to_string(number),
                                         sourceFile.get(),
                                         scripts,
                                         castLib->scriptNames());
        }
    }
    return writeBytes(out.str());
}

int libreshockwave_wasm_trigger_test_error() {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }
    try {
        const bool handled = player->fireTestError("Script error: Test error triggered for dialog appearance check");
        state().debugLog += std::string("[triggerTestError] handled=") + (handled ? "1\n" : "0\n");
        return handled ? 1 : 0;
    } catch (const std::exception& error) {
        state().debugLog += std::string("[triggerTestError] ") + error.what() + "\n";
    } catch (...) {
        state().debugLog += "[triggerTestError] unknown exception\n";
    }
    return 0;
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

void libreshockwave_wasm_debug_mus_request_connect(int instanceId, int hostLen, int port, int mode) {
    auto* bridge = activeMultiuserBridge();
    if (bridge == nullptr) {
        return;
    }
    bridge->requestConnect(instanceId, readString(state().stringBuffer, 0, hostLen), port, mode);
}

void libreshockwave_wasm_debug_mus_request_send_text(
    int instanceId, int senderLen, int subjectLen, int contentLen) {
    auto* bridge = activeMultiuserBridge();
    if (bridge == nullptr) {
        return;
    }
    const auto& buffer = state().stringBuffer;
    const auto sender = readString(buffer, 0, senderLen);
    const auto subject = readString(buffer, senderLen, subjectLen);
    const auto content = readString(buffer, senderLen + subjectLen, contentLen);
    bridge->requestSend(instanceId, sender, subject, Datum::of(content));
}

void libreshockwave_wasm_debug_mus_request_disconnect(int instanceId) {
    auto* bridge = activeMultiuserBridge();
    if (bridge != nullptr) {
        bridge->requestDisconnect(instanceId);
    }
}

int libreshockwave_wasm_debug_mus_is_connected(int instanceId) {
    auto* bridge = activeMultiuserBridge();
    return bridge != nullptr && bridge->isConnected(instanceId) ? 1 : 0;
}

void libreshockwave_wasm_debug_mus_destroy_instance(int instanceId) {
    auto* bridge = activeMultiuserBridge();
    if (bridge != nullptr) {
        bridge->destroyInstance(instanceId);
    }
    state().debugMusMessages.clear();
}

int libreshockwave_wasm_debug_mus_poll_message_count(int instanceId) {
    auto* bridge = activeMultiuserBridge();
    state().debugMusMessages = bridge != nullptr ? bridge->pollMessages(instanceId) :
        std::vector<libreshockwave::lingo::xtra::MultiuserNetBridge::NetMessage>{};
    return static_cast<int>(state().debugMusMessages.size());
}

int libreshockwave_wasm_debug_mus_message_error(int index) {
    const auto* message = debugMusMessageAt(index);
    return message != nullptr ? message->errorCode : 0;
}

int libreshockwave_wasm_debug_mus_message_sender(int index) {
    const auto* message = debugMusMessageAt(index);
    return message != nullptr ? writeBytes(message->senderID) : 0;
}

int libreshockwave_wasm_debug_mus_message_subject(int index) {
    const auto* message = debugMusMessageAt(index);
    return message != nullptr ? writeBytes(message->subject) : 0;
}

int libreshockwave_wasm_debug_mus_message_content(int index) {
    const auto* message = debugMusMessageAt(index);
    return message != nullptr ? writeBytes(datumDebugString(message->content)) : 0;
}

void libreshockwave_wasm_debug_set_global_string(int keyLen, int valueLen) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return;
    }
    auto& buffer = state().stringBuffer;
    const auto key = readString(buffer, 0, keyLen);
    const auto value = readString(buffer, keyLen, valueLen);
    player->vm().setGlobal(key, Datum::of(value));
}

void libreshockwave_wasm_debug_set_global_int(int keyLen, int value) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return;
    }
    player->vm().setGlobal(readString(state().stringBuffer, 0, keyLen), Datum::of(value));
}

int libreshockwave_wasm_debug_call_handler(int nameLen) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return -1;
    }
    try {
        const auto result = callPlayerHandler(*player, readString(state().stringBuffer, 0, nameLen));
        return result.isVoid() ? 0 : 1;
    } catch (const std::exception& ex) {
        player->vm().fireTraceError("Debug handler call failed", ex.what());
        return -2;
    } catch (...) {
        player->vm().fireTraceError("Debug handler call failed", "unknown exception");
        return -2;
    }
}

int libreshockwave_wasm_debug_call_handler_string_arg(int nameLen, int argLen) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return -1;
    }
    try {
        auto& buffer = state().stringBuffer;
        const auto name = readString(buffer, 0, nameLen);
        const auto arg = readString(buffer, nameLen, argLen);
        const auto result = callPlayerHandler(*player, name, {Datum::of(arg)});
        return result.isVoid() ? 0 : 1;
    } catch (const std::exception& ex) {
        player->vm().fireTraceError("Debug handler call failed", ex.what());
        return -2;
    } catch (...) {
        player->vm().fireTraceError("Debug handler call failed", "unknown exception");
        return -2;
    }
}

int libreshockwave_wasm_debug_get_global_string(int keyLen) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }
    return writeBytes(datumDebugString(player->vm().getGlobal(readString(state().stringBuffer, 0, keyLen))));
}

int libreshockwave_wasm_debug_get_global_int(int keyLen) {
    auto* player = activePlayer();
    if (player == nullptr) {
        return 0;
    }
    return player->vm().getGlobal(readString(state().stringBuffer, 0, keyLen)).intValue();
}

int libreshockwave_wasm_get_last_error() {
    const auto error = state().runtime.takeLastError();
    return error.empty() ? 0 : writeBytes(error);
}
