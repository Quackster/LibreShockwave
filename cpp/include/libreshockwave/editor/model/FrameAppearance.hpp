#pragma once

#include <string>

namespace libreshockwave::editor::model {

struct FrameAppearance {
    int frame{};
    int channel{};
    std::string channelName;
    std::string frameLabel;
    int posX{};
    int posY{};

    friend bool operator==(const FrameAppearance&, const FrameAppearance&) = default;
};

} // namespace libreshockwave::editor::model
