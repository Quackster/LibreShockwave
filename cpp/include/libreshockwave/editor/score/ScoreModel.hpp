#pragma once

#include <optional>
#include <vector>

#include "libreshockwave/editor/score/ScoreColors.hpp"

namespace libreshockwave::editor::score {

class ScoreModel {
public:
    ScoreModel();
    ScoreModel(int frameCount, int channelCount);

    [[nodiscard]] int frameCount() const;
    [[nodiscard]] int channelCount() const;
    [[nodiscard]] std::optional<ScoreColor> cellColor(int channel, int frame) const;
    void setCellColor(int channel, int frame, std::optional<ScoreColor> color);

private:
    int frameCount_{0};
    int channelCount_{0};
    std::vector<std::vector<std::optional<ScoreColor>>> cellColors_;
};

} // namespace libreshockwave::editor::score
