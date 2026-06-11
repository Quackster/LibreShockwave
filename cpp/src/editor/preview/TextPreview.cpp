#include "libreshockwave/editor/preview/TextPreview.hpp"

#include <cstddef>
#include <iomanip>
#include <sstream>
#include <string>

#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/editor/preview/PreviewFormatUtils.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"

namespace libreshockwave::editor::preview {
namespace {

std::string normalizeTextLineEndings(std::string value) {
    std::string normalized;
    normalized.reserve(value.size());
    for (std::size_t index = 0; index < value.size(); ++index) {
        if (value[index] == '\r') {
            normalized.push_back('\n');
            if (index + 1 < value.size() && value[index + 1] == '\n') {
                ++index;
            }
        } else {
            normalized.push_back(value[index]);
        }
    }
    return normalized;
}

} // namespace

std::string TextPreview::format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const {
    std::string out;
    const std::string typeName = memberInfo.memberType == cast::MemberType::Button ? "BUTTON" : "TEXT";
    PreviewFormatUtils::appendMemberHeader(out, typeName, memberInfo, true);

    const auto text = scanning::MemberResolver::findTextForMember(dirFile, memberInfo.member);
    if (text == nullptr) {
        out += "[Text data not found]\n";
        return out;
    }

    out += "--- Text Content ---\n";
    out += normalizeTextLineEndings(text->text());
    out += "\n\n";

    if (!text->runs().empty()) {
        out += "--- Formatting Runs ---\n";
        std::ostringstream row;
        for (const auto& run : text->runs()) {
            row.str("");
            row.clear();
            row << "  Offset " << run.startOffset << ": Font #" << run.fontId << ", Size " << run.fontSize
                << ", Style 0x" << std::uppercase << std::hex << std::setfill('0') << std::setw(2)
                << run.fontStyle << std::dec << std::setfill(' ') << "\n";
            out += row.str();
        }
    }

    return out;
}

} // namespace libreshockwave::editor::preview
