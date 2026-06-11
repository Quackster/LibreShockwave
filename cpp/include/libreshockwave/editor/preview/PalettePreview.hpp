#pragma once

#include <optional>
#include <string>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::preview {

class PalettePreview {
public:
    struct PaletteResult {
        bitmap::Bitmap swatchImage;
        int colorCount;
    };

    [[nodiscard]] std::optional<PaletteResult> generateSwatch(DirectorFile& dirFile,
                                                              const model::CastMemberInfo& memberInfo) const;
    [[nodiscard]] std::string format(DirectorFile& dirFile, const model::CastMemberInfo& memberInfo) const;
};

} // namespace libreshockwave::editor::preview
