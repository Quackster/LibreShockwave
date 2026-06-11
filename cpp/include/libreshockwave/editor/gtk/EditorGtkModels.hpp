#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/EditorFrameModels.hpp"
#include "libreshockwave/editor/EditorShellModels.hpp"
#include "libreshockwave/editor/panels/EditorPanelCatalog.hpp"

namespace libreshockwave::editor::gtk {

struct GtkActionSpec {
    std::string name;
    std::string detailedName;
    EditorCommand command{EditorCommand::None};
    std::string panelId;
    bool enabled{true};
    bool stateful{false};
    bool active{false};

    friend bool operator==(const GtkActionSpec&, const GtkActionSpec&) = default;
};

struct GtkToolbarItemSpec {
    ToolbarItem::Kind kind{ToolbarItem::Kind::Button};
    std::string label;
    std::string tooltip;
    std::string actionName;
    std::string detailedActionName;

    friend bool operator==(const GtkToolbarItemSpec&, const GtkToolbarItemSpec&) = default;
};

struct GtkPanelRowSpec {
    std::string panelId;
    std::string title;
    std::string displayLabel;
    panels::PanelBounds bounds;
    bool visible{false};
    bool selected{false};
    bool iconified{false};
    bool docked{false};

    friend bool operator==(const GtkPanelRowSpec&, const GtkPanelRowSpec&) = default;
};

class EditorGtkShellModel {
public:
    [[nodiscard]] static std::string sanitizeActionName(std::string_view value);
    [[nodiscard]] static std::string commandActionName(EditorCommand command);
    [[nodiscard]] static std::string panelActionName(std::string_view panelId);
    [[nodiscard]] static std::string appAction(std::string_view actionName);

    [[nodiscard]] static std::vector<GtkActionSpec> actionSpecs(const EditorMenuModel& menuModel,
                                                                const EditorToolBarModel& toolbarModel,
                                                                const EditorFramePanelModel& frameModel);
    [[nodiscard]] static std::optional<GtkActionSpec> actionSpec(std::string_view name,
                                                                 const EditorMenuModel& menuModel,
                                                                 const EditorToolBarModel& toolbarModel,
                                                                 const EditorFramePanelModel& frameModel);
    [[nodiscard]] static std::vector<GtkToolbarItemSpec> toolbarItems(const EditorToolBarModel& toolbarModel);
    [[nodiscard]] static std::vector<GtkPanelRowSpec> panelRows(const EditorFramePanelModel& frameModel);
};

} // namespace libreshockwave::editor::gtk
