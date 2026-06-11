#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/model/CastMemberInfo.hpp"
#include "libreshockwave/editor/model/FrameAppearance.hpp"
#include "libreshockwave/editor/score/FrameAppearanceFinder.hpp"

namespace libreshockwave::editor::preview {

class PreviewFormatUtils {
public:
    PreviewFormatUtils() = delete;

    static void appendMemberHeader(std::string& out,
                                   std::string_view memberKind,
                                   const model::CastMemberInfo& memberInfo,
                                   bool blankLineAfterId);
    static void appendPaletteInfo(std::string& out, const std::vector<std::uint32_t>& colors);
    static void appendScoreAppearances(std::string& out,
                                       const std::vector<model::FrameAppearance>& appearances,
                                       const score::FrameAppearanceFinder& appearanceFinder,
                                       bool includePosition);
};

} // namespace libreshockwave::editor::preview
