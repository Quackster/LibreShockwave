#include "libreshockwave/editor/score/ScoreViewModels.hpp"

#include <algorithm>

namespace libreshockwave::editor::score {
namespace {

std::optional<ScoreColoredCell> coloredCellFor(const ScoreModel& model, int channel, int frame) {
    const auto color = model.cellColor(channel, frame);
    if (!color.has_value()) {
        return std::nullopt;
    }
    return ScoreColoredCell{channel,
                            frame,
                            ScoreRect{frame * ScoreViewModels::CELL_WIDTH + 1,
                                      ScoreViewModels::HEADER_HEIGHT + channel * ScoreViewModels::CELL_HEIGHT + 1,
                                      ScoreViewModels::CELL_WIDTH - 1,
                                      ScoreViewModels::CELL_HEIGHT - 1},
                            *color};
}

} // namespace

ScorePanelView ScoreViewModels::panelView(const ScoreModel* model, int currentFrame) {
    if (model == nullptr) {
        return ScorePanelView{ScoreSize{DEFAULT_EMPTY_WIDTH, DEFAULT_EMPTY_HEIGHT},
                              "No score data loaded",
                              {},
                              {},
                              {},
                              {},
                              std::nullopt};
    }

    const int totalFrames = model->frameCount();
    const int totalChannels = model->channelCount();
    const int width = totalFrames * CELL_WIDTH;
    const int height = HEADER_HEIGHT + totalChannels * CELL_HEIGHT;

    ScorePanelView view;
    view.preferredSize = ScoreSize{width, height};

    for (int frame = 0; frame <= totalFrames; ++frame) {
        const int x = frame * CELL_WIDTH;
        view.verticalLines.push_back(ScoreLine{x, HEADER_HEIGHT, x, height});
    }
    for (int channel = 0; channel <= totalChannels; ++channel) {
        const int y = HEADER_HEIGHT + channel * CELL_HEIGHT;
        view.horizontalLines.push_back(ScoreLine{0, y, width, y});
    }
    for (int frame = 0; frame < totalFrames; frame += 5) {
        view.frameLabels.push_back(
            ScoreFrameLabel{frame + 1, std::to_string(frame + 1), frame * CELL_WIDTH + 2, HEADER_HEIGHT - 4});
    }
    for (int channel = 0; channel < totalChannels; ++channel) {
        for (int frame = 0; frame < totalFrames; ++frame) {
            if (auto cell = coloredCellFor(*model, channel, frame); cell.has_value()) {
                view.coloredCells.push_back(*cell);
            }
        }
    }

    const int headX = (currentFrame - 1) * CELL_WIDTH;
    view.playbackHead = ScorePlaybackHeadView{currentFrame,
                                              ScoreRect{headX, 0, CELL_WIDTH, height},
                                              ScoreLine{headX, 0, headX, height}};
    return view;
}

ChannelHeaderView ScoreViewModels::channelHeaderView(int spriteChannelCount) {
    const int safeSpriteChannels = std::max(0, spriteChannelCount);
    const auto special = specialChannelNames();
    const int totalChannels = static_cast<int>(special.size()) + safeSpriteChannels;

    ChannelHeaderView view;
    view.preferredSize = ScoreSize{CHANNEL_HEADER_WIDTH, HEADER_HEIGHT + totalChannels * CELL_HEIGHT};
    view.separatorY = HEADER_HEIGHT + static_cast<int>(special.size()) * CELL_HEIGHT;
    view.rows.reserve(static_cast<std::size_t>(totalChannels));

    for (std::size_t index = 0; index < special.size(); ++index) {
        const int channel = static_cast<int>(index);
        view.rows.push_back(ChannelHeaderRow{channel,
                                             special[index],
                                             ScoreRect{0, HEADER_HEIGHT + channel * CELL_HEIGHT,
                                                       CHANNEL_HEADER_WIDTH, CELL_HEIGHT},
                                             4,
                                             HEADER_HEIGHT + channel * CELL_HEIGHT + CELL_HEIGHT - 2,
                                             true});
    }
    for (int index = 0; index < safeSpriteChannels; ++index) {
        const int channel = static_cast<int>(special.size()) + index;
        view.rows.push_back(ChannelHeaderRow{channel,
                                             std::to_string(index + 1),
                                             ScoreRect{0, HEADER_HEIGHT + channel * CELL_HEIGHT,
                                                       CHANNEL_HEADER_WIDTH, CELL_HEIGHT},
                                             4,
                                             HEADER_HEIGHT + channel * CELL_HEIGHT + CELL_HEIGHT - 2,
                                             false});
    }
    return view;
}

MarkersBarView ScoreViewModels::markersBarView(const std::vector<std::pair<int, std::string>>& markers,
                                               int parentWidth) {
    MarkersBarView view;
    view.preferredSize = ScoreSize{parentWidth > 0 ? parentWidth : DEFAULT_EMPTY_WIDTH, MARKERS_BAR_HEIGHT};
    view.markers.reserve(markers.size());
    for (const auto& [frame, label] : markers) {
        const int x = (frame - 1) * CELL_WIDTH;
        view.markers.push_back(MarkerView{frame,
                                          label,
                                          {ScorePoint{x, 0}, ScorePoint{x + 6, 0}, ScorePoint{x, 6}},
                                          x + 8,
                                          MARKERS_BAR_HEIGHT - 4});
    }
    return view;
}

ScoreCellPresentation ScoreViewModels::cellPresentation(const std::optional<model::ScoreCellData>& cell,
                                                        int channel,
                                                        bool selected) {
    if (!cell.has_value()) {
        return ScoreCellPresentation{"", std::nullopt, selected ? std::nullopt : std::optional(EMPTY_BACKGROUND), true};
    }

    std::string tooltip;
    if (channel < 6) {
        switch (channel) {
            case 0:
                tooltip = "<html>Frame Script: " + cell->memberName + "</html>";
                break;
            case 1:
                tooltip = "<html>Palette: " + cell->memberName + "</html>";
                break;
            case 2:
                tooltip = "<html>Transition: #" + std::to_string(cell->castMember) + "</html>";
                break;
            case 3:
            case 4:
                tooltip = "<html>Sound<br>Cast: " + std::to_string(cell->castLib) +
                          ", Member: " + std::to_string(cell->castMember) + "<br>" + cell->memberName + "</html>";
                break;
            case 5:
                tooltip = "<html>Frame Script<br>Cast: " + std::to_string(cell->castLib) +
                          ", Member: " + std::to_string(cell->castMember) + "<br>" + cell->memberName + "</html>";
                break;
            default:
                tooltip = cell->memberName;
                break;
        }
    } else {
        tooltip = "<html>" + cell->memberName + "<br>Cast: " + std::to_string(cell->castLib) +
                  ", Member: " + std::to_string(cell->castMember) + "<br>Type: " +
                  std::to_string(cell->spriteType) + ", Ink: " + std::to_string(cell->ink) + "<br>Pos: (" +
                  std::to_string(cell->posX) + ", " + std::to_string(cell->posY) + ")<br>Size: " +
                  std::to_string(cell->width) + "x" + std::to_string(cell->height) + "</html>";
    }

    const auto background = selected
                                ? std::nullopt
                                : std::optional(channel < 6 ? SPECIAL_CELL_BACKGROUND : SPRITE_CELL_BACKGROUND);
    return ScoreCellPresentation{cell->memberName, tooltip, background, true};
}

std::vector<std::string> ScoreViewModels::specialChannelNames() {
    return {"Tempo", "Palette", "Transition", "Sound 1", "Sound 2", "Script"};
}

ScoreColor ScoreViewModels::colorForScoreCell(int channel, const model::ScoreCellData& cell) {
    switch (channel) {
        case 0:
        case 5:
            return ScoreColors::SCRIPT;
        case 1:
            return ScoreColors::PALETTE;
        case 2:
            return ScoreColors::TRANSITION;
        case 3:
        case 4:
            return ScoreColors::SOUND;
        default:
            switch (cell.spriteType) {
                case 1: return ScoreColors::BITMAP;
                case 2: return ScoreColors::SHAPE;
                case 3: return ScoreColors::TEXT;
                case 4: return ScoreColors::BUTTON;
                case 6: return ScoreColors::FILM_LOOP;
                case 7: return ScoreColors::FIELD;
                default: return ScoreColors::UNKNOWN;
            }
    }
}

std::string ScoreViewModels::loadStatus(int frameCount, int channelCount) {
    return std::to_string(frameCount) + " frames, " + std::to_string(channelCount) + " channels";
}

std::string ScoreViewModels::noScoreStatus() {
    return "Score: No score data";
}

std::string ScoreViewModels::closedStatus() {
    return "Frame: 1 | Channel: -";
}

std::string ScoreViewModels::frameStatus(int frame) {
    return "Frame: " + std::to_string(frame);
}

} // namespace libreshockwave::editor::score
