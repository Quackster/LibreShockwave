#pragma once

#include <string>

#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::model {

struct MemberNodeData {
    std::string filePath;
    CastMemberInfo memberInfo;

    [[nodiscard]] std::string toString() const;

    friend bool operator==(const MemberNodeData&, const MemberNodeData&) = default;
};

} // namespace libreshockwave::editor::model
