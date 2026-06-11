#pragma once

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/script/LingoTokenizer.hpp"

namespace libreshockwave::editor::script {

struct ScriptStyleColor {
    int r{};
    int g{};
    int b{};

    friend bool operator==(const ScriptStyleColor&, const ScriptStyleColor&) = default;
};

struct ScriptStyle {
    ScriptStyleColor foreground;
    bool bold{false};

    friend bool operator==(const ScriptStyle&, const ScriptStyle&) = default;
};

struct ScriptStyleRange {
    int start{};
    int end{};
    LingoTokenType tokenType{LingoTokenType::Identifier};
    ScriptStyle style;

    friend bool operator==(const ScriptStyleRange&, const ScriptStyleRange&) = default;
};

class LingoSyntaxHighlighterModel {
public:
    static constexpr ScriptStyleColor COLOR_KEYWORD{0, 0, 192};
    static constexpr ScriptStyleColor COLOR_COMMAND{0, 0, 192};
    static constexpr ScriptStyleColor COLOR_FUNCTION{0, 128, 0};
    static constexpr ScriptStyleColor COLOR_EVENT{128, 0, 128};
    static constexpr ScriptStyleColor COLOR_STRING{128, 0, 0};
    static constexpr ScriptStyleColor COLOR_COMMENT{128, 128, 128};
    static constexpr ScriptStyleColor COLOR_NUMBER{255, 0, 0};
    static constexpr ScriptStyleColor COLOR_SYMBOL{0, 128, 128};
    static constexpr ScriptStyleColor COLOR_DEFAULT{0, 0, 0};

    [[nodiscard]] static ScriptStyle defaultStyle();
    [[nodiscard]] static bool hasExplicitStyle(LingoTokenType type);
    [[nodiscard]] static ScriptStyle styleForToken(LingoTokenType type);
    [[nodiscard]] static std::vector<ScriptStyleRange> highlight(std::string_view text);
};

class HandlerDropdownModel {
public:
    HandlerDropdownModel();

    [[nodiscard]] static std::string_view noHandlersLabel();
    [[nodiscard]] const std::vector<std::string>& items() const;
    [[nodiscard]] int selectedIndex() const;
    [[nodiscard]] std::string selectedItem() const;
    [[nodiscard]] bool hasHandlers() const;

    void setHandlers(const std::vector<std::string>& handlerNames);
    void clearHandlers();
    bool selectHandler(std::string_view handlerName);

private:
    std::vector<std::string> items_;
    int selectedIndex_{0};
};

class LingoDocumentModel {
public:
    LingoDocumentModel();
    explicit LingoDocumentModel(std::string text);

    [[nodiscard]] const std::string& text() const;
    [[nodiscard]] const std::vector<ScriptStyleRange>& highlights() const;
    [[nodiscard]] std::size_t length() const;
    [[nodiscard]] bool isHighlighting() const;

    bool setText(std::string text);
    bool insertString(std::size_t offset, std::string_view value);
    bool remove(std::size_t offset, std::size_t length);
    void rehighlight();

private:
    std::string text_;
    std::vector<ScriptStyleRange> highlights_;
    bool highlighting_{false};
};

} // namespace libreshockwave::editor::script
