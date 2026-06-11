#include "libreshockwave/editor/gtk/EditorGtkModels.hpp"

#include <algorithm>
#include <cctype>
#include <map>
#include <optional>
#include <string>
#include <utility>

#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::editor::gtk {
namespace {

constexpr std::string_view TRACE_HANDLER_PROMPT =
    "Enter handler names to trace (comma-separated), or clear to remove all:";
constexpr std::string_view ABOUT_BODY =
    "LibreShockwave Editor\n\n"
    "A recreation of Macromedia Director MX 2004.\n\n"
    "Part of the LibreShockwave project.";

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

} // namespace libreshockwave::editor::gtk
