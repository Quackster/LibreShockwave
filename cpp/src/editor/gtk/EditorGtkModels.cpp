#include "libreshockwave/editor/gtk/EditorGtkModels.hpp"

#include <algorithm>
#include <cctype>
#include <limits>
#include <map>
#include <optional>
#include <string>
#include <utility>

#include "libreshockwave/editor/panels/EditorPanelModels.hpp"
#include "libreshockwave/editor/score/ScoreViewModels.hpp"
#include "libreshockwave/editor/stage/StageViewModels.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::editor::gtk {
namespace {

constexpr std::string_view TRACE_HANDLER_PROMPT =
    "Enter handler names to trace (comma-separated), or clear to remove all:";
constexpr std::string_view ABOUT_BODY =
    "LibreShockwave Editor\n\n"
    "A recreation of Macromedia Director MX 2004.\n\n"
    "Part of the LibreShockwave project.";
constexpr std::string_view OPEN_RECENT_ACTION_PREFIX = "open_recent_";

std::string gtkAcceleratorKey(std::string_view key) {
    if (key == "DELETE") {
        return "Delete";
    }
    if (key == "PERIOD") {
        return "period";
    }
    if (key == "RIGHT") {
        return "Right";
    }
    if (key == "LEFT") {
        return "Left";
    }
    return std::string(key);
}

std::string gtkAccelerator(const EditorAccelerator& accelerator) {
    std::string result;
    if (accelerator.ctrl) {
        result += "<Control>";
    }
    if (accelerator.alt) {
        result += "<Alt>";
    }
    if (accelerator.shift) {
        result += "<Shift>";
    }
    result += gtkAcceleratorKey(accelerator.key);
    return result;
}

void appendAccelerator(GtkActionSpec& spec, const std::optional<EditorAccelerator>& accelerator) {
    if (!accelerator.has_value()) {
        return;
    }
    const auto formatted = gtkAccelerator(*accelerator);
    if (std::find(spec.accelerators.begin(), spec.accelerators.end(), formatted) == spec.accelerators.end()) {
        spec.accelerators.push_back(formatted);
    }
}

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
            appendAccelerator(spec, item.accelerator);
        } else if (!item.panelId.empty()) {
            const auto name = EditorGtkShellModel::panelActionName(item.panelId);
            auto& spec = specs[name];
            const bool initialized = !spec.name.empty();
            spec.name = name;
            spec.detailedName = EditorGtkShellModel::appAction(name);
            spec.panelId = item.panelId;
            spec.enabled = initialized ? (spec.enabled || item.enabled) : item.enabled;
            spec.stateful = true;
            spec.active = item.checked;
            appendAccelerator(spec, item.accelerator);
        }
        collectMenuActions(item.children, specs);
    }
}

GtkMenuItemKind menuItemKind(EditorMenuItem::Kind kind) {
    switch (kind) {
        case EditorMenuItem::Kind::Command:
            return GtkMenuItemKind::Command;
        case EditorMenuItem::Kind::Check:
            return GtkMenuItemKind::Check;
        case EditorMenuItem::Kind::Separator:
            return GtkMenuItemKind::Separator;
        case EditorMenuItem::Kind::Submenu:
            return GtkMenuItemKind::Submenu;
    }
    return GtkMenuItemKind::Command;
}

std::vector<std::string> acceleratorStrings(const std::optional<EditorAccelerator>& accelerator) {
    if (!accelerator.has_value()) {
        return {};
    }
    return {gtkAccelerator(*accelerator)};
}

GtkMenuItemSpec menuItemSpec(const EditorMenuItem& item, const EditorFramePanelModel& frameModel) {
    GtkMenuItemSpec spec;
    spec.kind = menuItemKind(item.kind);
    spec.label = item.label;
    spec.command = item.command;
    spec.panelId = item.panelId;
    spec.enabled = item.enabled;
    spec.checked = item.checked;
    spec.accelerators = acceleratorStrings(item.accelerator);

    if (!item.panelId.empty()) {
        spec.enabled = item.enabled && frameModel.hasPanel(item.panelId);
        spec.checked = frameModel.isPanelVisible(item.panelId);
        spec.actionName = EditorGtkShellModel::panelActionName(item.panelId);
        spec.detailedActionName = EditorGtkShellModel::appAction(spec.actionName);
    } else if (item.command != EditorCommand::None) {
        spec.actionName = EditorGtkShellModel::commandActionName(item.command);
        spec.detailedActionName = EditorGtkShellModel::appAction(spec.actionName);
    }

    spec.children.reserve(item.children.size());
    for (const auto& child : item.children) {
        spec.children.push_back(menuItemSpec(child, frameModel));
    }
    return spec;
}

std::string panelTitle(std::string_view panelId) {
    const auto descriptor = panels::EditorPanelCatalog::descriptor(panelId);
    return descriptor.has_value() ? descriptor->title : std::string(panelId);
}

std::string displayFileName(std::string_view path) {
    const auto fileName = util::getFileName(path);
    return fileName.empty() ? std::string(path) : fileName;
}

std::optional<std::string> parentDirectory(std::string_view path) {
    const auto slash = path.find_last_of("/\\");
    if (slash == std::string_view::npos) {
        return std::nullopt;
    }
    if (slash == 0) {
        return std::string(path.substr(0, 1));
    }
    return std::string(path.substr(0, slash));
}

std::string joinTraceHandlers(const std::vector<std::string>& handlers) {
    std::string result;
    for (const auto& handler : handlers) {
        if (!result.empty()) {
            result += ", ";
        }
        result += handler;
    }
    return result;
}

std::optional<int> parseNonNegativeIndex(std::string_view value) {
    if (value.empty()) {
        return std::nullopt;
    }

    int result = 0;
    for (const char c : value) {
        if (std::isdigit(static_cast<unsigned char>(c)) == 0) {
            return std::nullopt;
        }
        const int digit = c - '0';
        if (result > (std::numeric_limits<int>::max() - digit) / 10) {
            return std::nullopt;
        }
        result = result * 10 + digit;
    }
    return result;
}

std::string dialogCancelStatus(GtkShellDialogKind kind) {
    switch (kind) {
        case GtkShellDialogKind::ExternalParameters:
            return "External parameters cancelled";
        case GtkShellDialogKind::TraceHandler:
            return "Trace handler cancelled";
        case GtkShellDialogKind::About:
            return "About dialog closed";
        case GtkShellDialogKind::DetailedStack:
            return "Detailed stack window closed";
    }
    return "Dialog cancelled";
}

GtkWorkbenchPanelKind panelKind(std::string_view panelId) {
    if (panelId == "stage") {
        return GtkWorkbenchPanelKind::Stage;
    }
    if (panelId == "score") {
        return GtkWorkbenchPanelKind::Score;
    }
    if (panelId == "cast") {
        return GtkWorkbenchPanelKind::Cast;
    }
    if (panelId == "property-inspector") {
        return GtkWorkbenchPanelKind::PropertyInspector;
    }
    if (panelId == "script") {
        return GtkWorkbenchPanelKind::Script;
    }
    if (panelId == "message") {
        return GtkWorkbenchPanelKind::Message;
    }
    if (panelId == "tool-palette") {
        return GtkWorkbenchPanelKind::ToolPalette;
    }
    if (panelId == "paint") {
        return GtkWorkbenchPanelKind::Paint;
    }
    if (panelId == "vector-shape") {
        return GtkWorkbenchPanelKind::VectorShape;
    }
    if (panelId == "text") {
        return GtkWorkbenchPanelKind::Text;
    }
    if (panelId == "field") {
        return GtkWorkbenchPanelKind::Field;
    }
    if (panelId == "sound") {
        return GtkWorkbenchPanelKind::Sound;
    }
    if (panelId == "color-palettes") {
        return GtkWorkbenchPanelKind::ColorPalettes;
    }
    if (panelId == "bytecode-debugger") {
        return GtkWorkbenchPanelKind::BytecodeDebugger;
    }
    return GtkWorkbenchPanelKind::Generic;
}

std::vector<std::string> actionLabels(const std::vector<panels::DisabledPanelAction>& actions) {
    std::vector<std::string> result;
    result.reserve(actions.size());
    for (const auto& action : actions) {
        result.push_back(action.label);
    }
    return result;
}

std::vector<GtkWorkbenchContentActionSpec> contentActionSpecs(const std::vector<std::string>& labels) {
    std::vector<GtkWorkbenchContentActionSpec> result;
    result.reserve(labels.size());
    for (const auto& label : labels) {
        result.push_back(GtkWorkbenchContentActionSpec{label, label + " (not yet implemented)", false});
    }
    return result;
}

std::vector<std::string> toolLabels(const std::vector<panels::ToolPaletteTool>& tools) {
    std::vector<std::string> result;
    result.reserve(tools.size());
    for (const auto& tool : tools) {
        result.push_back(tool.label);
    }
    return result;
}

std::string toolbarIconName(EditorCommand command) {
    switch (command) {
        case EditorCommand::Rewind:
            return "media-seek-backward-symbolic";
        case EditorCommand::Stop:
            return "media-playback-stop-symbolic";
        case EditorCommand::Play:
            return "media-playback-start-symbolic";
        case EditorCommand::StepBackward:
            return "go-previous-symbolic";
        case EditorCommand::StepForward:
            return "go-next-symbolic";
        default:
            return {};
    }
}

void populateWorkbenchContent(GtkWorkbenchPanelSpec& spec, const EditorContextModel& contextModel) {
    const auto& currentPath = contextModel.currentPath();
    switch (spec.kind) {
        case GtkWorkbenchPanelKind::Stage:
            spec.title = currentPath.has_value()
                ? stage::StageViewModel::titleForOpenedPath(*currentPath)
                : stage::StageViewModel::closedTitle();
            spec.primaryText = currentPath.has_value()
                ? "Movie loaded: " + displayFileName(*currentPath)
                : "No movie loaded";
            spec.statusText = score::ScoreViewModels::frameStatus(contextModel.currentFrame());
            break;
        case GtkWorkbenchPanelKind::Score:
            spec.primaryText = currentPath.has_value() ? "" : "No score data loaded";
            spec.statusText = currentPath.has_value()
                ? score::ScoreViewModels::frameStatus(contextModel.currentFrame())
                : score::ScoreViewModels::closedStatus();
            break;
        case GtkWorkbenchPanelKind::Message:
            spec.primaryText = panels::MessageConsoleModel::welcomeText();
            break;
        case GtkWorkbenchPanelKind::ToolPalette:
            spec.actionLabels = toolLabels(panels::ToolPaletteModel::tools());
            break;
        case GtkWorkbenchPanelKind::Paint: {
            const auto state = panels::PaintPanelModel::emptyState();
            spec.primaryText = state.imageText;
            spec.statusText = state.status;
            spec.actionLabels = actionLabels(panels::PaintPanelModel::toolbarActions());
            break;
        }
        case GtkWorkbenchPanelKind::VectorShape:
            spec.primaryText = panels::VectorShapePanelModel::placeholderText();
            spec.actionLabels = panels::VectorShapePanelModel::toolbarActions();
            break;
        case GtkWorkbenchPanelKind::Text: {
            const auto state = panels::TextEditorPanelModel::emptyState();
            spec.primaryText = state.text;
            spec.statusText = state.status;
            const auto toolbar = panels::TextEditorPanelModel::toolbar();
            spec.actionLabels = actionLabels(toolbar.styleButtons);
            break;
        }
        case GtkWorkbenchPanelKind::Field: {
            const auto state = panels::FieldEditorModel::emptyState();
            spec.primaryText = state.text;
            spec.statusText = state.status;
            spec.actionLabels = actionLabels(panels::FieldEditorModel::toolbarActions());
            break;
        }
        case GtkWorkbenchPanelKind::Sound: {
            const auto state = panels::SoundPanelModel::emptyState();
            spec.primaryText = state.infoText;
            spec.statusText = state.status;
            break;
        }
        case GtkWorkbenchPanelKind::ColorPalettes: {
            const auto view = panels::ColorPalettesModel::view();
            spec.primaryText = view.placeholderText;
            spec.actionLabels = view.paletteOptions;
            break;
        }
        case GtkWorkbenchPanelKind::Cast:
            spec.primaryText = "Cast members";
            spec.statusText = " 0 of 0 members";
            break;
        case GtkWorkbenchPanelKind::PropertyInspector:
            spec.primaryText = "No selection";
            break;
        case GtkWorkbenchPanelKind::Script:
            spec.primaryText = "No script selected";
            break;
        case GtkWorkbenchPanelKind::BytecodeDebugger:
            spec.primaryText = "Status: Running";
            break;
        case GtkWorkbenchPanelKind::Generic:
            break;
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

std::string EditorGtkShellModel::workbenchPanelActionName(std::string_view panelId) {
    return "workbench_" + sanitizeActionName(panelId);
}

std::string EditorGtkShellModel::workbenchPanelFloatActionName(std::string_view panelId) {
    return "workbench_float_" + sanitizeActionName(panelId);
}

std::string EditorGtkShellModel::recentProjectActionName(int index) {
    return std::string(OPEN_RECENT_ACTION_PREFIX) + std::to_string(index);
}

std::optional<int> EditorGtkShellModel::recentProjectActionIndex(std::string_view actionName) {
    if (!actionName.starts_with(OPEN_RECENT_ACTION_PREFIX)) {
        return std::nullopt;
    }
    return parseNonNegativeIndex(actionName.substr(OPEN_RECENT_ACTION_PREFIX.size()));
}

std::string EditorGtkShellModel::appAction(std::string_view actionName) {
    return "app." + std::string(actionName);
}

GtkShellDialogPresentation EditorGtkShellModel::dialogPresentation(const GtkShellDialogRequest& request) {
    GtkShellDialogPresentation presentation;
    presentation.kind = request.kind;
    presentation.title = request.title;
    presentation.bodyText = request.message;
    presentation.inputLabel = request.prompt;
    presentation.inputText = request.currentValue;
    presentation.modal = request.modal;
    presentation.warning = request.warning;

    switch (request.kind) {
        case GtkShellDialogKind::ExternalParameters:
            presentation.hasExternalParamsTable = true;
            presentation.externalParams = request.externalParams;
            presentation.acceptLabel = "Apply";
            presentation.cancelLabel = "Cancel";
            break;
        case GtkShellDialogKind::TraceHandler:
            if (request.prompt.empty()) {
                presentation.closeLabel = "OK";
            } else {
                presentation.hasTextInput = true;
                presentation.acceptLabel = "Apply";
                presentation.cancelLabel = "Cancel";
            }
            break;
        case GtkShellDialogKind::About:
            presentation.closeLabel = "OK";
            break;
        case GtkShellDialogKind::DetailedStack:
            presentation.hasDetailedStackView = true;
            presentation.detailedStackView = request.detailedStackView;
            presentation.closeLabel = "Close";
            break;
    }

    return presentation;
}

GtkOpenFileDialogPresentation EditorGtkShellModel::openFileDialogPresentation(
    const EditorOpenFileDialogModel& request) {
    GtkOpenFileDialogPresentation presentation;
    presentation.title = request.title;
    presentation.filter.label = request.filterLabel;
    presentation.filter.extensions = request.extensions;
    presentation.filter.patterns.reserve(request.extensions.size());
    for (const auto& extension : request.extensions) {
        presentation.filter.patterns.push_back("*." + extension);
    }
    presentation.currentDirectory = request.currentDirectory;
    presentation.acceptLabel = "Open";
    presentation.cancelLabel = "Cancel";
    return presentation;
}

GtkStartScreenPresentation EditorGtkShellModel::startScreenPresentation(const GtkStartScreenRequest& request) {
    GtkStartScreenPresentation presentation;
    presentation.title = request.title;
    presentation.subtitle = request.subtitle;
    presentation.recentProjectsLabel = "Recent Projects:";
    presentation.emptyRecentsMessage = request.emptyRecentsMessage;
    presentation.hasRecentProjects = !request.recentProjects.empty();
    presentation.openMovieButtonLabel = "Open Movie...";
    presentation.createNewMovieButtonLabel = "Create New Movie";
    presentation.createNewMovieEnabled = request.createNewMovieEnabled;
    presentation.openFileDialog = openFileDialogPresentation(request.openFileDialog);

    presentation.recentProjects.reserve(request.recentProjects.size());
    for (std::size_t index = 0; index < request.recentProjects.size(); ++index) {
        const auto& recent = request.recentProjects[index];
        GtkRecentProjectRowSpec row;
        row.index = static_cast<int>(index);
        row.path = recent.path;
        row.title = recent.fileName.empty() ? recent.path : recent.fileName;
        row.subtitle = recent.parentDirectory;
        row.exists = recent.exists;
        row.enabled = recent.exists;
        row.actionName = recentProjectActionName(row.index);
        row.detailedActionName = appAction(row.actionName);
        if (!recent.exists) {
            if (!row.subtitle.empty()) {
                row.subtitle += " ";
            }
            row.subtitle += "(missing)";
            row.disabledReason = "File not found: " + recent.path;
        }
        presentation.recentProjects.push_back(std::move(row));
    }

    return presentation;
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
        const auto existing = specs.find(name);
        auto accelerators = existing == specs.end() ? std::vector<std::string>{} : existing->second.accelerators;
        specs[name] = GtkActionSpec{
            name,
            appAction(name),
            EditorCommand::None,
            panelId,
            true,
            true,
            visible,
            std::move(accelerators),
        };
    }

    for (const auto& focus : workbenchFocusActions(frameModel)) {
        specs[focus.name] = GtkActionSpec{
            focus.name,
            focus.detailedName,
            EditorCommand::None,
            focus.panelId,
            focus.enabled,
            true,
            focus.active,
            {},
        };
    }

    for (const auto& row : panelRows(frameModel)) {
        const auto name = workbenchPanelFloatActionName(row.panelId);
        specs[name] = GtkActionSpec{
            name,
            appAction(name),
            EditorCommand::None,
            row.panelId,
            row.focusEnabled,
            false,
            false,
            {},
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

std::vector<GtkActionAcceleratorSpec> EditorGtkShellModel::actionAcceleratorSpecs(
    const std::vector<GtkActionSpec>& actionSpecs) {
    std::vector<GtkActionAcceleratorSpec> result;
    for (const auto& spec : actionSpecs) {
        if (spec.accelerators.empty()) {
            continue;
        }
        result.push_back(GtkActionAcceleratorSpec{spec.detailedName, spec.accelerators});
    }
    return result;
}

std::vector<GtkActionAcceleratorSpec> EditorGtkShellModel::actionAcceleratorSpecs(
    const EditorMenuModel& menuModel,
    const EditorToolBarModel& toolbarModel,
    const EditorFramePanelModel& frameModel) {
    return actionAcceleratorSpecs(actionSpecs(menuModel, toolbarModel, frameModel));
}

std::vector<GtkMenuSpec> EditorGtkShellModel::menuSpecs(const EditorMenuModel& menuModel,
                                                        const EditorFramePanelModel& frameModel) {
    std::vector<GtkMenuSpec> result;
    result.reserve(menuModel.menus().size());
    for (const auto& menu : menuModel.menus()) {
        GtkMenuSpec spec;
        spec.label = menu.label;
        spec.mnemonic = menu.mnemonic;
        spec.items.reserve(menu.items.size());
        for (const auto& item : menu.items) {
            spec.items.push_back(menuItemSpec(item, frameModel));
        }
        result.push_back(std::move(spec));
    }
    return result;
}

std::vector<GtkToolbarItemSpec> EditorGtkShellModel::toolbarItems(const EditorToolBarModel& toolbarModel) {
    std::vector<GtkToolbarItemSpec> result;
    result.reserve(toolbarModel.items().size());
    for (const auto& item : toolbarModel.items()) {
        GtkToolbarItemSpec spec;
        spec.kind = item.kind;
        spec.label = item.label;
        spec.tooltip = item.tooltip;
        spec.iconName = toolbarIconName(item.command);
        spec.enabled = item.kind != ToolbarItem::Kind::Separator;
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
        row.toggleActionName = panelActionName(descriptor.panelId);
        row.detailedToggleActionName = appAction(row.toggleActionName);
        row.focusActionName = workbenchPanelActionName(descriptor.panelId);
        row.detailedFocusActionName = appAction(row.focusActionName);
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
        row.focusEnabled = row.visible && !row.iconified;
        if (row.focusEnabled) {
            row.primaryActionName = row.focusActionName;
            row.detailedPrimaryActionName = row.detailedFocusActionName;
            row.primaryActionEnabled = true;
        } else if (!row.visible) {
            row.primaryActionName = row.toggleActionName;
            row.detailedPrimaryActionName = row.detailedToggleActionName;
            row.primaryActionEnabled = true;
        }
        result.push_back(std::move(row));
    }
    return result;
}

std::vector<GtkWorkbenchPanelSpec> EditorGtkShellModel::workbenchPanels(const EditorFramePanelModel& frameModel,
                                                                       const EditorContextModel& contextModel) {
    const auto rows = panelRows(frameModel);
    std::vector<GtkWorkbenchPanelSpec> result;
    result.reserve(rows.size());
    for (const auto& row : rows) {
        if (!row.visible || row.iconified) {
            continue;
        }
        GtkWorkbenchPanelSpec spec;
        spec.panelId = row.panelId;
        spec.kind = panelKind(row.panelId);
        spec.title = row.title;
        spec.bounds = row.bounds;
        spec.visible = row.visible;
        spec.selected = row.selected;
        spec.docked = row.docked;
        spec.activationActionName = workbenchPanelActionName(row.panelId);
        spec.detailedActivationActionName = appAction(spec.activationActionName);
        populateWorkbenchContent(spec, contextModel);
        spec.actionSpecs = contentActionSpecs(spec.actionLabels);
        result.push_back(std::move(spec));
    }
    return result;
}

GtkWorkbenchLayoutSpec EditorGtkShellModel::workbenchLayout(const EditorFramePanelModel& frameModel,
                                                           const EditorContextModel& contextModel) {
    GtkWorkbenchLayoutSpec layout;
    layout.emptyText = "No editor panels available";
    layout.panels = workbenchPanels(frameModel, contextModel);
    auto active = std::find_if(layout.panels.begin(), layout.panels.end(), [](const GtkWorkbenchPanelSpec& panel) {
        return panel.selected;
    });
    if (active == layout.panels.end() && !layout.panels.empty()) {
        active = layout.panels.begin();
    }
    if (active != layout.panels.end()) {
        layout.activePanel = *active;
    }
    return layout;
}

std::vector<GtkWorkbenchTabSpec> EditorGtkShellModel::workbenchTabs(const EditorFramePanelModel& frameModel,
                                                                   const EditorContextModel& contextModel) {
    const auto layout = workbenchLayout(frameModel, contextModel);
    std::vector<GtkWorkbenchTabSpec> result;
    result.reserve(layout.panels.size());
    for (const auto& panel : layout.panels) {
        const bool active = layout.activePanel.has_value() && layout.activePanel->panelId == panel.panelId;
        const auto floatAction = workbenchPanelFloatActionName(panel.panelId);
        const auto toggleAction = panelActionName(panel.panelId);
        result.push_back(GtkWorkbenchTabSpec{
            panel.panelId,
            panel.kind,
            panel.title,
            active,
            active ? "Active panel" : "Select panel",
            panel.activationActionName,
            panel.detailedActivationActionName,
            "Float",
            "Float panel",
            floatAction,
            appAction(floatAction),
            "Hide",
            "Hide panel",
            toggleAction,
            appAction(toggleAction),
        });
    }
    return result;
}

GtkWorkbenchContentSpec EditorGtkShellModel::workbenchContent(const EditorFramePanelModel& frameModel,
                                                             const EditorContextModel& contextModel) {
    const auto layout = workbenchLayout(frameModel, contextModel);
    GtkWorkbenchContentSpec content;
    if (!layout.activePanel.has_value()) {
        content.title = layout.emptyText;
        content.primaryText = layout.emptyText;
        return content;
    }

    const auto& panel = *layout.activePanel;
    content.hasPanel = true;
    content.panelId = panel.panelId;
    content.kind = panel.kind;
    content.title = panel.title;
    content.primaryText = panel.primaryText;
    content.statusText = panel.statusText;
    content.actionLabels = panel.actionLabels;
    content.actionSpecs = panel.actionSpecs;
    content.focusActionName = panel.activationActionName;
    content.detailedFocusActionName = panel.detailedActivationActionName;
    return content;
}

std::vector<GtkWorkbenchFocusActionSpec> EditorGtkShellModel::workbenchFocusActions(
    const EditorFramePanelModel& frameModel) {
    const auto rows = panelRows(frameModel);
    auto active = std::find_if(rows.begin(), rows.end(), [](const GtkPanelRowSpec& row) {
        return row.visible && !row.iconified && row.selected;
    });
    if (active == rows.end()) {
        active = std::find_if(rows.begin(), rows.end(), [](const GtkPanelRowSpec& row) {
            return row.visible && !row.iconified;
        });
    }
    const std::string activePanelId = active == rows.end() ? std::string{} : active->panelId;

    std::vector<GtkWorkbenchFocusActionSpec> result;
    result.reserve(rows.size());
    for (const auto& row : rows) {
        const bool enabled = row.visible && !row.iconified;
        const auto name = workbenchPanelActionName(row.panelId);
        result.push_back(GtkWorkbenchFocusActionSpec{
            name,
            appAction(name),
            row.panelId,
            enabled,
            enabled && row.panelId == activePanelId,
        });
    }
    return result;
}

const EditorMenuModel& EditorGtkShellState::menuModel() const {
    return menuModel_;
}

const EditorToolBarModel& EditorGtkShellState::toolbarModel() const {
    return toolbarModel_;
}

const EditorFramePanelModel& EditorGtkShellState::frameModel() const {
    return frameModel_;
}

const EditorContextModel& EditorGtkShellState::contextModel() const {
    return contextModel_;
}

const std::optional<std::string>& EditorGtkShellState::openMoviePath() const {
    return contextModel_.currentPath();
}

const std::string& EditorGtkShellState::statusMessage() const {
    return statusMessage_;
}

const std::vector<ExternalParamRow>& EditorGtkShellState::externalParams() const {
    return externalParams_;
}

const std::vector<std::string>& EditorGtkShellState::traceHandlers() const {
    return traceHandlers_;
}

const PreferencesModel& EditorGtkShellState::preferences() const {
    return preferences_;
}

std::vector<GtkActionSpec> EditorGtkShellState::actionSpecs() const {
    return EditorGtkShellModel::actionSpecs(menuModel_, toolbarModel_, frameModel_);
}

std::vector<GtkActionAcceleratorSpec> EditorGtkShellState::actionAcceleratorSpecs() const {
    return EditorGtkShellModel::actionAcceleratorSpecs(actionSpecs());
}

std::vector<GtkMenuSpec> EditorGtkShellState::menuSpecs() const {
    return EditorGtkShellModel::menuSpecs(menuModel_, frameModel_);
}

std::optional<GtkActionSpec> EditorGtkShellState::actionSpec(std::string_view name) const {
    return EditorGtkShellModel::actionSpec(name, menuModel_, toolbarModel_, frameModel_);
}

std::vector<GtkToolbarItemSpec> EditorGtkShellState::toolbarItems() const {
    auto items = EditorGtkShellModel::toolbarItems(toolbarModel_);
    for (auto& item : items) {
        if (item.kind == ToolbarItem::Kind::Label) {
            item.label = "Frame: " + std::to_string(contextModel_.currentFrame());
        }
    }
    return items;
}

std::vector<GtkPanelRowSpec> EditorGtkShellState::panelRows() const {
    return EditorGtkShellModel::panelRows(frameModel_);
}

std::vector<GtkWorkbenchPanelSpec> EditorGtkShellState::workbenchPanels() const {
    return EditorGtkShellModel::workbenchPanels(frameModel_, contextModel_);
}

GtkWorkbenchLayoutSpec EditorGtkShellState::workbenchLayout() const {
    return EditorGtkShellModel::workbenchLayout(frameModel_, contextModel_);
}

std::vector<GtkWorkbenchTabSpec> EditorGtkShellState::workbenchTabs() const {
    return EditorGtkShellModel::workbenchTabs(frameModel_, contextModel_);
}

GtkWorkbenchContentSpec EditorGtkShellState::workbenchContent() const {
    return EditorGtkShellModel::workbenchContent(frameModel_, contextModel_);
}

std::vector<GtkWorkbenchFocusActionSpec> EditorGtkShellState::workbenchFocusActions() const {
    return EditorGtkShellModel::workbenchFocusActions(frameModel_);
}

GtkShellViewState EditorGtkShellState::viewState() const {
    const auto defaults = panels::EditorPanelCatalog::frameDefaults();
    const auto& currentPath = contextModel_.currentPath();
    return GtkShellViewState{
        currentPath.has_value()
            ? panels::EditorPanelCatalog::frameTitleForPath(*currentPath)
            : panels::EditorPanelCatalog::closedFrameTitle(),
        defaults.size.width,
        defaults.size.height,
        static_cast<std::uint8_t>(defaults.desktopBackgroundR),
        static_cast<std::uint8_t>(defaults.desktopBackgroundG),
        static_cast<std::uint8_t>(defaults.desktopBackgroundB),
        "Stage",
        currentPath.has_value() ? "Movie loaded: " + displayFileName(*currentPath) : "No movie loaded",
        statusMessage_,
        currentPath,
        contextModel_.isPlaying(),
        contextModel_.currentFrame(),
        actionSpecs(),
        toolbarItems(),
        panelRows(),
        workbenchPanels(),
        workbenchLayout(),
        workbenchTabs(),
        workbenchContent(),
        workbenchFocusActions(),
    };
}

GtkStartScreenRequest EditorGtkShellState::startScreen(StartScreenModel::ExistsCallback exists) const {
    return GtkStartScreenRequest{
        std::string(StartScreenModel::TITLE),
        std::string(StartScreenModel::SUBTITLE),
        std::string(StartScreenModel::EMPTY_RECENTS_MESSAGE),
        StartScreenModel::recentEntries(preferences_, std::move(exists)),
        StartScreenModel::createNewMovieEnabled(),
        EditorFramePanelModel::openFileDialog(preferences_.lastOpenDirectory()),
    };
}

void EditorGtkShellState::setPreferences(PreferencesModel preferences) {
    preferences_ = std::move(preferences);
}

void EditorGtkShellState::setOpenMoviePath(std::optional<std::string> path) {
    if (path.has_value()) {
        (void)openFile(std::move(*path));
    } else {
        (void)closeFile();
    }
}

std::vector<EditorContextEvent> EditorGtkShellState::openFile(std::string path) {
    if (const auto directory = parentDirectory(path); directory.has_value()) {
        preferences_.setLastOpenDirectory(*directory);
    }
    preferences_.addRecentProject(path);
    auto events = contextModel_.openFile(std::move(path));
    traceHandlers_.clear();
    if (const auto& movieKey = contextModel_.currentMovieKey(); movieKey.has_value()) {
        if (auto savedParams = preferences_.movieParams(*movieKey); savedParams.has_value()) {
            externalParams_ = std::move(*savedParams);
        } else if (EditorContextModel::detectLocalHttpRoot(*movieKey).has_value()) {
            externalParams_ = ExternalParamsTableModel::habboPresetRows();
        } else {
            externalParams_.clear();
        }
    } else {
        externalParams_.clear();
    }
    statusMessage_ = "Opened " + displayFileName(*contextModel_.currentPath());
    return events;
}

std::vector<EditorContextEvent> EditorGtkShellState::closeFile() {
    const auto oldPath = contextModel_.currentPath();
    auto events = contextModel_.closeFile();
    externalParams_.clear();
    traceHandlers_.clear();
    statusMessage_ = oldPath.has_value() ? "Closed " + displayFileName(*oldPath) : "No movie loaded";
    return events;
}

GtkActionActivation EditorGtkShellState::acceptOpenFile(std::string path) {
    GtkActionActivation result;
    result.actionName = "open_accept";
    result.command = EditorCommand::Open;
    result.handled = true;
    result.refreshView = true;
    result.contextEvents = openFile(std::move(path));
    result.statusMessage = statusMessage_;
    return result;
}

GtkActionActivation EditorGtkShellState::cancelOpenFile() {
    statusMessage_ = "Open cancelled";
    return GtkActionActivation{
        "open_cancel",
        EditorCommand::Open,
        {},
        true,
        false,
        false,
        false,
        false,
        true,
        std::nullopt,
        std::nullopt,
        std::nullopt,
        {},
        statusMessage_,
    };
}

GtkActionActivation EditorGtkShellState::openRecentProject(int index, StartScreenModel::ExistsCallback exists) {
    GtkActionActivation result;
    result.actionName = EditorGtkShellModel::recentProjectActionName(index);

    const auto entries = StartScreenModel::recentEntries(preferences_, std::move(exists));
    if (index < 0 || static_cast<std::size_t>(index) >= entries.size()) {
        result.statusMessage = "Recent project not available";
        statusMessage_ = result.statusMessage;
        return result;
    }
    if (!entries[static_cast<std::size_t>(index)].exists) {
        result.statusMessage = "Recent project not found: " + entries[static_cast<std::size_t>(index)].path;
        statusMessage_ = result.statusMessage;
        return result;
    }

    const auto selectedPath = StartScreenModel::selectRecentPath(entries, index);
    if (!selectedPath.has_value()) {
        result.statusMessage = "Recent project not available";
        statusMessage_ = result.statusMessage;
        return result;
    }

    result.handled = true;
    result.refreshView = true;
    result.contextEvents = openFile(*selectedPath);
    result.statusMessage = statusMessage_;
    return result;
}

GtkActionActivation EditorGtkShellState::activateRecentProjectAction(std::string_view actionName,
                                                                     StartScreenModel::ExistsCallback exists) {
    const auto index = EditorGtkShellModel::recentProjectActionIndex(actionName);
    if (!index.has_value()) {
        statusMessage_ = "Unknown recent project action: " + std::string(actionName);
        GtkActionActivation result;
        result.actionName = std::string(actionName);
        result.statusMessage = statusMessage_;
        return result;
    }

    auto result = openRecentProject(*index, std::move(exists));
    result.actionName = std::string(actionName);
    return result;
}

GtkWorkbenchPanelActivation EditorGtkShellState::floatWorkbenchPanel(std::string_view panelId) {
    GtkWorkbenchPanelActivation result;
    result.panelId = std::string(panelId);
    result.actionName = EditorGtkShellModel::workbenchPanelFloatActionName(panelId);

    if (!frameModel_.floatPanel(panelId)) {
        result.statusMessage = "Panel not available: " + result.panelId;
        statusMessage_ = result.statusMessage;
        return result;
    }

    result.handled = true;
    result.refreshActions = true;
    result.refreshPanels = true;
    result.refreshView = true;
    result.statusMessage = panelTitle(panelId) + " floated";
    statusMessage_ = result.statusMessage;

    const auto panels = workbenchPanels();
    const auto found = std::find_if(panels.begin(), panels.end(), [panelId](const GtkWorkbenchPanelSpec& panel) {
        return panel.panelId == panelId;
    });
    if (found != panels.end()) {
        result.panel = *found;
    }
    return result;
}

GtkWorkbenchPanelActivation EditorGtkShellState::activateWorkbenchPanel(std::string_view panelId) {
    GtkWorkbenchPanelActivation result;
    result.panelId = std::string(panelId);
    result.actionName = EditorGtkShellModel::workbenchPanelActionName(panelId);

    if (!frameModel_.selectPanel(panelId)) {
        result.statusMessage = "Panel not available: " + result.panelId;
        statusMessage_ = result.statusMessage;
        return result;
    }

    result.handled = true;
    result.refreshPanels = true;
    result.refreshView = true;
    result.statusMessage = panelTitle(panelId) + " selected";
    statusMessage_ = result.statusMessage;

    const auto panels = workbenchPanels();
    const auto found = std::find_if(panels.begin(), panels.end(), [panelId](const GtkWorkbenchPanelSpec& panel) {
        return panel.panelId == panelId;
    });
    if (found != panels.end()) {
        result.panel = *found;
    }
    return result;
}

GtkWorkbenchPanelActivation EditorGtkShellState::activateWorkbenchAction(std::string_view actionName) {
    for (const auto& descriptor : panels::EditorPanelCatalog::descriptors()) {
        if (EditorGtkShellModel::workbenchPanelActionName(descriptor.panelId) == actionName) {
            auto result = activateWorkbenchPanel(descriptor.panelId);
            result.actionName = std::string(actionName);
            return result;
        }
    }

    GtkWorkbenchPanelActivation result;
    result.actionName = std::string(actionName);
    result.statusMessage = "Unknown workbench action: " + result.actionName;
    statusMessage_ = result.statusMessage;
    return result;
}

GtkWorkbenchPanelActivation EditorGtkShellState::activateWorkbenchFloatAction(std::string_view actionName) {
    for (const auto& descriptor : panels::EditorPanelCatalog::descriptors()) {
        if (EditorGtkShellModel::workbenchPanelFloatActionName(descriptor.panelId) == actionName) {
            auto result = floatWorkbenchPanel(descriptor.panelId);
            result.actionName = std::string(actionName);
            return result;
        }
    }

    GtkWorkbenchPanelActivation result;
    result.actionName = std::string(actionName);
    result.statusMessage = "Unknown workbench float action: " + result.actionName;
    statusMessage_ = result.statusMessage;
    return result;
}

GtkActionActivation EditorGtkShellState::activateAction(std::string_view name) {
    GtkActionActivation result;
    result.actionName = std::string(name);

    const auto spec = actionSpec(name);
    if (!spec.has_value()) {
        result.statusMessage = "Unknown action: " + result.actionName;
        statusMessage_ = result.statusMessage;
        return result;
    }

    result.command = spec->command;
    result.panelId = spec->panelId;
    if (!spec->enabled) {
        result.statusMessage = "Action disabled: " + result.actionName;
        statusMessage_ = result.statusMessage;
        return result;
    }

    if (name.starts_with("workbench_float_")) {
        const auto activation = activateWorkbenchFloatAction(name);
        result.panelId = activation.panelId;
        result.handled = activation.handled;
        result.refreshActions = activation.refreshActions;
        result.refreshPanels = activation.refreshPanels;
        result.refreshView = activation.refreshView;
        result.statusMessage = activation.statusMessage;
        return result;
    }

    if (name.starts_with("workbench_")) {
        const auto activation = activateWorkbenchAction(name);
        result.panelId = activation.panelId;
        result.handled = activation.handled;
        result.refreshActions = activation.refreshActions;
        result.refreshPanels = activation.refreshPanels;
        result.refreshView = activation.refreshView;
        result.statusMessage = activation.statusMessage;
        return result;
    }

    if (spec->stateful && !spec->panelId.empty()) {
        const bool visible = !frameModel_.isPanelVisible(spec->panelId);
        result.handled = frameModel_.togglePanel(spec->panelId, visible);
        result.refreshActions = result.handled;
        result.refreshPanels = result.handled;
        result.refreshView = result.handled;
        result.active = frameModel_.isPanelVisible(spec->panelId);
        result.statusMessage = panelTitle(spec->panelId) + (result.active.value_or(false) ? " shown" : " hidden");
        statusMessage_ = result.statusMessage;
        return result;
    }

    switch (spec->command) {
        case EditorCommand::Open:
            result.handled = true;
            result.requestOpenFile = true;
            result.refreshView = true;
            result.openFileDialog = EditorFramePanelModel::openFileDialog(preferences_.lastOpenDirectory());
            result.statusMessage = "Open Director file requested";
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::Close:
            result.handled = true;
            result.refreshView = true;
            result.contextEvents = closeFile();
            result.statusMessage = statusMessage_;
            return result;
        case EditorCommand::ExternalParameters:
            result.handled = true;
            result.refreshView = true;
            result.dialogRequest = GtkShellDialogRequest{
                GtkShellDialogKind::ExternalParameters,
                "External Parameters",
                "Set key-value pairs accessible via externalParamValue() in Lingo scripts.",
                {},
                {},
                true,
                false,
                ExternalParamsTableModel{externalParams_},
                {},
            };
            result.statusMessage = "External parameters requested";
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::Play:
            if (auto event = contextModel_.play(); event.has_value()) {
                result.handled = true;
                result.refreshView = true;
                result.contextEvents.push_back(*event);
                result.statusMessage = "Playback started";
            } else {
                result.statusMessage = "No movie loaded";
            }
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::Stop:
            if (auto event = contextModel_.stop(); event.has_value()) {
                result.handled = true;
                result.refreshView = true;
                result.contextEvents.push_back(*event);
                result.statusMessage = "Playback stopped";
            } else {
                result.statusMessage = "No movie loaded";
            }
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::Rewind:
            result.contextEvents = contextModel_.rewind();
            if (!result.contextEvents.empty()) {
                result.handled = true;
                result.refreshView = true;
                result.statusMessage = "Rewound to frame 1";
            } else {
                result.statusMessage = "No movie loaded";
            }
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::StepForward:
            if (contextModel_.hasFile()) {
                if (auto event = contextModel_.setCurrentFrame(contextModel_.currentFrame() + 1); event.has_value()) {
                    result.contextEvents.push_back(*event);
                }
                result.handled = true;
                result.refreshView = true;
                result.statusMessage = "Stepped forward to frame " + std::to_string(contextModel_.currentFrame());
            } else {
                result.statusMessage = "No movie loaded";
            }
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::StepBackward:
            if (auto event = contextModel_.stepBackward(contextModel_.currentFrame()); event.has_value()) {
                result.handled = true;
                result.refreshView = true;
                result.contextEvents.push_back(*event);
                result.statusMessage = "Stepped backward to frame " + std::to_string(contextModel_.currentFrame());
            } else {
                result.statusMessage = contextModel_.hasFile() ? "Already at first frame" : "No movie loaded";
            }
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::DetailedStackWindow:
            result.handled = true;
            result.refreshView = true;
            result.dialogRequest = GtkShellDialogRequest{
                GtkShellDialogKind::DetailedStack,
                "Detailed Stack View",
                {},
                {},
                {},
                false,
                false,
                ExternalParamsTableModel{},
                contextModel_.isPlaying()
                    ? debug::DebugInspectionModels::detailedStackRunningView()
                    : debug::DebugInspectionModels::detailedStackInitialView(),
            };
            result.statusMessage = "Detailed stack window requested";
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::TraceHandler:
            result.handled = true;
            result.refreshView = true;
            if (!contextModel_.hasFile()) {
                result.dialogRequest = GtkShellDialogRequest{
                    GtkShellDialogKind::TraceHandler,
                    "Trace Handler",
                    "No movie loaded.",
                    {},
                    {},
                    true,
                    true,
                    ExternalParamsTableModel{},
                    {},
                };
                result.statusMessage = "No movie loaded";
            } else {
                const auto currentHandlers = joinTraceHandlers(traceHandlers_);
                result.dialogRequest = GtkShellDialogRequest{
                    GtkShellDialogKind::TraceHandler,
                    "Trace Handler",
                    currentHandlers.empty() ? "Current: (none)" : "Current: " + currentHandlers,
                    std::string(TRACE_HANDLER_PROMPT),
                    currentHandlers,
                    true,
                    false,
                    ExternalParamsTableModel{},
                    {},
                };
                result.statusMessage = "Trace handler dialog requested";
            }
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::About:
            result.handled = true;
            result.refreshView = true;
            result.dialogRequest = GtkShellDialogRequest{
                GtkShellDialogKind::About,
                "About",
                std::string(ABOUT_BODY),
                {},
                {},
                true,
                false,
                ExternalParamsTableModel{},
                {},
            };
            result.statusMessage = "About dialog requested";
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::Exit:
            result.handled = true;
            result.requestQuit = true;
            result.refreshView = true;
            result.statusMessage = "Exit requested";
            statusMessage_ = result.statusMessage;
            return result;
        case EditorCommand::ResetLayout:
            frameModel_.resetLayout();
            result.handled = true;
            result.refreshActions = true;
            result.refreshPanels = true;
            result.refreshView = true;
            result.statusMessage = "Layout reset";
            statusMessage_ = result.statusMessage;
            return result;
        default:
            result.handled = spec->command != EditorCommand::None;
            result.statusMessage = result.handled
                ? "Command activated: " + std::string(commandName(spec->command))
                : "No command mapped: " + result.actionName;
            statusMessage_ = result.statusMessage;
            return result;
    }
}

GtkShellDialogResult EditorGtkShellState::applyExternalParameters(std::vector<ExternalParamRow> rows) {
    auto sanitized = ExternalParamsTableModel{std::move(rows)}.toParams();
    const bool changed = sanitized != externalParams_;
    externalParams_ = std::move(sanitized);
    if (const auto& movieKey = contextModel_.currentMovieKey(); movieKey.has_value()) {
        preferences_.setMovieParams(*movieKey, externalParams_);
    }
    statusMessage_ = "External parameters updated";
    return GtkShellDialogResult{
        GtkShellDialogKind::ExternalParameters,
        true,
        changed,
        statusMessage_,
        externalParams_,
        {},
    };
}

GtkShellDialogResult EditorGtkShellState::applyTraceHandlerInput(std::string_view input) {
    auto handlers = EditorMenuModel::traceHandlersFromInput(input);
    const bool changed = handlers != traceHandlers_;
    traceHandlers_ = std::move(handlers);
    if (traceHandlers_.empty()) {
        statusMessage_ = "Trace handlers cleared";
    } else {
        statusMessage_ = "Trace handlers updated: " + std::to_string(traceHandlers_.size());
    }
    return GtkShellDialogResult{
        GtkShellDialogKind::TraceHandler,
        true,
        changed,
        statusMessage_,
        {},
        traceHandlers_,
    };
}

GtkShellDialogResult EditorGtkShellState::cancelDialog(GtkShellDialogKind kind) {
    statusMessage_ = dialogCancelStatus(kind);
    return GtkShellDialogResult{
        kind,
        false,
        false,
        statusMessage_,
        {},
        {},
    };
}

} // namespace libreshockwave::editor::gtk
