#pragma once

#include <array>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "libreshockwave/editor/model/ScoreCellData.hpp"
#include "libreshockwave/editor/score/ScoreColors.hpp"
#include "libreshockwave/editor/score/ScoreModel.hpp"

namespace libreshockwave::editor::score {

struct ScoreSize {
    int width{};
    int height{};

    friend bool operator==(const ScoreSize&, const ScoreSize&) = default;
};

struct ScorePoint {
    int x{};
    int y{};

    friend bool operator==(const ScorePoint&, const ScorePoint&) = default;
};

struct ScoreRect {
    int x{};
    int y{};
    int width{};
    int height{};

    friend bool operator==(const ScoreRect&, const ScoreRect&) = default;
};

struct ScoreLine {
    int x1{};
    int y1{};
    int x2{};
    int y2{};

    friend bool operator==(const ScoreLine&, const ScoreLine&) = default;
};

struct ScoreFrameLabel {
    int frame{};
    std::string text;
    int x{};
    int y{};

    friend bool operator==(const ScoreFrameLabel&, const ScoreFrameLabel&) = default;
};

struct ScoreColoredCell {
    int channel{};
    int frame{};
    ScoreRect bounds;
    ScoreColor color;

    friend bool operator==(const ScoreColoredCell&, const ScoreColoredCell&) = default;
};

struct ScorePlaybackHeadView {
    int frame{};
    ScoreRect overlayBounds;
    ScoreLine leadingLine;

    friend bool operator==(const ScorePlaybackHeadView&, const ScorePlaybackHeadView&) = default;
};

struct ScorePanelView {
    ScoreSize preferredSize;
    std::string emptyMessage;
    std::vector<ScoreLine> verticalLines;
    std::vector<ScoreLine> horizontalLines;
    std::vector<ScoreFrameLabel> frameLabels;
    std::vector<ScoreColoredCell> coloredCells;
    std::optional<ScorePlaybackHeadView> playbackHead;

    friend bool operator==(const ScorePanelView&, const ScorePanelView&) = default;
};

struct ChannelHeaderRow {
    int channelIndex{};
    std::string label;
    ScoreRect bounds;
    int textX{};
    int baselineY{};
    bool special{false};

    friend bool operator==(const ChannelHeaderRow&, const ChannelHeaderRow&) = default;
};

struct ChannelHeaderView {
    ScoreSize preferredSize;
    int separatorY{};
    std::vector<ChannelHeaderRow> rows;

    friend bool operator==(const ChannelHeaderView&, const ChannelHeaderView&) = default;
};

struct MarkerView {
    int frame{};
    std::string label;
    std::array<ScorePoint, 3> triangle;
    int textX{};
    int baselineY{};

    friend bool operator==(const MarkerView&, const MarkerView&) = default;
};

struct MarkersBarView {
    ScoreSize preferredSize;
    std::vector<MarkerView> markers;

    friend bool operator==(const MarkersBarView&, const MarkersBarView&) = default;
};

struct ScoreCellPresentation {
    std::string text;
    std::optional<std::string> tooltipHtml;
    std::optional<ScoreColor> background;
    bool centered{true};

    friend bool operator==(const ScoreCellPresentation&, const ScoreCellPresentation&) = default;
};

class ScoreViewModels {
public:
    static constexpr int CELL_WIDTH = 12;
    static constexpr int CELL_HEIGHT = 14;
    static constexpr int HEADER_HEIGHT = 20;
    static constexpr int CHANNEL_HEADER_WIDTH = 100;
    static constexpr int MARKERS_BAR_HEIGHT = 20;
    static constexpr int DEFAULT_EMPTY_WIDTH = 600;
    static constexpr int DEFAULT_EMPTY_HEIGHT = 200;
    static constexpr int DEFAULT_SPRITE_CHANNEL_COUNT = 48;

    static constexpr ScoreColor EMPTY_BACKGROUND{255, 255, 255};
    static constexpr ScoreColor SPECIAL_CELL_BACKGROUND{255, 255, 220};
    static constexpr ScoreColor SPRITE_CELL_BACKGROUND{220, 240, 255};

    [[nodiscard]] static ScorePanelView panelView(const ScoreModel* model, int currentFrame);
    [[nodiscard]] static ChannelHeaderView channelHeaderView(int spriteChannelCount = DEFAULT_SPRITE_CHANNEL_COUNT);
    [[nodiscard]] static MarkersBarView markersBarView(
        const std::vector<std::pair<int, std::string>>& markers,
        int parentWidth = DEFAULT_EMPTY_WIDTH);
    [[nodiscard]] static ScoreCellPresentation cellPresentation(
        const std::optional<model::ScoreCellData>& cell,
        int channel,
        bool selected = false);

    [[nodiscard]] static std::vector<std::string> specialChannelNames();
    [[nodiscard]] static ScoreColor colorForScoreCell(int channel, const model::ScoreCellData& cell);
    [[nodiscard]] static std::string loadStatus(int frameCount, int channelCount);
    [[nodiscard]] static std::string noScoreStatus();
    [[nodiscard]] static std::string closedStatus();
    [[nodiscard]] static std::string frameStatus(int frame);
};

} // namespace libreshockwave::editor::score
