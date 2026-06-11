#include "libreshockwave/editor/gtk/EditorGtkModels.hpp"

#include <algorithm>
#include <cctype>
#include <map>
#include <utility>

namespace libreshockwave::editor::gtk {
namespace {

void collectMenuActions(const std::vector<EditorMenuItem>& items, std::map<std::string, GtkActionSpec>& specs) {
    for (const auto& item : items) {
        if (item.command != EditorCommand::None) {
            const auto name = EditorGtkShellModel::commandActionName(item.command);
            auto& spec = specs[name];
            const bool initialized = !spec.name.empty();
            spec.name = name;
            spec.detailedName = EditorGtkShellModel::appAction(name);
            spec.command = item.command;
            spec.enabled = initialized ? (spec.enabled || item.enabled) : item.enabled;
        }
        collectMenuActions(item.children, specs);
    }
}

} // namespace

std::string EditorGtkShellModel::sanitizeActionName(std::string_view value) {
    std::string result;
    result.reserve(value.size());
    for (const char c : value) {
        if (std::isalnum(static_cast<unsigned char>(c)) != 0 || c == '_') {
            result.push_back(c);
        } else {
            result.push_back('_');
        }
    }
    return result.empty() ? "none" : result;
}

std::string EditorGtkShellModel::commandActionName(EditorCommand command) {
    return sanitizeActionName(commandName(command));
}

std::string EditorGtkShellModel::panelActionName(std::string_view panelId) {
    return "panel_" + sanitizeActionName(panelId);
}

std::string EditorGtkShellModel::appAction(std::string_view actionName) {
    return "app." + std::string(actionName);
}

std::vector<GtkActionSpec> EditorGtkShellModel::actionSpecs(const EditorMenuModel& menuModel,
                                                            const EditorToolBarModel& toolbarModel,
                                                            const EditorFramePanelModel& frameModel) {
    std::map<std::string, GtkActionSpec> specs;
    for (const auto& menu : menuModel.menus()) {
        collectMenuActions(menu.items, specs);
    }

    for (const auto& item : toolbarModel.items()) {
        if (item.command == EditorCommand::None) {
            continue;
        }
        const auto name = commandActionName(item.command);
        auto& spec = specs[name];
        spec.name = name;
        spec.detailedName = appAction(name);
        spec.command = item.command;
        spec.enabled = true;
    }

    for (const auto& [panelId, visible] : frameModel.panelVisibility()) {
        const auto name = panelActionName(panelId);
        specs[name] = GtkActionSpec{
            name,
            appAction(name),
            EditorCommand::None,
            panelId,
            true,
            true,
            visible,
        };
    }

    std::vector<GtkActionSpec> result;
    result.reserve(specs.size());
    for (auto& [_, spec] : specs) {
        result.push_back(std::move(spec));
    }
    return result;
}

std::optional<GtkActionSpec> EditorGtkShellModel::actionSpec(std::string_view name,
                                                             const EditorMenuModel& menuModel,
                                                             const EditorToolBarModel& toolbarModel,
                                                             const EditorFramePanelModel& frameModel) {
    const auto specs = actionSpecs(menuModel, toolbarModel, frameModel);
    const auto found = std::find_if(specs.begin(), specs.end(), [name](const GtkActionSpec& spec) {
        return spec.name == name;
    });
    return found == specs.end() ? std::nullopt : std::optional(*found);
}

std::vector<GtkToolbarItemSpec> EditorGtkShellModel::toolbarItems(const EditorToolBarModel& toolbarModel) {
    std::vector<GtkToolbarItemSpec> result;
    result.reserve(toolbarModel.items().size());
    for (const auto& item : toolbarModel.items()) {
        GtkToolbarItemSpec spec;
        spec.kind = item.kind;
        spec.label = item.label;
        spec.tooltip = item.tooltip;
        if (item.command != EditorCommand::None) {
            spec.actionName = commandActionName(item.command);
            spec.detailedActionName = appAction(spec.actionName);
        }
        result.push_back(std::move(spec));
    }
    return result;
}

std::vector<GtkPanelRowSpec> EditorGtkShellModel::panelRows(const EditorFramePanelModel& frameModel) {
    std::vector<GtkPanelRowSpec> result;
    const auto descriptors = panels::EditorPanelCatalog::descriptors();
    result.reserve(descriptors.size());
    for (const auto& descriptor : descriptors) {
        const auto panel = frameModel.panel(descriptor.panelId);
        GtkPanelRowSpec row;
        row.panelId = descriptor.panelId;
        row.title = descriptor.title;
        row.displayLabel = descriptor.title;
        if (panel.has_value()) {
            row.bounds = panel->bounds;
            row.visible = panel->visible;
            row.selected = panel->selected;
            row.iconified = panel->iconified;
            row.docked = panel->docked;
        } else {
            row.bounds = panels::PanelBounds{0, 0, descriptor.initialSize.width, descriptor.initialSize.height};
        }
        if (!row.visible) {
            row.displayLabel += " (hidden)";
        }
        result.push_back(std::move(row));
    }
    return result;
}

} // namespace libreshockwave::editor::gtk
