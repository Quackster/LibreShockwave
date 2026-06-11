#include "libreshockwave/editor/stage/StageViewModels.hpp"

#include "libreshockwave/player/input/DirectorKeyCodes.hpp"
#include "libreshockwave/util/FileUtil.hpp"

#include <utility>

namespace libreshockwave::editor::stage {
namespace {

StageMouseEvent mouseEvent(StageMouseEvent::Kind kind,
                           const StageViewModel& model,
                           int viewportWidth,
                           int viewportHeight,
                           int panelX,
                           int panelY,
                           bool rightButton) {
    return StageMouseEvent{kind, model.toStagePoint(viewportWidth, viewportHeight, panelX, panelY), rightButton};
}

std::string codePointToUtf8(int keyChar) {
    if (keyChar <= 0 || keyChar > 0x10FFFF) {
        return {};
    }
    if (keyChar <= 0x7F) {
        return std::string(1, static_cast<char>(keyChar));
    }
    if (keyChar <= 0x7FF) {
        return {static_cast<char>(0xC0 | ((keyChar >> 6) & 0x1F)),
                static_cast<char>(0x80 | (keyChar & 0x3F))};
    }
    if (keyChar <= 0xFFFF) {
        return {static_cast<char>(0xE0 | ((keyChar >> 12) & 0x0F)),
                static_cast<char>(0x80 | ((keyChar >> 6) & 0x3F)),
                static_cast<char>(0x80 | (keyChar & 0x3F))};
    }
    return {static_cast<char>(0xF0 | ((keyChar >> 18) & 0x07)),
            static_cast<char>(0x80 | ((keyChar >> 12) & 0x3F)),
            static_cast<char>(0x80 | ((keyChar >> 6) & 0x3F)),
            static_cast<char>(0x80 | (keyChar & 0x3F))};
}

} // namespace

int StageViewModel::stageWidth() const {
    return stageWidth_;
}

int StageViewModel::stageHeight() const {
    return stageHeight_;
}

void StageViewModel::setStageSize(int width, int height) {
    stageWidth_ = width > 0 ? width : DEFAULT_STAGE_WIDTH;
    stageHeight_ = height > 0 ? height : DEFAULT_STAGE_HEIGHT;
}

void StageViewModel::resetStageSize() {
    stageWidth_ = DEFAULT_STAGE_WIDTH;
    stageHeight_ = DEFAULT_STAGE_HEIGHT;
}

StageRect StageViewModel::canvasBounds(int viewportWidth, int viewportHeight) const {
    return StageRect{(viewportWidth - stageWidth_) / 2, (viewportHeight - stageHeight_) / 2, stageWidth_, stageHeight_};
}

StageRect StageViewModel::borderBounds(int viewportWidth, int viewportHeight) const {
    const auto canvas = canvasBounds(viewportWidth, viewportHeight);
    return StageRect{canvas.x - 1, canvas.y - 1, canvas.width + 1, canvas.height + 1};
}

StagePoint StageViewModel::toStagePoint(int viewportWidth, int viewportHeight, int panelX, int panelY) const {
    const auto canvas = canvasBounds(viewportWidth, viewportHeight);
    return StagePoint{panelX - canvas.x, panelY - canvas.y};
}

StageCanvasView StageViewModel::canvasView(int viewportWidth,
                                           int viewportHeight,
                                           bool hasPlayer,
                                           std::optional<std::string> debugInfo) const {
    return StageCanvasView{StageRect{0, 0, viewportWidth, viewportHeight},
                           canvasBounds(viewportWidth, viewportHeight),
                           borderBounds(viewportWidth, viewportHeight),
                           VIEWPORT_COLOR,
                           CANVAS_BORDER_COLOR,
                           NO_MOVIE_CANVAS_COLOR,
                           hasPlayer ? std::nullopt : std::optional<std::string>("No movie loaded"),
                           hasPlayer ? std::move(debugInfo) : std::nullopt};
}

StageMouseEvent StageViewModel::mouseDown(int viewportWidth,
                                          int viewportHeight,
                                          int panelX,
                                          int panelY,
                                          bool rightButton) const {
    return mouseEvent(StageMouseEvent::Kind::Down, *this, viewportWidth, viewportHeight, panelX, panelY, rightButton);
}

StageMouseEvent StageViewModel::mouseUp(int viewportWidth,
                                        int viewportHeight,
                                        int panelX,
                                        int panelY,
                                        bool rightButton) const {
    return mouseEvent(StageMouseEvent::Kind::Up, *this, viewportWidth, viewportHeight, panelX, panelY, rightButton);
}

StageMouseEvent StageViewModel::mouseMove(int viewportWidth, int viewportHeight, int panelX, int panelY) const {
    return mouseEvent(StageMouseEvent::Kind::Move, *this, viewportWidth, viewportHeight, panelX, panelY, false);
}

std::optional<StageKeyEvent> StageViewModel::keyEventFromJava(int javaKeyCode,
                                                              int keyChar,
                                                              bool shift,
                                                              bool ctrl,
                                                              bool alt,
                                                              bool meta) {
    if (ctrl || meta) {
        return std::nullopt;
    }
    return StageKeyEvent{player::input::DirectorKeyCodes::fromJavaKeyCode(javaKeyCode),
                         keyCharToString(keyChar),
                         shift,
                         ctrl,
                         alt};
}

std::string StageViewModel::keyCharToString(int keyChar) {
    if (keyChar == 0 || keyChar == 0xFFFF) {
        return {};
    }
    if (keyChar == '\n' || keyChar == '\r') {
        return "\r";
    }
    if (keyChar == '\t') {
        return "\t";
    }
    if (keyChar == '\b') {
        return "\b";
    }
    return codePointToUtf8(keyChar);
}

std::string StageViewModel::titleForOpenedPath(std::string_view path) {
    const auto fileName = util::getFileName(path);
    return "Stage - " + (fileName.empty() ? std::string("Untitled") : fileName);
}

std::string StageViewModel::closedTitle() {
    return "Stage";
}

} // namespace libreshockwave::editor::stage
