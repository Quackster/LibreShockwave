#pragma once

#include <optional>
#include <string>
#include <string_view>

namespace libreshockwave::editor::stage {

struct StageColor {
    int r{};
    int g{};
    int b{};
    int a{255};

    friend bool operator==(const StageColor&, const StageColor&) = default;
};

struct StagePoint {
    int x{};
    int y{};

    friend bool operator==(const StagePoint&, const StagePoint&) = default;
};

struct StageRect {
    int x{};
    int y{};
    int width{};
    int height{};

    friend bool operator==(const StageRect&, const StageRect&) = default;
};

struct StageCanvasView {
    StageRect viewportBounds;
    StageRect canvasBounds;
    StageRect borderBounds;
    StageColor viewportColor;
    StageColor borderColor;
    StageColor emptyCanvasColor;
    std::optional<std::string> emptyMessage;
    std::optional<std::string> debugInfo;

    friend bool operator==(const StageCanvasView&, const StageCanvasView&) = default;
};

struct StageMouseEvent {
    enum class Kind {
        Down,
        Up,
        Move
    };

    Kind kind{Kind::Move};
    StagePoint stagePosition;
    bool rightButton{false};

    friend bool operator==(const StageMouseEvent&, const StageMouseEvent&) = default;
};

struct StageKeyEvent {
    int directorKeyCode{};
    std::string keyChar;
    bool shift{false};
    bool ctrl{false};
    bool alt{false};

    friend bool operator==(const StageKeyEvent&, const StageKeyEvent&) = default;
};

class StageViewModel {
public:
    static constexpr int DEFAULT_STAGE_WIDTH = 640;
    static constexpr int DEFAULT_STAGE_HEIGHT = 480;
    static constexpr StageColor VIEWPORT_COLOR{48, 48, 48, 255};
    static constexpr StageColor CANVAS_BORDER_COLOR{80, 80, 80, 255};
    static constexpr StageColor NO_MOVIE_CANVAS_COLOR{192, 192, 192, 255};

    [[nodiscard]] int stageWidth() const;
    [[nodiscard]] int stageHeight() const;
    void setStageSize(int width, int height);
    void resetStageSize();

    [[nodiscard]] StageRect canvasBounds(int viewportWidth, int viewportHeight) const;
    [[nodiscard]] StageRect borderBounds(int viewportWidth, int viewportHeight) const;
    [[nodiscard]] StagePoint toStagePoint(int viewportWidth, int viewportHeight, int panelX, int panelY) const;
    [[nodiscard]] StageCanvasView canvasView(int viewportWidth,
                                             int viewportHeight,
                                             bool hasPlayer,
                                             std::optional<std::string> debugInfo = std::nullopt) const;

    [[nodiscard]] StageMouseEvent mouseDown(int viewportWidth,
                                            int viewportHeight,
                                            int panelX,
                                            int panelY,
                                            bool rightButton) const;
    [[nodiscard]] StageMouseEvent mouseUp(int viewportWidth,
                                          int viewportHeight,
                                          int panelX,
                                          int panelY,
                                          bool rightButton) const;
    [[nodiscard]] StageMouseEvent mouseMove(int viewportWidth,
                                            int viewportHeight,
                                            int panelX,
                                            int panelY) const;

    [[nodiscard]] static std::optional<StageKeyEvent> keyEventFromJava(int javaKeyCode,
                                                                       int keyChar,
                                                                       bool shift,
                                                                       bool ctrl,
                                                                       bool alt,
                                                                       bool meta);
    [[nodiscard]] static std::string keyCharToString(int keyChar);
    [[nodiscard]] static std::string titleForOpenedPath(std::string_view path);
    [[nodiscard]] static std::string closedTitle();

private:
    int stageWidth_{DEFAULT_STAGE_WIDTH};
    int stageHeight_{DEFAULT_STAGE_HEIGHT};
};

} // namespace libreshockwave::editor::stage
