#include <cmath>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "libreshockwave/editor/EditorShellModels.hpp"
#include "libreshockwave/editor/gtk/EditorGtkModels.hpp"

#include <gtk/gtk.h>

namespace {

namespace editor = libreshockwave::editor;
namespace docking = libreshockwave::editor::docking;
namespace gtk_models = libreshockwave::editor::gtk;

struct EditorGtkState {
    gtk_models::EditorGtkShellState shellState;
    GtkApplication* app{nullptr};
    GtkWindow* window{nullptr};
    GtkWidget* panelList{nullptr};
    GtkWidget* workbenchArea{nullptr};
    GtkWidget* workbenchTabs{nullptr};
    GtkWidget* workbenchDockArea{nullptr};
    GtkWidget* workbenchFloatingArea{nullptr};
    GtkWidget* workbenchTitleLabel{nullptr};
    GtkWidget* workbenchPrimaryLabel{nullptr};
    GtkWidget* workbenchStatusLabel{nullptr};
    GtkWidget* workbenchActions{nullptr};
    GtkWidget* statusLabel{nullptr};
};

void refreshGtkShell(EditorGtkState& state);

void appendMenuItems(GMenu* menu, const std::vector<gtk_models::GtkMenuItemSpec>& items) {
    GMenu* section = g_menu_new();
    bool hasSectionItems = false;
    auto flushSection = [&]() {
        if (!hasSectionItems) {
            return;
        }
        g_menu_append_section(menu, nullptr, G_MENU_MODEL(section));
        g_object_unref(section);
        section = g_menu_new();
        hasSectionItems = false;
    };

    for (const auto& item : items) {
        if (item.kind == gtk_models::GtkMenuItemKind::Separator) {
            flushSection();
            continue;
        }

        if (item.kind == gtk_models::GtkMenuItemKind::Submenu) {
            GMenu* child = g_menu_new();
            appendMenuItems(child, item.children);
            g_menu_append_submenu(section, item.label.c_str(), G_MENU_MODEL(child));
            g_object_unref(child);
            hasSectionItems = true;
            continue;
        }

        g_menu_append(section,
                      item.label.c_str(),
                      item.detailedActionName.empty() ? nullptr : item.detailedActionName.c_str());
        hasSectionItems = true;
    }
    flushSection();
    g_object_unref(section);
}

GMenu* buildMenuModel(const std::vector<gtk_models::GtkMenuSpec>& menuSpecs) {
    GMenu* menuBar = g_menu_new();
    for (const auto& menu : menuSpecs) {
        GMenu* submenu = g_menu_new();
        appendMenuItems(submenu, menu.items);
        g_menu_append_submenu(menuBar, menu.label.c_str(), G_MENU_MODEL(submenu));
        g_object_unref(submenu);
    }
    return menuBar;
}

GMenu* buildPanelContextMenuModel(const gtk_models::GtkPanelContextMenuSpec& spec) {
    GMenu* menu = g_menu_new();
    for (const auto& item : spec.items) {
        if (item.detailedActionName.empty()) {
            continue;
        }
        g_menu_append(menu, item.label.c_str(), item.detailedActionName.c_str());
    }
    return menu;
}

struct PanelContextMenuData {
    GtkWidget* popover{nullptr};
};

void destroyPanelContextMenuData(gpointer userData, GClosure*) {
    auto* data = static_cast<PanelContextMenuData*>(userData);
    if (data->popover != nullptr) {
        if (gtk_widget_get_parent(data->popover) != nullptr) {
            gtk_widget_unparent(data->popover);
        }
        g_object_unref(data->popover);
    }
    delete data;
}

void showPanelContextMenu(GtkGestureClick*, int, double x, double y, gpointer userData) {
    auto* data = static_cast<PanelContextMenuData*>(userData);
    if (data->popover == nullptr) {
        return;
    }

    const GdkRectangle point{static_cast<int>(x), static_cast<int>(y), 1, 1};
    gtk_popover_set_pointing_to(GTK_POPOVER(data->popover), &point);
    gtk_popover_popup(GTK_POPOVER(data->popover));
}

void addPanelContextMenu(GtkWidget* target, const gtk_models::GtkPanelContextMenuSpec& spec) {
    GMenu* menu = buildPanelContextMenuModel(spec);
    GtkWidget* popover = gtk_popover_menu_new_from_model(G_MENU_MODEL(menu));
    g_object_unref(menu);
    gtk_popover_set_autohide(GTK_POPOVER(popover), TRUE);
    gtk_popover_set_has_arrow(GTK_POPOVER(popover), TRUE);
    gtk_popover_set_position(GTK_POPOVER(popover), GTK_POS_BOTTOM);
    gtk_widget_set_parent(popover, target);
    g_object_ref(popover);

    auto* data = new PanelContextMenuData{popover};
    GtkGesture* gesture = gtk_gesture_click_new();
    gtk_gesture_single_set_button(GTK_GESTURE_SINGLE(gesture), GDK_BUTTON_SECONDARY);
    g_signal_connect_data(gesture,
                          "pressed",
                          G_CALLBACK(showPanelContextMenu),
                          data,
                          destroyPanelContextMenuData,
                          static_cast<GConnectFlags>(0));
    gtk_widget_add_controller(target, GTK_EVENT_CONTROLLER(gesture));
}

struct PanelDragData {
    EditorGtkState* state{nullptr};
    std::string panelId;
};

void destroyPanelDragData(gpointer userData, GClosure*) {
    delete static_cast<PanelDragData*>(userData);
}

void activateDockAction(EditorGtkState& state, std::string_view panelId, docking::DockEdge edge) {
    if (state.app == nullptr) {
        return;
    }
    const auto actionName = gtk_models::EditorGtkShellModel::workbenchPanelDockActionName(panelId, edge);
    GAction* action = g_action_map_lookup_action(G_ACTION_MAP(state.app), actionName.c_str());
    if (action == nullptr || !g_action_get_enabled(action)) {
        return;
    }
    g_action_activate(action, nullptr);
}

void snapPanelDragToEdge(GtkGestureDrag* gesture, double offsetX, double offsetY, gpointer userData) {
    auto* data = static_cast<PanelDragData*>(userData);
    if (data == nullptr || data->state == nullptr || data->state->workbenchArea == nullptr) {
        return;
    }

    GtkWidget* source = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(gesture));
    if (source == nullptr) {
        return;
    }

    double startX = 0.0;
    double startY = 0.0;
    if (!gtk_gesture_drag_get_start_point(gesture, &startX, &startY)) {
        return;
    }

    const graphene_point_t sourcePoint{static_cast<float>(startX + offsetX),
                                       static_cast<float>(startY + offsetY)};
    graphene_point_t workbenchPoint{0.0F, 0.0F};
    if (!gtk_widget_compute_point(source, data->state->workbenchArea, &sourcePoint, &workbenchPoint)) {
        return;
    }

    const auto edge = gtk_models::EditorGtkShellModel::workbenchSnapEdgeForPoint(
        workbenchPoint.x,
        workbenchPoint.y,
        gtk_widget_get_width(data->state->workbenchArea),
        gtk_widget_get_height(data->state->workbenchArea));
    if (edge.has_value()) {
        activateDockAction(*data->state, data->panelId, *edge);
        return;
    }

    const auto deltaX = static_cast<int>(std::lround(offsetX));
    const auto deltaY = static_cast<int>(std::lround(offsetY));
    if (deltaX == 0 && deltaY == 0) {
        return;
    }

    const auto moved = data->state->shellState.moveFloatingPanel(data->panelId, deltaX, deltaY);
    if (moved.handled) {
        refreshGtkShell(*data->state);
    }
}

void addPanelDragSnap(GtkWidget* target, EditorGtkState& state, std::string panelId) {
    auto* data = new PanelDragData{&state, std::move(panelId)};
    GtkGesture* gesture = gtk_gesture_drag_new();
    gtk_gesture_single_set_button(GTK_GESTURE_SINGLE(gesture), GDK_BUTTON_PRIMARY);
    g_signal_connect_data(gesture,
                          "drag-end",
                          G_CALLBACK(snapPanelDragToEdge),
                          data,
                          destroyPanelDragData,
                          static_cast<GConnectFlags>(0));
    gtk_widget_add_controller(target, GTK_EVENT_CONTROLLER(gesture));
}

void populatePanelList(EditorGtkState& state,
                       GtkWidget* list,
                       const std::vector<gtk_models::GtkPanelRowSpec>& rows) {
    while (GtkWidget* child = gtk_widget_get_first_child(list)) {
        gtk_list_box_remove(GTK_LIST_BOX(list), child);
    }

    for (const auto& row : rows) {
        GtkWidget* button = gtk_button_new_with_label(row.displayLabel.c_str());
        gtk_widget_set_sensitive(button, row.primaryActionEnabled);
        if (row.primaryActionEnabled && !row.detailedPrimaryActionName.empty()) {
            gtk_actionable_set_action_name(GTK_ACTIONABLE(button), row.detailedPrimaryActionName.c_str());
        }
        GtkWidget* label = gtk_button_get_child(GTK_BUTTON(button));
        gtk_label_set_xalign(GTK_LABEL(label), 0.0F);
        gtk_widget_set_margin_start(button, 4);
        gtk_widget_set_margin_end(button, 4);
        gtk_widget_set_margin_top(button, 2);
        gtk_widget_set_margin_bottom(button, 2);
        addPanelContextMenu(button, gtk_models::EditorGtkShellModel::panelContextMenu(row));
        addPanelDragSnap(button, state, row.panelId);
        gtk_list_box_append(GTK_LIST_BOX(list), button);
    }
}

void clearBox(GtkWidget* box) {
    while (GtkWidget* child = gtk_widget_get_first_child(box)) {
        gtk_box_remove(GTK_BOX(box), child);
    }
}

GtkWidget* actionButton(const char* label, const std::string& detailedActionName, bool enabled = true) {
    GtkWidget* button = gtk_button_new_with_label(label);
    gtk_widget_set_sensitive(button, enabled && !detailedActionName.empty());
    if (!detailedActionName.empty()) {
        gtk_actionable_set_action_name(GTK_ACTIONABLE(button), detailedActionName.c_str());
    }
    return button;
}

void populateWorkbenchTabs(EditorGtkState& state,
                           GtkWidget* tabsBox,
                           const std::vector<gtk_models::GtkWorkbenchTabSpec>& tabs) {
    clearBox(tabsBox);

    for (const auto& tab : tabs) {
        GtkWidget* tabBox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 2);
        const auto contextMenu = gtk_models::EditorGtkShellModel::workbenchTabContextMenu(tab);
        addPanelContextMenu(tabBox, contextMenu);
        addPanelDragSnap(tabBox, state, tab.panelId);

        GtkWidget* focusButton = actionButton(tab.title.c_str(), tab.detailedFocusActionName);
        gtk_widget_set_tooltip_text(focusButton, tab.focusTooltip.c_str());
        if (tab.active) {
            gtk_widget_add_css_class(focusButton, "suggested-action");
        }
        addPanelContextMenu(focusButton, contextMenu);
        addPanelDragSnap(focusButton, state, tab.panelId);
        gtk_box_append(GTK_BOX(tabBox), focusButton);

        GtkWidget* floatButton = actionButton(tab.floatLabel.c_str(), tab.detailedFloatActionName);
        gtk_widget_set_tooltip_text(floatButton, tab.floatTooltip.c_str());
        addPanelContextMenu(floatButton, contextMenu);
        gtk_box_append(GTK_BOX(tabBox), floatButton);

        GtkWidget* hideButton = actionButton(tab.hideLabel.c_str(), tab.detailedToggleActionName);
        gtk_widget_set_tooltip_text(hideButton, tab.hideTooltip.c_str());
        addPanelContextMenu(hideButton, contextMenu);
        gtk_box_append(GTK_BOX(tabBox), hideButton);

        gtk_box_append(GTK_BOX(tabsBox), tabBox);
    }
}

void populateWorkbenchActions(GtkWidget* actionsBox,
                              const std::vector<gtk_models::GtkWorkbenchContentActionSpec>& actions) {
    clearBox(actionsBox);

    for (const auto& action : actions) {
        GtkWidget* button = gtk_button_new_with_label(action.label.c_str());
        gtk_widget_set_sensitive(button, action.enabled);
        gtk_widget_set_tooltip_text(button, action.tooltip.c_str());
        if (!action.detailedActionName.empty()) {
            gtk_actionable_set_action_name(GTK_ACTIONABLE(button), action.detailedActionName.c_str());
        }
        gtk_box_append(GTK_BOX(actionsBox), button);
    }
}

gtk_models::GtkWorkbenchTabSpec tabSpecForPanel(const gtk_models::GtkWorkbenchPanelSpec& panel, bool active) {
    const auto floatAction = gtk_models::EditorGtkShellModel::workbenchPanelFloatActionName(panel.panelId);
    const auto toggleAction = gtk_models::EditorGtkShellModel::panelActionName(panel.panelId);
    return gtk_models::GtkWorkbenchTabSpec{
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
        gtk_models::EditorGtkShellModel::appAction(floatAction),
        "Hide",
        "Hide panel",
        toggleAction,
        gtk_models::EditorGtkShellModel::appAction(toggleAction),
    };
}

std::vector<gtk_models::GtkWorkbenchTabSpec> tabSpecsForDockNode(
    const gtk_models::GtkWorkbenchDockNodeSpec& node) {
    std::vector<gtk_models::GtkWorkbenchTabSpec> tabs;
    tabs.reserve(node.panels.size());
    const std::string activePanelId = node.activePanel.has_value() ? node.activePanel->panelId : std::string{};
    for (const auto& panel : node.panels) {
        tabs.push_back(tabSpecForPanel(panel, panel.panelId == activePanelId));
    }
    return tabs;
}

GtkWidget* makeWorkbenchPanelContent(EditorGtkState& state, const gtk_models::GtkWorkbenchPanelSpec& panel) {
    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_widget_set_margin_start(box, 6);
    gtk_widget_set_margin_end(box, 6);
    gtk_widget_set_margin_top(box, 6);
    gtk_widget_set_margin_bottom(box, 6);
    const auto contextMenu =
        gtk_models::EditorGtkShellModel::workbenchTabContextMenu(tabSpecForPanel(panel, panel.selected));
    addPanelContextMenu(box, contextMenu);
    addPanelDragSnap(box, state, panel.panelId);

    GtkWidget* title = gtk_label_new(panel.title.c_str());
    gtk_label_set_xalign(GTK_LABEL(title), 0.0F);
    gtk_box_append(GTK_BOX(box), title);

    GtkWidget* primary = gtk_label_new(panel.primaryText.c_str());
    gtk_label_set_xalign(GTK_LABEL(primary), 0.0F);
    gtk_label_set_yalign(GTK_LABEL(primary), 0.0F);
    gtk_label_set_wrap(GTK_LABEL(primary), TRUE);
    gtk_widget_set_hexpand(primary, TRUE);
    gtk_widget_set_vexpand(primary, TRUE);
    gtk_box_append(GTK_BOX(box), primary);

    GtkWidget* actions = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    populateWorkbenchActions(actions, panel.actionSpecs);
    gtk_box_append(GTK_BOX(box), actions);

    GtkWidget* status = gtk_label_new(panel.statusText.c_str());
    gtk_label_set_xalign(GTK_LABEL(status), 0.0F);
    gtk_box_append(GTK_BOX(box), status);
    return box;
}

GtkWidget* makeDockNodeWidget(EditorGtkState& state, const gtk_models::GtkWorkbenchDockNodeSpec& node) {
    if (node.kind == docking::DockNodeKind::Split && node.children.size() >= 2) {
        GtkWidget* paned = gtk_paned_new(node.orientation == docking::DockOrientation::Horizontal
                                             ? GTK_ORIENTATION_HORIZONTAL
                                             : GTK_ORIENTATION_VERTICAL);
        GtkWidget* first = makeDockNodeWidget(state, node.children[0]);
        GtkWidget* second = makeDockNodeWidget(state, node.children[1]);
        gtk_paned_set_start_child(GTK_PANED(paned), first);
        gtk_paned_set_end_child(GTK_PANED(paned), second);
        gtk_widget_set_hexpand(paned, TRUE);
        gtk_widget_set_vexpand(paned, TRUE);
        return paned;
    }

    GtkWidget* box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 4);
    gtk_widget_set_hexpand(box, TRUE);
    gtk_widget_set_vexpand(box, TRUE);

    const auto tabs = tabSpecsForDockNode(node);
    GtkWidget* tabsBox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    populateWorkbenchTabs(state, tabsBox, tabs);
    gtk_box_append(GTK_BOX(box), tabsBox);

    if (node.activePanel.has_value()) {
        gtk_box_append(GTK_BOX(box), makeWorkbenchPanelContent(state, *node.activePanel));
    } else {
        GtkWidget* empty = gtk_label_new("No docked panels");
        gtk_label_set_xalign(GTK_LABEL(empty), 0.0F);
        gtk_widget_set_hexpand(empty, TRUE);
        gtk_widget_set_vexpand(empty, TRUE);
        gtk_box_append(GTK_BOX(box), empty);
    }
    return box;
}

void populateWorkbenchDockArea(EditorGtkState& state,
                               GtkWidget* dockArea,
                               const gtk_models::GtkWorkbenchLayoutSpec& layout) {
    clearBox(dockArea);
    gtk_widget_set_visible(dockArea, layout.hasDockedLayout);
    if (!layout.hasDockedLayout) {
        return;
    }
    GtkWidget* root = makeDockNodeWidget(state, layout.dockRoot);
    gtk_widget_set_hexpand(root, TRUE);
    gtk_widget_set_vexpand(root, TRUE);
    gtk_box_append(GTK_BOX(dockArea), root);
}

void populateWorkbenchFloatingArea(EditorGtkState& state,
                                   GtkWidget* floatingArea,
                                   const gtk_models::GtkWorkbenchLayoutSpec& layout) {
    clearBox(floatingArea);
    const bool hasFloatingPanels = !layout.floatingPanels.empty();
    gtk_widget_set_visible(floatingArea, hasFloatingPanels);
    if (!hasFloatingPanels) {
        return;
    }

    for (const auto& panel : layout.floatingPanels) {
        GtkWidget* frame = gtk_frame_new(panel.title.c_str());
        gtk_widget_set_size_request(frame,
                                    panel.bounds.width > 0 ? panel.bounds.width : -1,
                                    panel.bounds.height > 0 ? panel.bounds.height : -1);
        gtk_widget_set_hexpand(frame, TRUE);
        gtk_frame_set_child(GTK_FRAME(frame), makeWorkbenchPanelContent(state, panel));
        gtk_box_append(GTK_BOX(floatingArea), frame);
    }
}

void refreshGtkActions(GtkApplication* app, const std::vector<gtk_models::GtkActionSpec>& specs) {
    for (const auto& spec : specs) {
        GAction* action = g_action_map_lookup_action(G_ACTION_MAP(app), spec.name.c_str());
        if (action == nullptr) {
            continue;
        }
        auto* simpleAction = G_SIMPLE_ACTION(action);
        g_simple_action_set_enabled(simpleAction, spec.enabled);
        if (spec.stateful) {
            g_simple_action_set_state(simpleAction, g_variant_new_boolean(spec.active));
        }
    }
}

void installActionAccelerators(GtkApplication* app,
                               const std::vector<gtk_models::GtkActionAcceleratorSpec>& specs) {
    for (const auto& spec : specs) {
        std::vector<const char*> accelerators;
        accelerators.reserve(spec.accelerators.size() + 1);
        for (const auto& accelerator : spec.accelerators) {
            accelerators.push_back(accelerator.c_str());
        }
        accelerators.push_back(nullptr);
        gtk_application_set_accels_for_action(app, spec.detailedActionName.c_str(), accelerators.data());
    }
}

void refreshGtkShell(EditorGtkState& state) {
    const auto view = state.shellState.viewState();
    if (state.app != nullptr) {
        refreshGtkActions(state.app, view.actionSpecs);
    }
    if (state.window != nullptr) {
        gtk_window_set_title(state.window, view.windowTitle.c_str());
    }
    if (state.panelList != nullptr) {
        populatePanelList(state, state.panelList, view.panelRows);
    }
    if (state.workbenchTabs != nullptr) {
        populateWorkbenchTabs(state, state.workbenchTabs, view.workbenchTabs);
    }
    if (state.workbenchDockArea != nullptr) {
        populateWorkbenchDockArea(state, state.workbenchDockArea, view.workbenchLayout);
    }
    if (state.workbenchFloatingArea != nullptr) {
        populateWorkbenchFloatingArea(state, state.workbenchFloatingArea, view.workbenchLayout);
    }
    const bool showFallbackContent =
        !view.workbenchLayout.hasDockedLayout && view.workbenchLayout.floatingPanels.empty();
    if (state.workbenchTitleLabel != nullptr) {
        gtk_widget_set_visible(state.workbenchTitleLabel, showFallbackContent);
        gtk_label_set_text(GTK_LABEL(state.workbenchTitleLabel), view.workbenchContent.title.c_str());
    }
    if (state.workbenchPrimaryLabel != nullptr) {
        gtk_widget_set_visible(state.workbenchPrimaryLabel, showFallbackContent);
        gtk_label_set_text(GTK_LABEL(state.workbenchPrimaryLabel), view.workbenchContent.primaryText.c_str());
    }
    if (state.workbenchStatusLabel != nullptr) {
        gtk_widget_set_visible(state.workbenchStatusLabel, showFallbackContent);
        gtk_label_set_text(GTK_LABEL(state.workbenchStatusLabel), view.workbenchContent.statusText.c_str());
    }
    if (state.workbenchActions != nullptr) {
        gtk_widget_set_visible(state.workbenchActions, showFallbackContent);
        populateWorkbenchActions(state.workbenchActions, view.workbenchContent.actionSpecs);
    }
    if (state.statusLabel != nullptr) {
        gtk_label_set_text(GTK_LABEL(state.statusLabel), view.statusMessage.c_str());
    }
}

void openFileDialogFinished(GObject* sourceObject, GAsyncResult* result, gpointer userData) {
    auto* state = static_cast<EditorGtkState*>(userData);
    GError* error = nullptr;
    GFile* file = gtk_file_dialog_open_finish(GTK_FILE_DIALOG(sourceObject), result, &error);

    gtk_models::GtkActionActivation activation;
    if (file == nullptr) {
        if (error != nullptr && !g_error_matches(error, GTK_DIALOG_ERROR, GTK_DIALOG_ERROR_DISMISSED)) {
            g_print("LibreShockwave GTK editor open dialog failed: %s\n", error->message);
        }
        activation = state->shellState.activateOpenFileDialogAction("open_cancel", std::nullopt);
        g_clear_error(&error);
    } else {
        char* path = g_file_get_path(file);
        if (path != nullptr) {
            activation = state->shellState.activateOpenFileDialogAction(
                "open_accept",
                std::optional<std::string>{path});
            g_free(path);
        } else {
            activation = state->shellState.activateOpenFileDialogAction("open_accept", std::nullopt);
        }
        g_object_unref(file);
    }

    if (!activation.statusMessage.empty()) {
        g_print("LibreShockwave GTK editor: %s\n", activation.statusMessage.c_str());
    }
    refreshGtkShell(*state);
}

void presentOpenFileDialog(EditorGtkState& state, const editor::EditorOpenFileDialogModel& request) {
    const auto presentation = gtk_models::EditorGtkShellModel::openFileDialogPresentation(request);
    GtkFileDialog* dialog = gtk_file_dialog_new();
    gtk_file_dialog_set_title(dialog, presentation.title.c_str());
    gtk_file_dialog_set_modal(dialog, presentation.modal);
    gtk_file_dialog_set_accept_label(dialog, presentation.acceptLabel.c_str());

    if (presentation.currentDirectory.has_value()) {
        GFile* folder = g_file_new_for_path(presentation.currentDirectory->c_str());
        gtk_file_dialog_set_initial_folder(dialog, folder);
        g_object_unref(folder);
    }

    if (!presentation.filter.extensions.empty()) {
        GListStore* filters = g_list_store_new(GTK_TYPE_FILE_FILTER);
        GtkFileFilter* directorFilter = gtk_file_filter_new();
        gtk_file_filter_set_name(directorFilter, presentation.filter.label.c_str());
        for (const auto& extension : presentation.filter.extensions) {
            gtk_file_filter_add_suffix(directorFilter, extension.c_str());
        }
        g_list_store_append(filters, directorFilter);
        gtk_file_dialog_set_filters(dialog, G_LIST_MODEL(filters));
        gtk_file_dialog_set_default_filter(dialog, directorFilter);
        g_object_unref(directorFilter);
        g_object_unref(filters);
    }

    gtk_file_dialog_open(dialog, state.window, nullptr, openFileDialogFinished, &state);
    g_object_unref(dialog);
}

gtk_models::GtkActionActivation activateGtkAction(GSimpleAction* action, EditorGtkState* state) {
    const char* name = g_action_get_name(G_ACTION(action));
    auto result = state->shellState.activateAction(name != nullptr ? name : "");
    if (result.requestOpenFile && result.openFileDialog.has_value()) {
        presentOpenFileDialog(*state, *result.openFileDialog);
    }
    if (result.dialogRequest.has_value()) {
        g_print("LibreShockwave GTK editor dialog: %s\n", result.dialogRequest->title.c_str());
    }
    if (!result.statusMessage.empty()) {
        g_print("LibreShockwave GTK editor: %s\n", result.statusMessage.c_str());
    }
    refreshGtkShell(*state);
    if (result.requestQuit && state->app != nullptr) {
        g_application_quit(G_APPLICATION(state->app));
    }
    return result;
}

void commandActivated(GSimpleAction* action, GVariant*, gpointer userData) {
    (void)activateGtkAction(action, static_cast<EditorGtkState*>(userData));
}

void panelActionActivated(GSimpleAction* action, GVariant*, gpointer userData) {
    (void)activateGtkAction(action, static_cast<EditorGtkState*>(userData));
}

void installActions(GtkApplication* app, EditorGtkState& state) {
    for (const auto& spec : state.shellState.actionSpecs()) {
        GSimpleAction* action = spec.stateful
            ? g_simple_action_new_stateful(spec.name.c_str(), nullptr, g_variant_new_boolean(spec.active))
            : g_simple_action_new(spec.name.c_str(), nullptr);
        g_simple_action_set_enabled(action, spec.enabled);
        g_signal_connect(action,
                         "activate",
                         spec.stateful ? G_CALLBACK(panelActionActivated) : G_CALLBACK(commandActivated),
                         &state);
        g_action_map_add_action(G_ACTION_MAP(app), G_ACTION(action));
        g_object_unref(action);
    }
    installActionAccelerators(app, state.shellState.actionAcceleratorSpecs());
}

GtkWidget* makeMenuBar(const gtk_models::EditorGtkShellState& shellState) {
    GMenu* model = buildMenuModel(shellState.menuSpecs());
    GtkWidget* menuBar = gtk_popover_menu_bar_new_from_model(G_MENU_MODEL(model));
    g_object_unref(model);
    return menuBar;
}

GtkWidget* makeToolbar(const gtk_models::EditorGtkShellState& shellState) {
    GtkWidget* toolbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    gtk_widget_set_margin_start(toolbar, 8);
    gtk_widget_set_margin_end(toolbar, 8);
    gtk_widget_set_margin_top(toolbar, 6);
    gtk_widget_set_margin_bottom(toolbar, 6);

    for (const auto& item : shellState.toolbarItems()) {
        if (item.kind == editor::ToolbarItem::Kind::Separator) {
            gtk_box_append(GTK_BOX(toolbar), gtk_separator_new(GTK_ORIENTATION_VERTICAL));
            continue;
        }
        if (item.kind == editor::ToolbarItem::Kind::Label) {
            GtkWidget* label = gtk_label_new(item.label.c_str());
            gtk_box_append(GTK_BOX(toolbar), label);
            continue;
        }

        GtkWidget* button = item.iconName.empty()
            ? gtk_button_new_with_label(item.label.c_str())
            : gtk_button_new_from_icon_name(item.iconName.c_str());
        gtk_widget_set_tooltip_text(button, item.tooltip.c_str());
        gtk_widget_set_sensitive(button, item.enabled && !item.detailedActionName.empty());
        if (!item.detailedActionName.empty()) {
            gtk_actionable_set_action_name(GTK_ACTIONABLE(button), item.detailedActionName.c_str());
        }
        gtk_box_append(GTK_BOX(toolbar), button);
    }
    return toolbar;
}

GtkWidget* makePanelList(EditorGtkState& state) {
    GtkWidget* list = gtk_list_box_new();
    state.panelList = list;
    populatePanelList(state, list, state.shellState.viewState().panelRows);
    return list;
}

GtkWidget* makeWorkbench(EditorGtkState& state) {
    const auto view = state.shellState.viewState();
    GtkWidget* paned = gtk_paned_new(GTK_ORIENTATION_HORIZONTAL);

    GtkWidget* panelScroller = gtk_scrolled_window_new();
    gtk_widget_set_size_request(panelScroller, 230, -1);
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(panelScroller), makePanelList(state));
    gtk_paned_set_start_child(GTK_PANED(paned), panelScroller);

    GtkWidget* workbenchBox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 8);
    state.workbenchArea = workbenchBox;
    gtk_widget_set_margin_start(workbenchBox, 10);
    gtk_widget_set_margin_end(workbenchBox, 10);
    gtk_widget_set_margin_top(workbenchBox, 10);
    gtk_widget_set_margin_bottom(workbenchBox, 10);

    state.workbenchTabs = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    populateWorkbenchTabs(state, state.workbenchTabs, view.workbenchTabs);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchTabs);

    state.workbenchDockArea = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_widget_set_hexpand(state.workbenchDockArea, TRUE);
    gtk_widget_set_vexpand(state.workbenchDockArea, TRUE);
    populateWorkbenchDockArea(state, state.workbenchDockArea, view.workbenchLayout);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchDockArea);

    state.workbenchFloatingArea = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_widget_set_hexpand(state.workbenchFloatingArea, TRUE);
    populateWorkbenchFloatingArea(state, state.workbenchFloatingArea, view.workbenchLayout);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchFloatingArea);

    const bool showFallbackContent =
        !view.workbenchLayout.hasDockedLayout && view.workbenchLayout.floatingPanels.empty();

    state.workbenchTitleLabel = gtk_label_new(view.workbenchContent.title.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.workbenchTitleLabel), 0.0F);
    gtk_widget_set_visible(state.workbenchTitleLabel, showFallbackContent);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchTitleLabel);

    state.workbenchPrimaryLabel = gtk_label_new(view.workbenchContent.primaryText.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.workbenchPrimaryLabel), 0.0F);
    gtk_label_set_yalign(GTK_LABEL(state.workbenchPrimaryLabel), 0.0F);
    gtk_label_set_wrap(GTK_LABEL(state.workbenchPrimaryLabel), TRUE);
    gtk_widget_set_hexpand(state.workbenchPrimaryLabel, TRUE);
    gtk_widget_set_vexpand(state.workbenchPrimaryLabel, TRUE);
    gtk_widget_set_visible(state.workbenchPrimaryLabel, showFallbackContent);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchPrimaryLabel);

    state.workbenchActions = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    populateWorkbenchActions(state.workbenchActions, view.workbenchContent.actionSpecs);
    gtk_widget_set_visible(state.workbenchActions, showFallbackContent);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchActions);

    state.workbenchStatusLabel = gtk_label_new(view.workbenchContent.statusText.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.workbenchStatusLabel), 0.0F);
    gtk_widget_set_visible(state.workbenchStatusLabel, showFallbackContent);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchStatusLabel);

    gtk_paned_set_end_child(GTK_PANED(paned), workbenchBox);

    return paned;
}

GtkWidget* makeRoot(GtkApplication*, EditorGtkState& state) {
    GtkWidget* root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_box_append(GTK_BOX(root), makeMenuBar(state.shellState));
    gtk_box_append(GTK_BOX(root), makeToolbar(state.shellState));

    GtkWidget* workbench = makeWorkbench(state);
    gtk_widget_set_vexpand(workbench, TRUE);
    gtk_box_append(GTK_BOX(root), workbench);

    state.statusLabel = gtk_label_new(state.shellState.viewState().statusMessage.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.statusLabel), 0.0F);
    gtk_widget_set_margin_start(state.statusLabel, 8);
    gtk_widget_set_margin_end(state.statusLabel, 8);
    gtk_widget_set_margin_top(state.statusLabel, 4);
    gtk_widget_set_margin_bottom(state.statusLabel, 4);
    gtk_box_append(GTK_BOX(root), state.statusLabel);
    return root;
}

void activate(GtkApplication* app, gpointer userData) {
    auto* state = static_cast<EditorGtkState*>(userData);

    GtkWidget* window = gtk_application_window_new(app);
    state->window = GTK_WINDOW(window);
    const auto view = state->shellState.viewState();
    gtk_window_set_title(GTK_WINDOW(window), view.windowTitle.c_str());
    gtk_window_set_default_size(GTK_WINDOW(window), view.windowWidth, view.windowHeight);
    gtk_window_set_child(GTK_WINDOW(window), makeRoot(app, *state));
    refreshGtkShell(*state);
    gtk_window_present(GTK_WINDOW(window));
}

} // namespace

int main(int argc, char** argv) {
    EditorGtkState state;
    if (argc > 1 && argv[1] != nullptr) {
        (void)state.shellState.openFile(argv[1]);
    }
    GtkApplication* app = gtk_application_new("org.libreshockwave.Editor", G_APPLICATION_DEFAULT_FLAGS);
    state.app = app;
    installActions(app, state);
    g_signal_connect(app, "activate", G_CALLBACK(activate), &state);
    const int status = g_application_run(G_APPLICATION(app), argc, argv);
    g_object_unref(app);
    return status;
}
