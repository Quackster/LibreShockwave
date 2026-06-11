#include "libreshockwave/editor/model/MemberNodeData.hpp"

#include "libreshockwave/cast/MemberType.hpp"

namespace libreshockwave::editor::model {

std::string MemberNodeData::toString() const {
    const std::string idPrefix = "#" + std::to_string(memberInfo.memberNum) + " ";

    if (memberInfo.memberType == cast::MemberType::Script && !memberInfo.details.empty()) {
        return idPrefix + memberInfo.name + " - " + memberInfo.details;
    }

    const std::string base =
        idPrefix + memberInfo.name + " [" + std::string(cast::name(memberInfo.memberType)) + "]";
    if (!memberInfo.details.empty()) {
        return base + " (" + memberInfo.details + ")";
    }
    return base;
}

} // namespace libreshockwave::editor::model
