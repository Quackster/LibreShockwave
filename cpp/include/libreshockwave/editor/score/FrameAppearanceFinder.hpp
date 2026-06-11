#pragma once

#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/editor/model/FrameAppearance.hpp"

namespace libreshockwave::editor::score {

class FrameAppearanceFinder {
public:
    [[nodiscard]] std::vector<model::FrameAppearance> find(DirectorFile& dirFile, int memberId) const;
    [[nodiscard]] std::string format(const std::vector<model::FrameAppearance>& appearances) const;

private:
    [[nodiscard]] static std::string formatRange(int start, int end, std::string_view channelName);
};

} // namespace libreshockwave::editor::score
