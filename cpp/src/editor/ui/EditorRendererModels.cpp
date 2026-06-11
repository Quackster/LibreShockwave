#include "libreshockwave/editor/ui/EditorRendererModels.hpp"

#include <utility>

namespace libreshockwave::editor::ui {
namespace {

TreeCellPresentation treeCell(std::string text,
                              TreeIcon icon,
                              std::optional<UiColor> foreground,
                              bool selected,
                              bool expanded,
                              bool leaf,
                              int row,
                              bool hasFocus) {
    return TreeCellPresentation{
        std::move(text), icon, foreground, selected, expanded, leaf, row, hasFocus,
    };
}

} // namespace

std::optional<UiColor> EditorRendererModels::memberTypeForeground(::libreshockwave::cast::MemberType type) {
    using ::libreshockwave::cast::MemberType;
    switch (type) {
        case MemberType::Bitmap:
            return UiColor{0, 100, 0};
        case MemberType::Script:
            return UiColor{0, 0, 180};
        case MemberType::Sound:
            return UiColor{180, 0, 180};
        case MemberType::Text:
        case MemberType::Button:
            return UiColor{100, 100, 0};
        case MemberType::Shape:
            return UiColor{180, 100, 0};
        case MemberType::Palette:
            return UiColor{100, 0, 100};
        case MemberType::FilmLoop:
            return UiColor{0, 100, 100};
        default:
            return std::nullopt;
    }
}

TreeCellPresentation EditorRendererModels::fileTreeCell(const model::FileNode& node,
                                                        bool selected,
                                                        bool expanded,
                                                        bool leaf,
                                                        int row,
                                                        bool hasFocus) {
    return treeCell(node.toString(), TreeIcon::Directory, std::nullopt, selected, expanded, leaf, row, hasFocus);
}

TreeCellPresentation EditorRendererModels::memberTreeCell(const model::MemberNodeData& node,
                                                          bool selected,
                                                          bool expanded,
                                                          bool leaf,
                                                          int row,
                                                          bool hasFocus) {
    return treeCell(node.toString(),
                    TreeIcon::File,
                    memberTypeForeground(node.memberInfo.memberType),
                    selected,
                    expanded,
                    leaf,
                    row,
                    hasFocus);
}

TreeCellPresentation EditorRendererModels::plainTreeCell(std::string_view text,
                                                         bool selected,
                                                         bool expanded,
                                                         bool leaf,
                                                         int row,
                                                         bool hasFocus) {
    return treeCell(std::string(text), TreeIcon::None, std::nullopt, selected, expanded, leaf, row, hasFocus);
}

RowHeaderPresentation EditorRendererModels::rowHeaderCell(std::string_view value,
                                                          int index,
                                                          bool selected,
                                                          bool hasFocus) {
    return RowHeaderPresentation{
        std::string(value),
        "table-header-background",
        "table-header-cell-border",
        "table-header-font",
        HorizontalAlignment::Center,
        true,
        index,
        selected,
        hasFocus,
    };
}

} // namespace libreshockwave::editor::ui
