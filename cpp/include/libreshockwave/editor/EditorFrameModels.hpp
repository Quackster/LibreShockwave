#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/docking/DockingModels.hpp"
#include "libreshockwave/editor/panels/EditorPanelCatalog.hpp"

namespace libreshockwave::editor {

struct EditorFramePanelState {
    std::string panelId;
    panels::PanelBounds bounds;
    bool visible{false};
    bool selected{false};
    bool iconified{false};
    bool docked{false};

    friend bool operator==(const EditorFramePanelState&, const EditorFramePanelState&) = default;
};

struct EditorOpenFileDialogModel {
    std::string title;
    std::string filterLabel;
    std::vector<std::string> extensions;
    std::optional<std::string> currentDirectory;

    friend bool operator==(const EditorOpenFileDialogModel&, const EditorOpenFileDialogModel&) = default;
};

class EditorFramePanelModel {
public:
    EditorFramePanelModel();

    [[nodiscard]] const docking::DockingLayoutModel& dockingLayout() const;
    [[nodiscard]] docking::DockingLayoutModel& dockingLayout();
    [[nodiscard]] const std::vector<EditorFramePanelState>& panels() const;
    [[nodiscard]] std::optional<EditorFramePanelState> panel(std::string_view panelId) const;
    [[nodiscard]] bool hasPanel(std::string_view panelId) const;
    [[nodiscard]] bool isPanelVisible(std::string_view panelId) const;
    [[nodiscard]] std::vector<std::pair<std::string, bool>> panelVisibility() const;

    bool togglePanel(std::string_view panelId, bool visible);
    bool showPanel(std::string_view panelId);
    bool iconifyPanelForTesting(std::string_view panelId);
    void resetLayout();

    [[nodiscard]] static std::vector<std::string> creationOrder();
    [[nodiscard]] static EditorOpenFileDialogModel openFileDialog(std::optional<std::string> lastDirectory);

private:
    [[nodiscard]] std::optional<std::size_t> indexOf(std::string_view panelId) const;
    void registerPanels();
    void arrangeDefaultLayout();
    void clearSelection();

    std::vector<EditorFramePanelState> panels_;
    docking::DockingLayoutModel dockingLayout_;
};

} // namespace libreshockwave::editor
