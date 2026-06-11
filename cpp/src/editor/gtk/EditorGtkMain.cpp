#include <gtk/gtk.h>

#include <string>
#include <vector>

#include "libreshockwave/editor/EditorShellModels.hpp"
#include "libreshockwave/editor/gtk/EditorGtkModels.hpp"

namespace {

namespace editor = libreshockwave::editor;
namespace gtk_models = libreshockwave::editor::gtk;

struct EditorGtkState {
    gtk_models::EditorGtkShellState shellState;
    GtkApplication* app{nullptr};
    GtkWindow* window{nullptr};
    GtkWidget* panelList{nullptr};
    GtkWidget* workbenchTabs{nullptr};
    GtkWidget* workbenchTitleLabel{nullptr};
    GtkWidget* workbenchPrimaryLabel{nullptr};
    GtkWidget* workbenchStatusLabel{nullptr};
    GtkWidget* workbenchActions{nullptr};
    GtkWidget* statusLabel{nullptr};
};

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

void populatePanelList(GtkWidget* list, const std::vector<gtk_models::GtkPanelRowSpec>& rows) {
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

void populateWorkbenchTabs(GtkWidget* tabsBox, const std::vector<gtk_models::GtkWorkbenchTabSpec>& tabs) {
    clearBox(tabsBox);

    for (const auto& tab : tabs) {
        GtkWidget* tabBox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 2);
        GtkWidget* focusButton = actionButton(tab.title.c_str(), tab.detailedFocusActionName);
        gtk_widget_set_tooltip_text(focusButton, tab.focusTooltip.c_str());
        if (tab.active) {
            gtk_widget_add_css_class(focusButton, "suggested-action");
        }
        gtk_box_append(GTK_BOX(tabBox), focusButton);

        GtkWidget* floatButton = actionButton(tab.floatLabel.c_str(), tab.detailedFloatActionName);
        gtk_widget_set_tooltip_text(floatButton, tab.floatTooltip.c_str());
        gtk_box_append(GTK_BOX(tabBox), floatButton);

        GtkWidget* hideButton = actionButton(tab.hideLabel.c_str(), tab.detailedToggleActionName);
        gtk_widget_set_tooltip_text(hideButton, tab.hideTooltip.c_str());
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
        gtk_box_append(GTK_BOX(actionsBox), button);
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
        populatePanelList(state.panelList, view.panelRows);
    }
    if (state.workbenchTabs != nullptr) {
        populateWorkbenchTabs(state.workbenchTabs, view.workbenchTabs);
    }
    if (state.workbenchTitleLabel != nullptr) {
        gtk_label_set_text(GTK_LABEL(state.workbenchTitleLabel), view.workbenchContent.title.c_str());
    }
    if (state.workbenchPrimaryLabel != nullptr) {
        gtk_label_set_text(GTK_LABEL(state.workbenchPrimaryLabel), view.workbenchContent.primaryText.c_str());
    }
    if (state.workbenchStatusLabel != nullptr) {
        gtk_label_set_text(GTK_LABEL(state.workbenchStatusLabel), view.workbenchContent.statusText.c_str());
    }
    if (state.workbenchActions != nullptr) {
        populateWorkbenchActions(state.workbenchActions, view.workbenchContent.actionSpecs);
    }
    if (state.statusLabel != nullptr) {
        gtk_label_set_text(GTK_LABEL(state.statusLabel), view.statusMessage.c_str());
    }
}

gtk_models::GtkActionActivation activateGtkAction(GSimpleAction* action, EditorGtkState* state) {
    const char* name = g_action_get_name(G_ACTION(action));
    auto result = state->shellState.activateAction(name != nullptr ? name : "");
    if (result.requestOpenFile && result.openFileDialog.has_value()) {
        g_print("LibreShockwave GTK editor open dialog: %s\n", result.openFileDialog->title.c_str());
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

        GtkWidget* button = gtk_button_new_with_label(item.label.c_str());
        gtk_widget_set_tooltip_text(button, item.tooltip.c_str());
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
    populatePanelList(list, state.shellState.viewState().panelRows);
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
    gtk_widget_set_margin_start(workbenchBox, 10);
    gtk_widget_set_margin_end(workbenchBox, 10);
    gtk_widget_set_margin_top(workbenchBox, 10);
    gtk_widget_set_margin_bottom(workbenchBox, 10);

    state.workbenchTabs = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    populateWorkbenchTabs(state.workbenchTabs, view.workbenchTabs);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchTabs);

    state.workbenchTitleLabel = gtk_label_new(view.workbenchContent.title.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.workbenchTitleLabel), 0.0F);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchTitleLabel);

    state.workbenchPrimaryLabel = gtk_label_new(view.workbenchContent.primaryText.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.workbenchPrimaryLabel), 0.0F);
    gtk_label_set_yalign(GTK_LABEL(state.workbenchPrimaryLabel), 0.0F);
    gtk_label_set_wrap(GTK_LABEL(state.workbenchPrimaryLabel), TRUE);
    gtk_widget_set_hexpand(state.workbenchPrimaryLabel, TRUE);
    gtk_widget_set_vexpand(state.workbenchPrimaryLabel, TRUE);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchPrimaryLabel);

    state.workbenchActions = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    populateWorkbenchActions(state.workbenchActions, view.workbenchContent.actionSpecs);
    gtk_box_append(GTK_BOX(workbenchBox), state.workbenchActions);

    state.workbenchStatusLabel = gtk_label_new(view.workbenchContent.statusText.c_str());
    gtk_label_set_xalign(GTK_LABEL(state.workbenchStatusLabel), 0.0F);
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
