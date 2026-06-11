#pragma once

#include <cstdint>
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

struct GtkActionActivation {
    std::string actionName;
    EditorCommand command{EditorCommand::None};
    std::string panelId;
    bool handled{false};
    bool requestQuit{false};
    bool refreshActions{false};
    bool refreshPanels{false};
    std::optional<bool> active;
    std::string statusMessage;

    friend bool operator==(const GtkActionActivation&, const GtkActionActivation&) = default;
};

struct GtkShellViewState {
    std::string windowTitle;
    int windowWidth{0};
    int windowHeight{0};
    std::uint8_t desktopBackgroundR{0};
    std::uint8_t desktopBackgroundG{0};
    std::uint8_t desktopBackgroundB{0};
    std::string stageTitle;
    std::string stagePlaceholderText;
    std::string statusMessage;
    std::vector<GtkActionSpec> actionSpecs;
    std::vector<GtkToolbarItemSpec> toolbarItems;
    std::vector<GtkPanelRowSpec> panelRows;

    friend bool operator==(const GtkShellViewState&, const GtkShellViewState&) = default;
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

class EditorGtkShellState {
public:
    [[nodiscard]] const EditorMenuModel& menuModel() const;
    [[nodiscard]] const EditorToolBarModel& toolbarModel() const;
    [[nodiscard]] const EditorFramePanelModel& frameModel() const;
    [[nodiscard]] const std::optional<std::string>& openMoviePath() const;
    [[nodiscard]] const std::string& statusMessage() const;

    [[nodiscard]] std::vector<GtkActionSpec> actionSpecs() const;
    [[nodiscard]] std::optional<GtkActionSpec> actionSpec(std::string_view name) const;
    [[nodiscard]] std::vector<GtkToolbarItemSpec> toolbarItems() const;
    [[nodiscard]] std::vector<GtkPanelRowSpec> panelRows() const;
    [[nodiscard]] GtkShellViewState viewState() const;

    void setOpenMoviePath(std::optional<std::string> path);
    GtkActionActivation activateAction(std::string_view name);

private:
    EditorMenuModel menuModel_;
    EditorToolBarModel toolbarModel_;
    EditorFramePanelModel frameModel_;
    std::optional<std::string> openMoviePath_;
    std::string statusMessage_;
};

} // namespace libreshockwave::editor::gtk
