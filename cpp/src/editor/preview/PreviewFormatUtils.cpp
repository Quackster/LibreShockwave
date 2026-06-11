#include "libreshockwave/editor/preview/PreviewFormatUtils.hpp"

#include <cstddef>
#include <iomanip>
#include <sstream>

namespace libreshockwave::editor::preview {

void PreviewFormatUtils::appendMemberHeader(std::string& out,
                                            std::string_view memberKind,
                                            const model::CastMemberInfo& memberInfo,
                                            bool blankLineAfterId) {
    out += "=== ";
    out += memberKind;
    out += ": ";
    out += memberInfo.name;
    out += " ===\n\n";
    out += "Member ID: " + std::to_string(memberInfo.memberNum) + "\n";
    if (blankLineAfterId) {
        out += "\n";
    }
}

void PreviewFormatUtils::appendPaletteInfo(std::string& out, const std::vector<std::uint32_t>& colors) {
    out += "--- Palette Info ---\n";
    out += "Color Count: " + std::to_string(colors.size()) + "\n";
    out += "\n--- Colors ---\n";

    std::ostringstream row;
    for (std::size_t index = 0; index < colors.size(); ++index) {
        const auto color = colors[index];
        const int r = static_cast<int>((color >> 16U) & 0xFFU);
        const int g = static_cast<int>((color >> 8U) & 0xFFU);
        const int b = static_cast<int>(color & 0xFFU);

        row.str("");
        row.clear();
        row << "[" << std::setw(3) << index << "] #"
            << std::uppercase << std::hex << std::setfill('0')
            << std::setw(2) << r
            << std::setw(2) << g
            << std::setw(2) << b
            << std::dec << std::setfill(' ')
            << " (R:" << std::setw(3) << r
            << " G:" << std::setw(3) << g
            << " B:" << std::setw(3) << b
            << ")\n";
        out += row.str();
    }
}

void PreviewFormatUtils::appendScoreAppearances(
    std::string& out,
    const std::vector<model::FrameAppearance>& appearances,
    const score::FrameAppearanceFinder& appearanceFinder,
    bool includePosition) {
    if (appearances.empty()) {
        out += "Not used in score\n";
        return;
    }

    out += appearanceFinder.format(appearances);
    out += "\n";
    if (appearances.size() > 20) {
        return;
    }

    out += "\nDetailed appearances:\n";
    for (const auto& appearance : appearances) {
        if (includePosition) {
            out += "  Frame " + std::to_string(appearance.frame) + ", " + appearance.channelName +
                   " at (" + std::to_string(appearance.posX) + ", " + std::to_string(appearance.posY) + ")";
        } else {
            out += "  Frame " + std::to_string(appearance.frame) + ", " + appearance.channelName;
        }
        if (!appearance.frameLabel.empty()) {
            out += " [" + appearance.frameLabel + "]";
        }
        out += "\n";
    }
}

} // namespace libreshockwave::editor::preview
