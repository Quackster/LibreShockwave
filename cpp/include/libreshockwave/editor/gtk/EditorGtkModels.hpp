#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/AppModels.hpp"
#include "libreshockwave/editor/EditorContextModels.hpp"
#include "libreshockwave/editor/EditorFrameModels.hpp"
#include "libreshockwave/editor/EditorShellModels.hpp"
#include "libreshockwave/editor/debug/DebugInspectionModels.hpp"
#include "libreshockwave/editor/panels/EditorPanelCatalog.hpp"

namespace libreshockwave::editor::gtk {

enum class GtkShellDialogKind {
    ExternalParameters,
    TraceHandler,
    About,
    DetailedStack
};

enum class GtkWorkbenchPanelKind {
    Stage,
    Score,
    Cast,
    PropertyInspector,
    Script,
    Message,
    ToolPalette,
    Paint,
    VectorShape,
    Text,
    Field,
    Sound,
    ColorPalettes,
    BytecodeDebugger,
    Generic
};

struct GtkShellDialogRequest {
    GtkShellDialogKind kind{GtkShellDialogKind::About};
    std::string title;
    std::string message;
    std::string prompt;
    std::string currentValue;
    bool modal{false};
    bool warning{false};
    ExternalParamsTableModel externalParams;
    debug::DetailedStackView detailedStackView;

    friend bool operator==(const GtkShellDialogRequest&, const GtkShellDialogRequest&) = default;
};

struct GtkShellDialogResult {
    GtkShellDialogKind kind{GtkShellDialogKind::About};
    bool accepted{false};
    bool changed{false};
    std::string statusMessage;
    std::vector<ExternalParamRow> externalParams;
    std::vector<std::string> traceHandlers;

    friend bool operator==(const GtkShellDialogResult&, const GtkShellDialogResult&) = default;
};

struct GtkStartScreenRequest {
    std::string title;
    std::string subtitle;
    std::string emptyRecentsMessage;
    std::vector<RecentProjectEntry> recentProjects;
    bool createNewMovieEnabled{false};
    EditorOpenFileDialogModel openFileDialog;

    friend bool operator==(const GtkStartScreenRequest&, const GtkStartScreenRequest&) = default;
};

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
    std::string toggleActionName;
    std::string detailedToggleActionName;
    std::string focusActionName;
    std::string detailedFocusActionName;
    bool focusEnabled{false};

    friend bool operator==(const GtkPanelRowSpec&, const GtkPanelRowSpec&) = default;
};

struct GtkWorkbenchFocusActionSpec {
    std::string name;
    std::string detailedName;
    std::string panelId;
    bool enabled{false};
    bool active{false};

    friend bool operator==(const GtkWorkbenchFocusActionSpec&, const GtkWorkbenchFocusActionSpec&) = default;
};

struct GtkWorkbenchPanelSpec {
    std::string panelId;
    GtkWorkbenchPanelKind kind{GtkWorkbenchPanelKind::Generic};
    std::string title;
    panels::PanelBounds bounds;
    bool visible{false};
    bool selected{false};
    bool docked{false};
    std::string activationActionName;
    std::string detailedActivationActionName;
    std::string primaryText;
    std::string statusText;
    std::vector<std::string> actionLabels;

    friend bool operator==(const GtkWorkbenchPanelSpec&, const GtkWorkbenchPanelSpec&) = default;
};

struct GtkWorkbenchContentSpec {
    bool hasPanel{false};
    std::string panelId;
    GtkWorkbenchPanelKind kind{GtkWorkbenchPanelKind::Generic};
    std::string title;
    std::string primaryText;
    std::string statusText;
    std::vector<std::string> actionLabels;
    std::string focusActionName;
    std::string detailedFocusActionName;

    friend bool operator==(const GtkWorkbenchContentSpec&, const GtkWorkbenchContentSpec&) = default;
};

struct GtkWorkbenchTabSpec {
    std::string panelId;
    GtkWorkbenchPanelKind kind{GtkWorkbenchPanelKind::Generic};
    std::string title;
    bool active{false};
    std::string focusActionName;
    std::string detailedFocusActionName;
    std::string floatActionName;
    std::string detailedFloatActionName;
    std::string toggleActionName;
    std::string detailedToggleActionName;

    friend bool operator==(const GtkWorkbenchTabSpec&, const GtkWorkbenchTabSpec&) = default;
};

struct GtkWorkbenchLayoutSpec {
    std::vector<GtkWorkbenchPanelSpec> panels;
    std::optional<GtkWorkbenchPanelSpec> activePanel;
    std::string emptyText;

    friend bool operator==(const GtkWorkbenchLayoutSpec&, const GtkWorkbenchLayoutSpec&) = default;
};

struct GtkWorkbenchPanelActivation {
    std::string actionName;
    std::string panelId;
    bool handled{false};
    bool refreshActions{false};
    bool refreshPanels{false};
    bool refreshView{false};
    std::string statusMessage;
    std::optional<GtkWorkbenchPanelSpec> panel;

    friend bool operator==(const GtkWorkbenchPanelActivation&, const GtkWorkbenchPanelActivation&) = default;
};

struct GtkActionActivation {
    std::string actionName;
    EditorCommand command{EditorCommand::None};
    std::string panelId;
    bool handled{false};
    bool requestQuit{false};
    bool requestOpenFile{false};
    bool refreshActions{false};
    bool refreshPanels{false};
    bool refreshView{false};
    std::optional<bool> active;
    std::optional<EditorOpenFileDialogModel> openFileDialog;
    std::optional<GtkShellDialogRequest> dialogRequest;
    std::vector<EditorContextEvent> contextEvents;
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
    std::optional<std::string> openMoviePath;
    bool playing{false};
    int currentFrame{1};
    std::vector<GtkActionSpec> actionSpecs;
    std::vector<GtkToolbarItemSpec> toolbarItems;
    std::vector<GtkPanelRowSpec> panelRows;
    std::vector<GtkWorkbenchPanelSpec> workbenchPanels;
    GtkWorkbenchLayoutSpec workbenchLayout;
    std::vector<GtkWorkbenchTabSpec> workbenchTabs;
    GtkWorkbenchContentSpec workbenchContent;
    std::vector<GtkWorkbenchFocusActionSpec> workbenchFocusActions;

    friend bool operator==(const GtkShellViewState&, const GtkShellViewState&) = default;
};

class EditorGtkShellModel {
public:
    [[nodiscard]] static std::string sanitizeActionName(std::string_view value);
    [[nodiscard]] static std::string commandActionName(EditorCommand command);
    [[nodiscard]] static std::string panelActionName(std::string_view panelId);
    [[nodiscard]] static std::string workbenchPanelActionName(std::string_view panelId);
    [[nodiscard]] static std::string workbenchPanelFloatActionName(std::string_view panelId);
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
    [[nodiscard]] static std::vector<GtkWorkbenchPanelSpec> workbenchPanels(const EditorFramePanelModel& frameModel,
                                                                           const EditorContextModel& contextModel);
    [[nodiscard]] static GtkWorkbenchLayoutSpec workbenchLayout(const EditorFramePanelModel& frameModel,
                                                               const EditorContextModel& contextModel);
    [[nodiscard]] static std::vector<GtkWorkbenchTabSpec> workbenchTabs(const EditorFramePanelModel& frameModel,
                                                                       const EditorContextModel& contextModel);
    [[nodiscard]] static GtkWorkbenchContentSpec workbenchContent(const EditorFramePanelModel& frameModel,
                                                                 const EditorContextModel& contextModel);
    [[nodiscard]] static std::vector<GtkWorkbenchFocusActionSpec> workbenchFocusActions(
        const EditorFramePanelModel& frameModel);
};

class EditorGtkShellState {
public:
    [[nodiscard]] const EditorMenuModel& menuModel() const;
    [[nodiscard]] const EditorToolBarModel& toolbarModel() const;
    [[nodiscard]] const EditorFramePanelModel& frameModel() const;
    [[nodiscard]] const EditorContextModel& contextModel() const;
    [[nodiscard]] const std::optional<std::string>& openMoviePath() const;
    [[nodiscard]] const std::string& statusMessage() const;
    [[nodiscard]] const std::vector<ExternalParamRow>& externalParams() const;
    [[nodiscard]] const std::vector<std::string>& traceHandlers() const;
    [[nodiscard]] const PreferencesModel& preferences() const;

    [[nodiscard]] std::vector<GtkActionSpec> actionSpecs() const;
    [[nodiscard]] std::optional<GtkActionSpec> actionSpec(std::string_view name) const;
    [[nodiscard]] std::vector<GtkToolbarItemSpec> toolbarItems() const;
    [[nodiscard]] std::vector<GtkPanelRowSpec> panelRows() const;
    [[nodiscard]] std::vector<GtkWorkbenchPanelSpec> workbenchPanels() const;
    [[nodiscard]] GtkWorkbenchLayoutSpec workbenchLayout() const;
    [[nodiscard]] std::vector<GtkWorkbenchTabSpec> workbenchTabs() const;
    [[nodiscard]] GtkWorkbenchContentSpec workbenchContent() const;
    [[nodiscard]] std::vector<GtkWorkbenchFocusActionSpec> workbenchFocusActions() const;
    [[nodiscard]] GtkShellViewState viewState() const;
    [[nodiscard]] GtkStartScreenRequest startScreen(StartScreenModel::ExistsCallback exists) const;

    void setPreferences(PreferencesModel preferences);
    void setOpenMoviePath(std::optional<std::string> path);
    std::vector<EditorContextEvent> openFile(std::string path);
    std::vector<EditorContextEvent> closeFile();
    GtkActionActivation openRecentProject(int index, StartScreenModel::ExistsCallback exists);
    GtkWorkbenchPanelActivation floatWorkbenchPanel(std::string_view panelId);
    GtkWorkbenchPanelActivation activateWorkbenchPanel(std::string_view panelId);
    GtkWorkbenchPanelActivation activateWorkbenchAction(std::string_view actionName);
    GtkWorkbenchPanelActivation activateWorkbenchFloatAction(std::string_view actionName);
    GtkActionActivation activateAction(std::string_view name);
    GtkShellDialogResult applyExternalParameters(std::vector<ExternalParamRow> rows);
    GtkShellDialogResult applyTraceHandlerInput(std::string_view input);

private:
    EditorMenuModel menuModel_;
    EditorToolBarModel toolbarModel_;
    EditorFramePanelModel frameModel_;
    EditorContextModel contextModel_;
    PreferencesModel preferences_;
    std::string statusMessage_;
    std::vector<ExternalParamRow> externalParams_;
    std::vector<std::string> traceHandlers_;
};

} // namespace libreshockwave::editor::gtk
