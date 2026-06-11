#pragma once

#include <string>

#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::model {

struct ExtractionTask {
    std::string filePath;
    CastMemberInfo memberInfo;

    friend bool operator==(const ExtractionTask&, const ExtractionTask&) = default;
};

} // namespace libreshockwave::editor::model
