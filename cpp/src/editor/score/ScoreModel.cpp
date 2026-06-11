#include "libreshockwave/editor/score/ScoreModel.hpp"

namespace libreshockwave::editor::score {

ScoreModel::ScoreModel() = default;

ScoreModel::ScoreModel(int frameCount, int channelCount)
    : frameCount_(frameCount),
      channelCount_(channelCount) {
    if (frameCount_ > 0 && channelCount_ > 0) {
        cellColors_.resize(static_cast<std::size_t>(channelCount_));
        for (auto& row : cellColors_) {
            row.resize(static_cast<std::size_t>(frameCount_));
        }
    }
}

int ScoreModel::frameCount() const {
    return frameCount_;
}

int ScoreModel::channelCount() const {
    return channelCount_;
}

std::optional<ScoreColor> ScoreModel::cellColor(int channel, int frame) const {
    if (cellColors_.empty() || channel < 0 || frame < 0 ||
        channel >= channelCount_ || frame >= frameCount_) {
        return std::nullopt;
    }
    return cellColors_[static_cast<std::size_t>(channel)][static_cast<std::size_t>(frame)];
}

void ScoreModel::setCellColor(int channel, int frame, std::optional<ScoreColor> color) {
    if (cellColors_.empty() || channel < 0 || frame < 0 ||
        channel >= channelCount_ || frame >= frameCount_) {
        return;
    }
    cellColors_[static_cast<std::size_t>(channel)][static_cast<std::size_t>(frame)] = color;
}

} // namespace libreshockwave::editor::score
