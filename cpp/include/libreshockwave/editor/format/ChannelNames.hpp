#pragma once

#include <string>
#include <vector>

namespace libreshockwave::editor::format {

class ChannelNames {
public:
    [[nodiscard]] static std::string get(int channelIndex);
    [[nodiscard]] static std::vector<std::string> createLabels(int channelCount);
};

} // namespace libreshockwave::editor::format
