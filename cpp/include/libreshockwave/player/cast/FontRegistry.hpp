#pragma once

#include <memory>
#include <optional>
#include <string>

#include "libreshockwave/font/BitmapFont.hpp"

namespace libreshockwave::player::cast {

class FontRegistry {
public:
    struct FontAlias {
        std::string fontName;
        bool bold = false;
    };

    static void registerBitmapFont(const std::string& fontName,
                                   int fontSize,
                                   std::shared_ptr<font::BitmapFont> font);
    [[nodiscard]] static std::shared_ptr<font::BitmapFont> getBitmapFont(const std::string& fontName,
                                                                         int fontSize);
    [[nodiscard]] static std::shared_ptr<font::BitmapFont> getBitmapFont(const std::string& fontName,
                                                                         int fontSize,
                                                                         bool bold,
                                                                         bool italic);

    static void registerFontAlias(const std::string& alias,
                                  const std::string& fontName,
                                  bool bold);
    [[nodiscard]] static std::optional<FontAlias> getFontAlias(const std::string& fontName);

    [[nodiscard]] static std::string canonicalFontName(const std::string& name);
    [[nodiscard]] static std::optional<std::string> resolveFont(const std::string& fontName);
    [[nodiscard]] static bool hasPfrFont(const std::string& fontName);
    [[nodiscard]] static std::optional<std::string> getFirstRegisteredFont();
    [[nodiscard]] static std::optional<std::string> getPreferredDirectorPixelFont();

    static void clear();
};

} // namespace libreshockwave::player::cast
