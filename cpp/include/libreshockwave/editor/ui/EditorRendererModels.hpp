#pragma once

#include <optional>
#include <string>
#include <string_view>

#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/editor/model/FileNode.hpp"
#include "libreshockwave/editor/model/MemberNodeData.hpp"

namespace libreshockwave::editor::ui {

struct UiColor {
    int r{};
    int g{};
    int b{};

    friend bool operator==(const UiColor&, const UiColor&) = default;
};

enum class TreeIcon {
    None,
    Directory,
    File
};

enum class HorizontalAlignment {
    Left,
    Center,
    Right
};

struct TreeCellPresentation {
    std::string text;
    TreeIcon icon{TreeIcon::None};
    std::optional<UiColor> foreground;
    bool selected{false};
    bool expanded{false};
    bool leaf{false};
    int row{};
    bool hasFocus{false};

    friend bool operator==(const TreeCellPresentation&, const TreeCellPresentation&) = default;
};

struct RowHeaderPresentation {
    std::string text;
    std::string backgroundRole;
    std::string borderRole;
    std::string fontRole;
    HorizontalAlignment alignment{HorizontalAlignment::Center};
    bool opaque{true};
    int index{};
    bool selected{false};
    bool hasFocus{false};

    friend bool operator==(const RowHeaderPresentation&, const RowHeaderPresentation&) = default;
};

class EditorRendererModels {
public:
    [[nodiscard]] static std::optional<UiColor> memberTypeForeground(::libreshockwave::cast::MemberType type);

    [[nodiscard]] static TreeCellPresentation fileTreeCell(const model::FileNode& node,
                                                           bool selected = false,
                                                           bool expanded = false,
                                                           bool leaf = false,
                                                           int row = 0,
                                                           bool hasFocus = false);
    [[nodiscard]] static TreeCellPresentation memberTreeCell(const model::MemberNodeData& node,
                                                             bool selected = false,
                                                             bool expanded = false,
                                                             bool leaf = true,
                                                             int row = 0,
                                                             bool hasFocus = false);
    [[nodiscard]] static TreeCellPresentation plainTreeCell(std::string_view text,
                                                            bool selected = false,
                                                            bool expanded = false,
                                                            bool leaf = false,
                                                            int row = 0,
                                                            bool hasFocus = false);

    [[nodiscard]] static RowHeaderPresentation rowHeaderCell(std::string_view value,
                                                             int index = 0,
                                                             bool selected = false,
                                                             bool hasFocus = false);
};

} // namespace libreshockwave::editor::ui
