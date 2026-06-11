#include "libreshockwave/editor/extraction/ExportHandlerModels.hpp"

#include <algorithm>
#include <cctype>
#include <string>
#include <utility>

namespace libreshockwave::editor::extraction {
namespace {

std::string lowerAscii(std::string value) {
    for (char& ch : value) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return value;
}

bool filenameEndsWith(const std::filesystem::path& path, std::string_view suffix) {
    const auto filename = lowerAscii(path.filename().string());
    const auto lowerSuffix = lowerAscii(std::string(suffix));
    return filename.size() >= lowerSuffix.size() &&
           filename.compare(filename.size() - lowerSuffix.size(), lowerSuffix.size(), lowerSuffix) == 0;
}

ExportFileChoice unsupportedChoice(std::string safeName, cast::MemberType memberType) {
    return ExportFileChoice{ExportAssetKind::Unsupported,
                            memberType,
                            std::move(safeName),
                            {},
                            {},
                            {},
                            false,
                            false};
}

} // namespace

std::string ExportHandlerModel::safeDefaultName(std::string_view memberName,
                                                cast::MemberType memberType,
                                                int memberNumber) {
    std::string safeName;
    safeName.reserve(memberName.size());
    for (unsigned char ch : memberName) {
        if (std::isalnum(ch) || ch == '.' || ch == '_' || ch == '-') {
            safeName.push_back(static_cast<char>(ch));
        } else {
            safeName.push_back('_');
        }
    }
    if (safeName.empty()) {
        safeName = std::string(cast::name(memberType)) + "_" + std::to_string(memberNumber);
    }
    return safeName;
}

ExportFileChoice ExportHandlerModel::fileChoice(std::string_view memberName,
                                                cast::MemberType memberType,
                                                int memberNumber,
                                                bool soundMp3) {
    auto safeName = safeDefaultName(memberName, memberType, memberNumber);
    if (memberType == cast::MemberType::Bitmap) {
        return ExportFileChoice{ExportAssetKind::Bitmap,
                                memberType,
                                safeName,
                                safeName + ".png",
                                ".png",
                                "PNG Image",
                                true,
                                false};
    }
    if (memberType == cast::MemberType::Sound) {
        const std::string extension = soundMp3 ? ".mp3" : ".wav";
        return ExportFileChoice{ExportAssetKind::Sound,
                                memberType,
                                safeName,
                                safeName + extension,
                                extension,
                                soundMp3 ? "MP3 Audio" : "WAV Audio",
                                true,
                                soundMp3};
    }
    return unsupportedChoice(std::move(safeName), memberType);
}

std::filesystem::path ExportHandlerModel::withRequiredExtension(const std::filesystem::path& selectedPath,
                                                               std::string_view extension) {
    if (filenameEndsWith(selectedPath, extension)) {
        return selectedPath;
    }
    return std::filesystem::path(selectedPath.string() + std::string(extension));
}

bool ExportHandlerModel::supports(cast::MemberType memberType) {
    return memberType == cast::MemberType::Bitmap || memberType == cast::MemberType::Sound;
}

std::string ExportHandlerModel::unsupportedStatus(cast::MemberType memberType) {
    return "Export not supported for " + std::string(cast::name(memberType)) + " members";
}

std::string ExportHandlerModel::bitmapExportedStatus(std::string_view outputFileName) {
    return "Exported bitmap to: " + std::string(outputFileName);
}

std::string ExportHandlerModel::soundExportedStatus(std::string_view outputFileName) {
    return "Exported sound to: " + std::string(outputFileName);
}

std::string ExportHandlerModel::bitmapDecodeFailedStatus() {
    return "Failed to decode bitmap";
}

std::string ExportHandlerModel::soundDataMissingStatus() {
    return "Sound data not found";
}

std::string ExportHandlerModel::soundExportFailedStatus() {
    return "Failed to export sound";
}

std::string ExportHandlerModel::exportErrorStatus(std::string_view message) {
    return "Export error: " + std::string(message);
}

} // namespace libreshockwave::editor::extraction
