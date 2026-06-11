#include <gtk/gtk.h>

#include <string>
#include <vector>

#include "libreshockwave/editor/EditorFrameModels.hpp"
#include "libreshockwave/editor/EditorShellModels.hpp"
#include "libreshockwave/editor/gtk/EditorGtkModels.hpp"
#include "libreshockwave/editor/panels/EditorPanelCatalog.hpp"

namespace {

namespace editor = libreshockwave::editor;
namespace gtk_models = libreshockwave::editor::gtk;
namespace panels = libreshockwave::editor::panels;

struct EditorGtkState {
    gtk_models::EditorGtkShellState shellState;
    GtkApplication* app{nullptr};
};

void appendMenuItems(GMenu* menu, const std::vector<editor::EditorMenuItem>& items) {
    for (const auto& item : items) {
        if (item.kind == editor::EditorMenuItem::Kind::Separator) {
            continue;
        }

        if (item.kind == editor::EditorMenuItem::Kind::Submenu) {
            GMenu* child = g_menu_new();
            appendMenuItems(child, item.children);
            g_menu_append_submenu(menu, item.label.c_str(), G_MENU_MODEL(child));
            g_object_unref(child);
            continue;
        }

        std::string action;
        if (!item.panelId.empty()) {
            action = gtk_models::EditorGtkShellModel::appAction(
                gtk_models::EditorGtkShellModel::panelActionName(item.panelId));
        } else if (item.command != editor::EditorCommand::None) {
            action = gtk_models::EditorGtkShellModel::appAction(
                gtk_models::EditorGtkShellModel::commandActionName(item.command));
        }
        g_menu_append(menu, item.label.c_str(), action.empty() ? nullptr : action.c_str());
    }
}

GMenu* buildMenuModel(const editor::EditorMenuModel& menuModel) {
    GMenu* menuBar = g_menu_new();
    for (const auto& menu : menuModel.menus()) {
        GMenu* submenu = g_menu_new();
        appendMenuItems(submenu, menu.items);
        g_menu_append_submenu(menuBar, menu.label.c_str(), G_MENU_MODEL(submenu));
        g_object_unref(submenu);
    }
    return menuBar;
}

gtk_models::GtkActionActivation activateGtkAction(GSimpleAction* action, EditorGtkState* state) {
    const char* name = g_action_get_name(G_ACTION(action));
    auto result = state->shellState.activateAction(name != nullptr ? name : "");
    if (result.active.has_value()) {
        g_simple_action_set_state(action, g_variant_new_boolean(*result.active));
    }
    if (!result.statusMessage.empty()) {
        g_print("LibreShockwave GTK editor: %s\n", result.statusMessage.c_str());
    }
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
}

GtkWidget* makeMenuBar(const gtk_models::EditorGtkShellState& shellState) {
    GMenu* model = buildMenuModel(shellState.menuModel());
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

GtkWidget* makePanelList(const gtk_models::EditorGtkShellState& shellState) {
    GtkWidget* list = gtk_list_box_new();
    for (const auto& row : shellState.panelRows()) {
        GtkWidget* label = gtk_label_new(row.displayLabel.c_str());
        gtk_label_set_xalign(GTK_LABEL(label), 0.0F);
        gtk_widget_set_margin_start(label, 8);
        gtk_widget_set_margin_end(label, 8);
        gtk_widget_set_margin_top(label, 5);
        gtk_widget_set_margin_bottom(label, 5);
        gtk_list_box_append(GTK_LIST_BOX(list), label);
    }
    return list;
}

GtkWidget* makeWorkbench(const EditorGtkState& state) {
    GtkWidget* paned = gtk_paned_new(GTK_ORIENTATION_HORIZONTAL);

    GtkWidget* panelScroller = gtk_scrolled_window_new();
    gtk_widget_set_size_request(panelScroller, 230, -1);
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(panelScroller), makePanelList(state.shellState));
    gtk_paned_set_start_child(GTK_PANED(paned), panelScroller);

    GtkWidget* stageFrame = gtk_frame_new("Stage");
    GtkWidget* stageLabel = gtk_label_new("No movie loaded");
    gtk_widget_set_hexpand(stageLabel, TRUE);
    gtk_widget_set_vexpand(stageLabel, TRUE);
    gtk_frame_set_child(GTK_FRAME(stageFrame), stageLabel);
    gtk_paned_set_end_child(GTK_PANED(paned), stageFrame);

    return paned;
}

GtkWidget* makeRoot(GtkApplication*, const EditorGtkState& state) {
    GtkWidget* root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_box_append(GTK_BOX(root), makeMenuBar(state.shellState));
    gtk_box_append(GTK_BOX(root), makeToolbar(state.shellState));

    GtkWidget* workbench = makeWorkbench(state);
    gtk_widget_set_vexpand(workbench, TRUE);
    gtk_box_append(GTK_BOX(root), workbench);
    return root;
}

void activate(GtkApplication* app, gpointer userData) {
    auto* state = static_cast<EditorGtkState*>(userData);

    GtkWidget* window = gtk_application_window_new(app);
    const auto title = panels::EditorPanelCatalog::closedFrameTitle();
    const auto defaults = panels::EditorPanelCatalog::frameDefaults();
    gtk_window_set_title(GTK_WINDOW(window), title.c_str());
    gtk_window_set_default_size(GTK_WINDOW(window), defaults.size.width, defaults.size.height);
    gtk_window_set_child(GTK_WINDOW(window), makeRoot(app, *state));
    gtk_window_present(GTK_WINDOW(window));
}

} // namespace

int main(int argc, char** argv) {
    EditorGtkState state;
    GtkApplication* app = gtk_application_new("org.libreshockwave.Editor", G_APPLICATION_DEFAULT_FLAGS);
    state.app = app;
    installActions(app, state);
    g_signal_connect(app, "activate", G_CALLBACK(activate), &state);
    const int status = g_application_run(G_APPLICATION(app), argc, argv);
    g_object_unref(app);
    return status;
}
