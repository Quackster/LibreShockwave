#include <gtk/gtk.h>

#include <cctype>
#include <map>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/EditorFrameModels.hpp"
#include "libreshockwave/editor/EditorShellModels.hpp"
#include "libreshockwave/editor/panels/EditorPanelCatalog.hpp"

namespace {

namespace editor = libreshockwave::editor;
namespace panels = libreshockwave::editor::panels;

struct EditorGtkState {
    editor::EditorMenuModel menuModel;
    editor::EditorToolBarModel toolbarModel;
    editor::EditorFramePanelModel frameModel;
};

std::string sanitizeActionName(std::string_view value) {
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

std::string commandActionName(editor::EditorCommand command) {
    return sanitizeActionName(editor::commandName(command));
}

std::string panelActionName(std::string_view panelId) {
    return "panel_" + sanitizeActionName(panelId);
}

std::string appAction(std::string_view name) {
    return "app." + std::string(name);
}

void collectMenuActions(const std::vector<editor::EditorMenuItem>& items,
                        std::map<std::string, bool>& commandEnabled) {
    for (const auto& item : items) {
        if (item.command != editor::EditorCommand::None) {
            commandEnabled[commandActionName(item.command)] =
                commandEnabled[commandActionName(item.command)] || item.enabled;
        }
        collectMenuActions(item.children, commandEnabled);
    }
}

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
            action = appAction(panelActionName(item.panelId));
        } else if (item.command != editor::EditorCommand::None) {
            action = appAction(commandActionName(item.command));
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

void commandActivated(GSimpleAction* action, GVariant*, gpointer userData) {
    const char* name = g_action_get_name(G_ACTION(action));
    const auto exitAction = commandActionName(editor::EditorCommand::Exit);
    if (name != nullptr && exitAction == name) {
        g_application_quit(G_APPLICATION(userData));
        return;
    }
    g_print("LibreShockwave GTK editor command: %s\n", name != nullptr ? name : "");
}

void panelActionActivated(GSimpleAction* action, GVariant*, gpointer) {
    GVariant* state = g_action_get_state(G_ACTION(action));
    const bool active = state != nullptr && g_variant_get_boolean(state);
    if (state != nullptr) {
        g_variant_unref(state);
    }
    g_simple_action_set_state(action, g_variant_new_boolean(!active));
}

void installActions(GtkApplication* app, const EditorGtkState& state) {
    std::map<std::string, bool> commandEnabled;
    for (const auto& menu : state.menuModel.menus()) {
        collectMenuActions(menu.items, commandEnabled);
    }
    for (const auto& item : state.toolbarModel.items()) {
        if (item.command != editor::EditorCommand::None) {
            commandEnabled[commandActionName(item.command)] = true;
        }
    }

    for (const auto& [name, enabled] : commandEnabled) {
        GSimpleAction* action = g_simple_action_new(name.c_str(), nullptr);
        g_simple_action_set_enabled(action, enabled);
        g_signal_connect(action, "activate", G_CALLBACK(commandActivated), app);
        g_action_map_add_action(G_ACTION_MAP(app), G_ACTION(action));
        g_object_unref(action);
    }

    for (const auto& [panelId, visible] : state.frameModel.panelVisibility()) {
        GSimpleAction* action = g_simple_action_new_stateful(panelActionName(panelId).c_str(),
                                                             nullptr,
                                                             g_variant_new_boolean(visible));
        g_signal_connect(action, "activate", G_CALLBACK(panelActionActivated), nullptr);
        g_action_map_add_action(G_ACTION_MAP(app), G_ACTION(action));
        g_object_unref(action);
    }
}

GtkWidget* makeMenuBar(const editor::EditorMenuModel& menuModel) {
    GMenu* model = buildMenuModel(menuModel);
    GtkWidget* menuBar = gtk_popover_menu_bar_new_from_model(G_MENU_MODEL(model));
    g_object_unref(model);
    return menuBar;
}

GtkWidget* makeToolbar(const editor::EditorToolBarModel& toolbarModel) {
    GtkWidget* toolbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    gtk_widget_set_margin_start(toolbar, 8);
    gtk_widget_set_margin_end(toolbar, 8);
    gtk_widget_set_margin_top(toolbar, 6);
    gtk_widget_set_margin_bottom(toolbar, 6);

    for (const auto& item : toolbarModel.items()) {
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
        if (item.command != editor::EditorCommand::None) {
            const auto action = appAction(commandActionName(item.command));
            gtk_actionable_set_action_name(GTK_ACTIONABLE(button), action.c_str());
        }
        gtk_box_append(GTK_BOX(toolbar), button);
    }
    return toolbar;
}

GtkWidget* makePanelList(const editor::EditorFramePanelModel& frameModel) {
    GtkWidget* list = gtk_list_box_new();
    const auto descriptors = panels::EditorPanelCatalog::descriptors();
    for (const auto& descriptor : descriptors) {
        const bool visible = frameModel.isPanelVisible(descriptor.panelId);
        std::string labelText = descriptor.title;
        if (!visible) {
            labelText += " (hidden)";
        }
        GtkWidget* label = gtk_label_new(labelText.c_str());
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
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(panelScroller), makePanelList(state.frameModel));
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
    gtk_box_append(GTK_BOX(root), makeMenuBar(state.menuModel));
    gtk_box_append(GTK_BOX(root), makeToolbar(state.toolbarModel));

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
    installActions(app, state);
    g_signal_connect(app, "activate", G_CALLBACK(activate), &state);
    const int status = g_application_run(G_APPLICATION(app), argc, argv);
    g_object_unref(app);
    return status;
}
