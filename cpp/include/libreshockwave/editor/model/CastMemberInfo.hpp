#pragma once

#include <memory>
#include <string>

#include "libreshockwave/cast/MemberType.hpp"

namespace libreshockwave::chunks {
class CastMemberChunk;
}

namespace libreshockwave::editor::model {

struct CastMemberInfo {
    int memberNum{};
    std::string name;
    std::shared_ptr<chunks::CastMemberChunk> member;
    cast::MemberType memberType{cast::MemberType::Unknown};
    std::string details;

    friend bool operator==(const CastMemberInfo&, const CastMemberInfo&) = default;
};

} // namespace libreshockwave::editor::model
