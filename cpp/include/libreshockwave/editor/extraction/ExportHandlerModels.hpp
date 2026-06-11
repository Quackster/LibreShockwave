#pragma once

#include <filesystem>
#include <string>
#include <string_view>

#include "libreshockwave/cast/MemberType.hpp"

namespace libreshockwave::editor::extraction {

enum class ExportAssetKind {
    Bitmap,
    Sound,
    Unsupported
};

struct ExportFileChoice {
    ExportAssetKind kind{ExportAssetKind::Unsupported};
    cast::MemberType memberType{cast::MemberType::Unknown};
    std::string safeName;
    std::string defaultFileName;
    std::string extension;
    std::string fileFilterLabel;
    bool supported{false};
    bool soundMp3{false};

    friend bool operator==(const ExportFileChoice&, const ExportFileChoice&) = default;
};

class ExportHandlerModel {
public:
    [[nodiscard]] static std::string safeDefaultName(std::string_view memberName,
                                                     cast::MemberType memberType,
                                                     int memberNumber);
    [[nodiscard]] static ExportFileChoice fileChoice(std::string_view memberName,
                                                     cast::MemberType memberType,
                                                     int memberNumber,
                                                     bool soundMp3 = false);
    [[nodiscard]] static std::filesystem::path withRequiredExtension(const std::filesystem::path& selectedPath,
                                                                     std::string_view extension);

    [[nodiscard]] static bool supports(cast::MemberType memberType);
    [[nodiscard]] static std::string unsupportedStatus(cast::MemberType memberType);
    [[nodiscard]] static std::string bitmapExportedStatus(std::string_view outputFileName);
    [[nodiscard]] static std::string soundExportedStatus(std::string_view outputFileName);
    [[nodiscard]] static std::string bitmapDecodeFailedStatus();
    [[nodiscard]] static std::string soundDataMissingStatus();
    [[nodiscard]] static std::string soundExportFailedStatus();
    [[nodiscard]] static std::string exportErrorStatus(std::string_view message);
};

} // namespace libreshockwave::editor::extraction
