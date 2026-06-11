#include "libreshockwave/editor/EditorShellModels.hpp"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <utility>

namespace libreshockwave::editor {
namespace {

EditorAccelerator accel(std::string key, bool ctrl = false, bool alt = false, bool shift = false) {
    return EditorAccelerator{std::move(key), ctrl, alt, shift};
}

EditorMenuItem command(std::string label,
                       EditorCommand command,
                       std::optional<EditorAccelerator> accelerator = std::nullopt,
                       bool enabled = true) {
    return EditorMenuItem{EditorMenuItem::Kind::Command,
                          std::move(label),
                          command,
                          std::move(accelerator),
                          enabled,
                          false,
                          {},
                          {}};
}

EditorMenuItem check(std::string label,
                     EditorCommand command,
                     bool checked = false,
                     std::optional<EditorAccelerator> accelerator = std::nullopt,
                     bool enabled = true,
                     std::string panelId = {}) {
    return EditorMenuItem{EditorMenuItem::Kind::Check,
                          std::move(label),
                          command,
                          std::move(accelerator),
                          enabled,
                          checked,
                          std::move(panelId),
                          {}};
}

EditorMenuItem separator() {
    EditorMenuItem item;
    item.kind = EditorMenuItem::Kind::Separator;
    return item;
}

EditorMenuItem submenu(std::string label, std::vector<EditorMenuItem> children) {
    EditorMenuItem item;
    item.kind = EditorMenuItem::Kind::Submenu;
    item.label = std::move(label);
    item.children = std::move(children);
    return item;
}

ToolbarItem button(std::string label, std::string tooltip, EditorCommand command) {
    return ToolbarItem{ToolbarItem::Kind::Button, std::move(label), std::move(tooltip), command};
}

ToolbarItem toolbarSeparator() {
    ToolbarItem item;
    item.kind = ToolbarItem::Kind::Separator;
    return item;
}

ToolbarItem toolbarLabel(std::string text) {
    ToolbarItem item;
    item.kind = ToolbarItem::Kind::Label;
    item.label = std::move(text);
    return item;
}

std::string trim(std::string_view value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin))) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1)))) {
        --end;
    }
    return std::string(begin, end);
}

} // namespace

EditorMenuModel::EditorMenuModel() {
    buildMenus();
}

const std::vector<EditorMenu>& EditorMenuModel::menus() const {
    return menus_;
}

const EditorMenu* EditorMenuModel::findMenu(std::string_view label) const {
    const auto found = std::find_if(menus_.begin(), menus_.end(), [label](const EditorMenu& menu) {
        return menu.label == label;
    });
    return found == menus_.end() ? nullptr : &*found;
}

const EditorMenuItem* EditorMenuModel::findCommand(EditorCommand command) const {
    for (const auto& menu : menus_) {
        if (const auto* item = findCommandInItems(menu.items, command); item != nullptr) {
            return item;
        }
    }
    return nullptr;
}

std::vector<EditorMenuItem> EditorMenuModel::windowItems(
    const std::vector<std::pair<std::string, bool>>& panelVisibility) const {
    std::vector<EditorMenuItem> items;
    for (const auto& [panelId, visible] : panelVisibility) {
        items.push_back(check(panelId, EditorCommand::None, visible, std::nullopt, true, panelId));
    }
    return items;
}

std::vector<std::string> EditorMenuModel::traceHandlersFromInput(std::string_view input) {
    std::vector<std::string> result;
    std::string current;
    std::istringstream stream{std::string(input)};
    while (std::getline(stream, current, ',')) {
        current = trim(current);
        if (!current.empty()) {
            result.push_back(current);
        }
    }
    return result;
}

const EditorMenuItem* EditorMenuModel::findCommandInItems(const std::vector<EditorMenuItem>& items,
                                                          EditorCommand command) {
    for (const auto& item : items) {
        if (item.command == command) {
            return &item;
        }
        if (const auto* found = findCommandInItems(item.children, command); found != nullptr) {
            return found;
        }
    }
    return nullptr;
}

void EditorMenuModel::buildMenus() {
    menus_ = {
        EditorMenu{"File", 'F', {
            command("New Movie", EditorCommand::NewMovie, accel("N", true), false),
            command("New Cast", EditorCommand::NewCast, std::nullopt, false),
            separator(),
            command("Open...", EditorCommand::Open, accel("O", true)),
            command("Close", EditorCommand::Close, accel("W", true)),
            separator(),
            command("Save", EditorCommand::Save, accel("S", true), false),
            command("Save As...", EditorCommand::SaveAs, accel("S", true, false, true), false),
            command("Save All", EditorCommand::SaveAll, std::nullopt, false),
            separator(),
            command("Import...", EditorCommand::Import, accel("R", true), false),
            command("Export...", EditorCommand::Export, std::nullopt, false),
            separator(),
            submenu("Preferences", {
                command("General...", EditorCommand::PreferencesGeneral, std::nullopt, false),
                command("Network...", EditorCommand::PreferencesNetwork, std::nullopt, false),
                command("Script...", EditorCommand::PreferencesScript, std::nullopt, false),
                command("Sprite...", EditorCommand::PreferencesSprite, std::nullopt, false),
                command("Paint...", EditorCommand::PreferencesPaint, std::nullopt, false),
            }),
            separator(),
            command("Exit", EditorCommand::Exit),
        }},
        EditorMenu{"Edit", 'E', {
            command("Undo", EditorCommand::Undo, accel("Z", true), false),
            command("Redo", EditorCommand::Redo, accel("Y", true), false),
            separator(),
            command("Cut", EditorCommand::Cut, accel("X", true), false),
            command("Copy", EditorCommand::Copy, accel("C", true), false),
            command("Paste", EditorCommand::Paste, accel("V", true), false),
            command("Clear", EditorCommand::Clear, accel("DELETE"), false),
            command("Select All", EditorCommand::SelectAll, accel("A", true), false),
            separator(),
            submenu("Find", {
                command("Find...", EditorCommand::Find, accel("F", true), false),
                command("Find Again", EditorCommand::FindAgain, accel("G", true), false),
                command("Replace...", EditorCommand::Replace, accel("H", true), false),
                command("Find Selection", EditorCommand::FindSelection, std::nullopt, false),
            }),
            separator(),
            command("Edit Sprite Frames", EditorCommand::EditSpriteFrames, std::nullopt, false),
            command("Edit Entire Sprite", EditorCommand::EditEntireSprite, std::nullopt, false),
            separator(),
            command("Exchange Cast Members", EditorCommand::ExchangeCastMembers, std::nullopt, false),
        }},
        EditorMenu{"View", 'V', {
            submenu("Zoom", {
                command("25%", EditorCommand::None, std::nullopt, false),
                command("50%", EditorCommand::None, std::nullopt, false),
                command("100%", EditorCommand::None, std::nullopt, false),
                command("200%", EditorCommand::None, std::nullopt, false),
                command("400%", EditorCommand::None, std::nullopt, false),
            }),
            separator(),
            submenu("Sprite Overlay", {
                check("Show Info", EditorCommand::None),
                check("Show Paths", EditorCommand::None),
            }),
            separator(),
            check("Sprite Toolbar", EditorCommand::None),
            check("Keyframes", EditorCommand::None),
            separator(),
            submenu("Grids", {
                check("Show", EditorCommand::None),
                check("Snap To", EditorCommand::None),
                command("Settings...", EditorCommand::None, std::nullopt, false),
            }),
            submenu("Guides", {
                check("Show", EditorCommand::None),
                check("Snap To", EditorCommand::None),
            }),
        }},
        EditorMenu{"Insert", 'I', {
            command("Keyframe", EditorCommand::None, accel("K", true, true), false),
            separator(),
            command("Marker", EditorCommand::None, std::nullopt, false),
            separator(),
            command("Remove Frame", EditorCommand::None, std::nullopt, false),
            separator(),
            submenu("Media Element", {
                command("Bitmap", EditorCommand::None, std::nullopt, false),
                command("Text", EditorCommand::None, std::nullopt, false),
                command("Script", EditorCommand::None, std::nullopt, false),
                command("Shape", EditorCommand::None, std::nullopt, false),
                command("Film Loop", EditorCommand::None, std::nullopt, false),
                command("Sound", EditorCommand::None, std::nullopt, false),
            }),
        }},
        EditorMenu{"Modify", 'M', {
            submenu("Movie", {
                command("Properties...", EditorCommand::None, std::nullopt, false),
                command("Casts...", EditorCommand::None, std::nullopt, false),
                command("External Parameters...", EditorCommand::ExternalParameters, accel("E", true, false, true)),
            }),
            separator(),
            submenu("Sprite", {
                command("Properties...", EditorCommand::None, std::nullopt, false),
                command("Tweening...", EditorCommand::None, std::nullopt, false),
            }),
            separator(),
            submenu("Cast Member", {
                command("Properties...", EditorCommand::None, std::nullopt, false),
            }),
            separator(),
            submenu("Frame", {
                command("Tempo...", EditorCommand::None, std::nullopt, false),
                command("Palette...", EditorCommand::None, std::nullopt, false),
                command("Transition...", EditorCommand::None, std::nullopt, false),
                command("Sound...", EditorCommand::None, std::nullopt, false),
            }),
            separator(),
            command("Font...", EditorCommand::None, std::nullopt, false),
            command("Paragraph...", EditorCommand::None, std::nullopt, false),
        }},
        EditorMenu{"Control", 'C', {
            command("Play", EditorCommand::Play, accel("P", true, true)),
            command("Stop", EditorCommand::Stop, accel("PERIOD", true)),
            command("Rewind", EditorCommand::Rewind, accel("R", true, true)),
            separator(),
            command("Step Forward", EditorCommand::StepForward, accel("RIGHT", true, true)),
            command("Step Backward", EditorCommand::StepBackward, accel("LEFT", true, true)),
            separator(),
            check("Loop Playback", EditorCommand::LoopPlayback, true),
        }},
        EditorMenu{"Debug", 'D', {
            command("Step Into", EditorCommand::DebugStepInto, accel("F11")),
            command("Step Over", EditorCommand::DebugStepOver, accel("F10")),
            command("Step Out", EditorCommand::DebugStepOut, accel("F11", false, false, true)),
            command("Continue", EditorCommand::DebugContinue, accel("F5")),
            separator(),
            command("Toggle Breakpoint", EditorCommand::ToggleBreakpoint, accel("F9"), false),
            command("Clear All Breakpoints", EditorCommand::ClearAllBreakpoints),
            separator(),
            check("Detailed Stack Window", EditorCommand::DetailedStackWindow, false, accel("S", true, false, true)),
            command("Trace Handler...", EditorCommand::TraceHandler, accel("T", true, false, true)),
        }},
        EditorMenu{"Window", 'W', {
            check("Stage", EditorCommand::None, true, accel("1", true), true, "stage"),
            check("Score", EditorCommand::None, true, accel("2", true), true, "score"),
            check("Cast", EditorCommand::None, true, accel("3", true), true, "cast"),
            check("Property Inspector", EditorCommand::None, true, accel("4", true), true, "property-inspector"),
            check("Script", EditorCommand::None, true, accel("0", true), true, "script"),
            check("Message", EditorCommand::None, true, accel("M", true, false, true), true, "message"),
            check("Tool Palette", EditorCommand::None, true, accel("7", true), true, "tool-palette"),
            separator(),
            check("Paint", EditorCommand::None, true, accel("5", true), true, "paint"),
            check("Vector Shape", EditorCommand::None, true, std::nullopt, true, "vector-shape"),
            check("Text", EditorCommand::None, true, accel("6", true), true, "text"),
            check("Field", EditorCommand::None, true, std::nullopt, true, "field"),
            check("Sound", EditorCommand::None, true, std::nullopt, true, "sound"),
            check("Color Palettes", EditorCommand::None, true, accel("7", true, true), true, "color-palettes"),
            separator(),
            check("Bytecode Debugger", EditorCommand::None, true, accel("D", true, false, true), true, "bytecode-debugger"),
            separator(),
            command("Reset Layout", EditorCommand::ResetLayout),
        }},
        EditorMenu{"Help", 'H', {
            command("About LibreShockwave Editor", EditorCommand::About),
        }},
    };
}

EditorToolBarModel::EditorToolBarModel()
    : items_({
        button("Rewind", "Rewind", EditorCommand::Rewind),
        button("Stop", "Stop", EditorCommand::Stop),
        button("Play", "Play", EditorCommand::Play),
        toolbarSeparator(),
        button("Step Backward", "Step Backward", EditorCommand::StepBackward),
        button("Step Forward", "Step Forward", EditorCommand::StepForward),
        toolbarSeparator(),
        toolbarLabel("Frame: 1"),
    }) {}

const std::vector<ToolbarItem>& EditorToolBarModel::items() const {
    return items_;
}

std::string EditorToolBarModel::frameLabel(int currentFrame, std::optional<int> frameCount) {
    if (!frameCount.has_value()) {
        return "Frame: 1";
    }
    return "Frame: " + std::to_string(currentFrame) + " / " + std::to_string(*frameCount);
}

std::string_view commandName(EditorCommand command) {
    switch (command) {
        case EditorCommand::None: return "none";
        case EditorCommand::NewMovie: return "newMovie";
        case EditorCommand::NewCast: return "newCast";
        case EditorCommand::Open: return "open";
        case EditorCommand::Close: return "close";
        case EditorCommand::Save: return "save";
        case EditorCommand::SaveAs: return "saveAs";
        case EditorCommand::SaveAll: return "saveAll";
        case EditorCommand::Import: return "import";
        case EditorCommand::Export: return "export";
        case EditorCommand::Exit: return "exit";
        case EditorCommand::PreferencesGeneral: return "preferencesGeneral";
        case EditorCommand::PreferencesNetwork: return "preferencesNetwork";
        case EditorCommand::PreferencesScript: return "preferencesScript";
        case EditorCommand::PreferencesSprite: return "preferencesSprite";
        case EditorCommand::PreferencesPaint: return "preferencesPaint";
        case EditorCommand::Undo: return "undo";
        case EditorCommand::Redo: return "redo";
        case EditorCommand::Cut: return "cut";
        case EditorCommand::Copy: return "copy";
        case EditorCommand::Paste: return "paste";
        case EditorCommand::Clear: return "clear";
        case EditorCommand::SelectAll: return "selectAll";
        case EditorCommand::Find: return "find";
        case EditorCommand::FindAgain: return "findAgain";
        case EditorCommand::Replace: return "replace";
        case EditorCommand::FindSelection: return "findSelection";
        case EditorCommand::EditSpriteFrames: return "editSpriteFrames";
        case EditorCommand::EditEntireSprite: return "editEntireSprite";
        case EditorCommand::ExchangeCastMembers: return "exchangeCastMembers";
        case EditorCommand::ExternalParameters: return "externalParameters";
        case EditorCommand::Play: return "play";
        case EditorCommand::Stop: return "stop";
        case EditorCommand::Rewind: return "rewind";
        case EditorCommand::StepForward: return "stepForward";
        case EditorCommand::StepBackward: return "stepBackward";
        case EditorCommand::LoopPlayback: return "loopPlayback";
        case EditorCommand::DebugStepInto: return "debugStepInto";
        case EditorCommand::DebugStepOver: return "debugStepOver";
        case EditorCommand::DebugStepOut: return "debugStepOut";
        case EditorCommand::DebugContinue: return "debugContinue";
        case EditorCommand::ToggleBreakpoint: return "toggleBreakpoint";
        case EditorCommand::ClearAllBreakpoints: return "clearAllBreakpoints";
        case EditorCommand::DetailedStackWindow: return "detailedStackWindow";
        case EditorCommand::TraceHandler: return "traceHandler";
        case EditorCommand::ResetLayout: return "resetLayout";
        case EditorCommand::About: return "about";
    }
    return "none";
}

} // namespace libreshockwave::editor
