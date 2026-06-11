#include "libreshockwave/editor/EditorFrameModels.hpp"

#include <algorithm>
#include <utility>

#include "libreshockwave/editor/AppModels.hpp"

namespace libreshockwave::editor {

EditorFramePanelModel::EditorFramePanelModel() {
    registerPanels();
    arrangeDefaultLayout();
}

const docking::DockingLayoutModel& EditorFramePanelModel::dockingLayout() const {
    return dockingLayout_;
}

docking::DockingLayoutModel& EditorFramePanelModel::dockingLayout() {
    return dockingLayout_;
}

const std::vector<EditorFramePanelState>& EditorFramePanelModel::panels() const {
    return panels_;
}

std::optional<EditorFramePanelState> EditorFramePanelModel::panel(std::string_view panelId) const {
    const auto index = indexOf(panelId);
    if (!index.has_value()) {
        return std::nullopt;
    }
    auto state = panels_[*index];
    state.docked = dockingLayout_.isDocked(panelId);
    if (state.docked) {
        state.visible = true;
    }
    return state;
}

bool EditorFramePanelModel::hasPanel(std::string_view panelId) const {
    return indexOf(panelId).has_value();
}

bool EditorFramePanelModel::isPanelVisible(std::string_view panelId) const {
    if (dockingLayout_.isDocked(panelId)) {
        return true;
    }
    const auto index = indexOf(panelId);
    return index.has_value() && panels_[*index].visible;
}

std::vector<std::pair<std::string, bool>> EditorFramePanelModel::panelVisibility() const {
    std::vector<std::pair<std::string, bool>> visibility;
    visibility.reserve(panels_.size());
    for (const auto& panel : panels_) {
        visibility.emplace_back(panel.panelId, isPanelVisible(panel.panelId));
    }
    return visibility;
}

bool EditorFramePanelModel::togglePanel(std::string_view panelId, bool visible) {
    const auto index = indexOf(panelId);
    if (!index.has_value()) {
        return false;
    }

    auto& panel = panels_[*index];
    if (!visible) {
        if (dockingLayout_.isDocked(panelId)) {
            dockingLayout_.undock(panelId);
        }
        panel.docked = false;
        panel.visible = false;
        panel.selected = false;
        return true;
    }

    const bool docked = dockingLayout_.dockCenter(panelId);
    panel.docked = docked;
    panel.visible = true;
    panel.selected = docked;
    panel.iconified = false;
    return docked;
}

bool EditorFramePanelModel::showPanel(std::string_view panelId) {
    const auto index = indexOf(panelId);
    if (!index.has_value()) {
        return false;
    }
    if (dockingLayout_.isDocked(panelId)) {
        panels_[*index].docked = true;
        panels_[*index].visible = true;
        return true;
    }

    clearSelection();
    auto& panel = panels_[*index];
    panel.visible = true;
    panel.iconified = false;
    panel.selected = true;
    return true;
}

bool EditorFramePanelModel::iconifyPanelForTesting(std::string_view panelId) {
    const auto index = indexOf(panelId);
    if (!index.has_value()) {
        return false;
    }
    panels_[*index].iconified = true;
    panels_[*index].selected = false;
    return true;
}

void EditorFramePanelModel::resetLayout() {
    dockingLayout_.undockAll();
    arrangeDefaultLayout();
}

std::vector<std::string> EditorFramePanelModel::creationOrder() {
    std::vector<std::string> ids;
    const auto descriptors = panels::EditorPanelCatalog::descriptors();
    ids.reserve(descriptors.size());
    for (const auto& descriptor : descriptors) {
        ids.push_back(descriptor.panelId);
    }
    return ids;
}

EditorOpenFileDialogModel EditorFramePanelModel::openFileDialog(std::optional<std::string> lastDirectory) {
    return EditorOpenFileDialogModel{
        "Open Director File",
        "Director Files (*.dir, *.dxr, *.dcr, *.cct, *.cst)",
        StartScreenModel::directorFileExtensions(),
        std::move(lastDirectory),
    };
}

std::optional<std::size_t> EditorFramePanelModel::indexOf(std::string_view panelId) const {
    const auto found = std::find_if(panels_.begin(), panels_.end(), [panelId](const EditorFramePanelState& panel) {
        return panel.panelId == panelId;
    });
    if (found == panels_.end()) {
        return std::nullopt;
    }
    return static_cast<std::size_t>(std::distance(panels_.begin(), found));
}

void EditorFramePanelModel::registerPanels() {
    panels_.clear();
    for (const auto& descriptor : panels::EditorPanelCatalog::descriptors()) {
        panels_.push_back(EditorFramePanelState{
            descriptor.panelId,
            panels::PanelBounds{0, 0, descriptor.initialSize.width, descriptor.initialSize.height},
            true,
            false,
            false,
            false,
        });
        dockingLayout_.registerPanel(descriptor.panelId, descriptor.title);
    }
}

void EditorFramePanelModel::arrangeDefaultLayout() {
    const auto placements = panels::EditorPanelCatalog::defaultFloatingLayout();
    for (const auto& placement : placements) {
        const auto index = indexOf(placement.panelId);
        if (!index.has_value()) {
            continue;
        }
        panels_[*index].bounds = placement.bounds;
        panels_[*index].visible = placement.visible;
        panels_[*index].selected = placement.selected;
        panels_[*index].iconified = false;
        panels_[*index].docked = false;
    }
}

void EditorFramePanelModel::clearSelection() {
    for (auto& panel : panels_) {
        panel.selected = false;
    }
}

} // namespace libreshockwave::editor
