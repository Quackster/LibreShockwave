#include "libreshockwave/editor/format/ChannelNames.hpp"

namespace libreshockwave::editor::format {

std::string ChannelNames::get(int channelIndex) {
    if (channelIndex < 6) {
        switch (channelIndex) {
            case 0:
                return "Tempo";
            case 1:
                return "Palette";
            case 2:
                return "Transition";
            case 3:
                return "Sound 1";
            case 4:
                return "Sound 2";
            case 5:
                return "Script";
            default:
                return "Ch " + std::to_string(channelIndex + 1);
        }
    }
    return "Ch " + std::to_string(channelIndex - 5);
}

std::vector<std::string> ChannelNames::createLabels(int channelCount) {
    std::vector<std::string> labels;
    if (channelCount <= 0) {
        return labels;
    }
    labels.reserve(static_cast<std::size_t>(channelCount));
    for (int i = 0; i < channelCount; ++i) {
        labels.push_back(get(i));
    }
    return labels;
}

} // namespace libreshockwave::editor::format
