#include "libreshockwave/editor/preview/PalettePreview.hpp"

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/editor/preview/PreviewFormatUtils.hpp"
#include "libreshockwave/editor/scanning/MemberResolver.hpp"

namespace libreshockwave::editor::preview {

std::optional<PalettePreview::PaletteResult> PalettePreview::generateSwatch(
    DirectorFile& dirFile,
    const model::CastMemberInfo& memberInfo) const {
    const auto palette = scanning::MemberResolver::findPaletteForMember(dirFile, memberInfo.member);
    if (palette == nullptr) {
        return std::nullopt;
    }
    return PaletteResult{bitmap::Bitmap::createPaletteSwatch(palette->colors(), 16, 16),
                         static_cast<int>(palette->colors().size())};
}

std::string PalettePreview::format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const {
    std::string out;
    PreviewFormatUtils::appendMemberHeader(out, "PALETTE", memberInfo, true);

    const auto palette = scanning::MemberResolver::findPaletteForMember(dirFile, memberInfo.member);
    if (palette != nullptr) {
        PreviewFormatUtils::appendPaletteInfo(out, palette->colors());
    } else {
        out += "[Palette data not found]\n";
    }
    return out;
}

} // namespace libreshockwave::editor::preview
