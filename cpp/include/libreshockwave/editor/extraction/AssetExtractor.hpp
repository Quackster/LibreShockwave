#pragma once

#include <filesystem>
#include <functional>
#include <string>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/editor/model/CastMemberInfo.hpp"

namespace libreshockwave::editor::extraction {

class AssetExtractor {
public:
    struct ExtractionResult {
        int bitmapsExtracted{};
        int soundsExtracted{};

        friend bool operator==(const ExtractionResult&, const ExtractionResult&) = default;
    };

    using BitmapWriter = std::function<bool(const bitmap::Bitmap&, const std::filesystem::path&)>;

    AssetExtractor() = default;
    explicit AssetExtractor(BitmapWriter bitmapWriter);

    void setBitmapWriter(BitmapWriter bitmapWriter);
    [[nodiscard]] bool extract(DirectorFile& dirFile,
                               const model::CastMemberInfo& memberInfo,
                               const std::filesystem::path& outputDir) const;

    [[nodiscard]] static std::string sanitizeFileName(const std::string& name);
    [[nodiscard]] static std::filesystem::path resolveUnique(const std::filesystem::path& dir,
                                                             const std::string& baseName,
                                                             const std::string& extension);

private:
    [[nodiscard]] static DirectorFile& resolveMemberFile(DirectorFile& fallback,
                                                         const model::CastMemberInfo& memberInfo);
    [[nodiscard]] bool extractBitmap(DirectorFile& dirFile,
                                     const model::CastMemberInfo& memberInfo,
                                     const std::filesystem::path& outputDir,
                                     std::string safeName) const;
    [[nodiscard]] bool extractSound(DirectorFile& dirFile,
                                    const model::CastMemberInfo& memberInfo,
                                    const std::filesystem::path& outputDir,
                                    std::string safeName) const;
    [[nodiscard]] bool extractScript(DirectorFile& dirFile,
                                     const model::CastMemberInfo& memberInfo,
                                     const std::filesystem::path& outputDir,
                                     std::string safeName) const;
    [[nodiscard]] bool extractText(DirectorFile& dirFile,
                                   const model::CastMemberInfo& memberInfo,
                                   const std::filesystem::path& outputDir,
                                   std::string safeName) const;
    [[nodiscard]] bool extractPalette(DirectorFile& dirFile,
                                      const model::CastMemberInfo& memberInfo,
                                      const std::filesystem::path& outputDir,
                                      std::string safeName) const;
    [[nodiscard]] bool extractGeneric(DirectorFile& dirFile,
                                      const model::CastMemberInfo& memberInfo,
                                      const std::filesystem::path& outputDir,
                                      std::string safeName) const;

    BitmapWriter bitmapWriter_;
};

} // namespace libreshockwave::editor::extraction
