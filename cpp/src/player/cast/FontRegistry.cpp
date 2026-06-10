#include "libreshockwave/player/cast/FontRegistry.hpp"

#include <algorithm>
#include <cctype>
#include <exception>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>

#include "libreshockwave/font/Pfr1TtfConverter.hpp"
#include "libreshockwave/font/TtfBitmapRasterizer.hpp"

namespace libreshockwave::player::cast {
namespace {

struct RegistryState {
    std::unordered_map<std::string, std::shared_ptr<font::Pfr1Font>> parsedFonts;
    std::unordered_map<std::string, std::vector<std::uint8_t>> ttfCache;
    std::unordered_map<std::string, std::shared_ptr<font::BitmapFont>> rasterizedCache;
    std::unordered_map<std::string, std::string> canonicalIndex;
    std::unordered_map<std::string, FontRegistry::FontAlias> aliases;
    std::optional<std::string> firstRegisteredFont;
    std::optional<std::string> firstEmbeddedFont;
    std::mutex mutex;
};

RegistryState& state() {
    static RegistryState registry;
    return registry;
}

std::string lowerAscii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

std::string trimAscii(std::string value) {
    while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back())) != 0) {
        value.pop_back();
    }
    std::size_t first = 0;
    while (first < value.size() && std::isspace(static_cast<unsigned char>(value[first])) != 0) {
        ++first;
    }
    if (first > 0) {
        value.erase(0, first);
    }
    return value;
}

bool isBlank(const std::string& value) {
    return std::all_of(value.begin(), value.end(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    });
}

bool hasTrailingDigitSegment(std::string_view value) {
    if (value.empty() || !std::isdigit(static_cast<unsigned char>(value.back()))) {
        return false;
    }
    std::size_t pos = value.size();
    while (pos > 0 && std::isdigit(static_cast<unsigned char>(value[pos - 1])) != 0) {
        --pos;
    }
    return pos > 0 && std::isspace(static_cast<unsigned char>(value[pos - 1])) != 0;
}

std::string stripTrailingDigitSegments(std::string value) {
    while (hasTrailingDigitSegment(value)) {
        while (!value.empty() && std::isdigit(static_cast<unsigned char>(value.back())) != 0) {
            value.pop_back();
        }
        while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back())) != 0) {
            value.pop_back();
        }
    }
    return value;
}

std::string fontCacheKey(const std::string& fontName, int fontSize, bool bold, bool italic) {
    auto key = lowerAscii(fontName) + ":" + std::to_string(fontSize);
    if (bold || italic) {
        key += ":" + std::string(bold ? "1" : "0") + ":" + (italic ? "1" : "0");
    }
    return key;
}

} // namespace

void FontRegistry::registerBitmapFont(const std::string& fontName,
                                      int fontSize,
                                      std::shared_ptr<font::BitmapFont> font) {
    if (fontName.empty() || font == nullptr) {
        return;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    registry.rasterizedCache[fontCacheKey(fontName, fontSize, false, false)] = std::move(font);
}

void FontRegistry::registerPfr1Font(const std::string& memberName,
                                    const std::vector<std::uint8_t>& pfrData) {
    if (memberName.empty() || pfrData.empty()) {
        return;
    }
    auto parsed = font::Pfr1Font::parse(pfrData);
    if (parsed == nullptr) {
        return;
    }

    std::vector<std::uint8_t> ttfBytes;
    try {
        const auto& ttfName = parsed->fontName.empty() ? memberName : parsed->fontName;
        ttfBytes = font::Pfr1TtfConverter::convert(*parsed, ttfName);
    } catch (const std::exception&) {
        ttfBytes.clear();
    }

    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    const auto key = lowerAscii(memberName);
    registry.parsedFonts[key] = parsed;
    if (!ttfBytes.empty()) {
        registry.ttfCache[key] = ttfBytes;
    }
    if (!registry.firstRegisteredFont.has_value()) {
        registry.firstRegisteredFont = key;
    }
    registry.canonicalIndex[canonicalFontName(memberName)] = key;

    if (!parsed->fontName.empty()) {
        const auto internalKey = lowerAscii(parsed->fontName);
        registry.parsedFonts[internalKey] = parsed;
        if (!ttfBytes.empty()) {
            registry.ttfCache[internalKey] = ttfBytes;
        }
        registry.canonicalIndex[canonicalFontName(parsed->fontName)] = key;
    }
}

std::shared_ptr<font::Pfr1Font> FontRegistry::getPfr1Font(const std::string& fontName) {
    if (fontName.empty()) {
        return nullptr;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    const auto key = lowerAscii(fontName);
    if (const auto it = registry.parsedFonts.find(key); it != registry.parsedFonts.end()) {
        return it->second;
    }
    if (const auto mapped = registry.canonicalIndex.find(canonicalFontName(fontName));
        mapped != registry.canonicalIndex.end()) {
        if (const auto it = registry.parsedFonts.find(mapped->second); it != registry.parsedFonts.end()) {
            return it->second;
        }
    }
    return nullptr;
}

std::optional<std::vector<std::uint8_t>> FontRegistry::getTtfBytes(const std::string& fontName) {
    if (fontName.empty()) {
        return std::nullopt;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    if (const auto it = registry.ttfCache.find(lowerAscii(fontName)); it != registry.ttfCache.end()) {
        return it->second;
    }
    return std::nullopt;
}

std::shared_ptr<font::BitmapFont> FontRegistry::getBitmapFont(const std::string& fontName, int fontSize) {
    return getBitmapFont(fontName, fontSize, false, false);
}

std::shared_ptr<font::BitmapFont> FontRegistry::getBitmapFont(const std::string& fontName,
                                                              int fontSize,
                                                              bool bold,
                                                              bool italic) {
    if (fontName.empty()) {
        return nullptr;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    if (const auto styled = registry.rasterizedCache.find(fontCacheKey(fontName, fontSize, bold, italic));
        styled != registry.rasterizedCache.end()) {
        return styled->second;
    }
    const auto key = lowerAscii(fontName);
    if (!bold && !italic) {
        if (const auto ttf = registry.ttfCache.find(key); ttf != registry.ttfCache.end()) {
            auto rasterized = font::TtfBitmapRasterizer::rasterize(ttf->second, fontSize, fontName);
            if (rasterized != nullptr) {
                registry.rasterizedCache[fontCacheKey(fontName, fontSize, bold, italic)] = rasterized;
                return rasterized;
            }
        }
    }
    if (const auto parsed = registry.parsedFonts.find(key); parsed != registry.parsedFonts.end()) {
        auto rasterized = font::BitmapFont::fromPfr1(*parsed->second, fontSize);
        if (rasterized != nullptr) {
            registry.rasterizedCache[fontCacheKey(fontName, fontSize, bold, italic)] = rasterized;
            return rasterized;
        }
    }
    return nullptr;
}

void FontRegistry::registerFontAlias(const std::string& alias,
                                     const std::string& fontName,
                                     bool bold) {
    if (alias.empty() || isBlank(alias) || fontName.empty() || isBlank(fontName)) {
        return;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    const auto key = lowerAscii(alias);
    registry.aliases[key] = FontAlias{fontName, bold};
    registry.canonicalIndex[canonicalFontName(alias)] = key;
}

std::optional<FontRegistry::FontAlias> FontRegistry::getFontAlias(const std::string& fontName) {
    if (fontName.empty()) {
        return std::nullopt;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    if (const auto it = registry.aliases.find(lowerAscii(fontName)); it != registry.aliases.end()) {
        return it->second;
    }
    return std::nullopt;
}

std::string FontRegistry::canonicalFontName(const std::string& name) {
    if (name.empty()) {
        return "";
    }
    std::string result = lowerAscii(name);
    std::replace(result.begin(), result.end(), '_', ' ');
    std::replace(result.begin(), result.end(), '*', ' ');
    return trimAscii(stripTrailingDigitSegments(trimAscii(std::move(result))));
}

std::optional<std::string> FontRegistry::resolveFont(const std::string& fontName) {
    if (fontName.empty()) {
        return std::nullopt;
    }

    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    const auto key = lowerAscii(fontName);

    if (registry.parsedFonts.contains(key)) {
        return key;
    }

    if (const auto mapped = registry.canonicalIndex.find(canonicalFontName(fontName));
        mapped != registry.canonicalIndex.end()) {
        return mapped->second;
    }

    if (key.size() <= 3) {
        for (const auto& [fontKey, _] : registry.parsedFonts) {
            if (fontKey.starts_with(key)) {
                return fontKey;
            }
        }
    }

    return std::nullopt;
}

bool FontRegistry::hasPfrFont(const std::string& fontName) {
    if (fontName.empty()) {
        return false;
    }
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    const auto key = lowerAscii(fontName);
    return registry.parsedFonts.contains(key);
}

std::optional<std::string> FontRegistry::getFirstRegisteredFont() {
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    return registry.firstRegisteredFont;
}

std::optional<std::string> FontRegistry::getPreferredDirectorPixelFont() {
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    if (registry.firstEmbeddedFont.has_value() && !registry.firstEmbeddedFont->empty()) {
        return registry.firstEmbeddedFont;
    }
    return registry.firstRegisteredFont;
}

void FontRegistry::clear() {
    auto& registry = state();
    std::lock_guard lock(registry.mutex);
    registry.parsedFonts.clear();
    registry.ttfCache.clear();
    registry.rasterizedCache.clear();
    registry.canonicalIndex.clear();
    registry.aliases.clear();
    registry.firstRegisteredFont.reset();
    registry.firstEmbeddedFont.reset();
}

} // namespace libreshockwave::player::cast
