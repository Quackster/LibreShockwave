#include "libreshockwave/editor/panels/EditorPanelCatalog.hpp"

#include <algorithm>
#include <utility>

#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::editor::panels {
namespace {

PanelDescriptor panel(std::string panelId,
                      std::string title,
                      int width,
                      int height,
                      PanelCapabilities capabilities = {}) {
    return PanelDescriptor{std::move(panelId), std::move(title), capabilities, PanelSize{width, height}};
}

PanelPlacement visible(std::string panelId, int x, int y, int width, int height, bool selected = false) {
    return PanelPlacement{std::move(panelId), PanelBounds{x, y, width, height}, true, selected};
}

PanelPlacement hidden(const PanelDescriptor& descriptor) {
    return PanelPlacement{descriptor.panelId,
                          PanelBounds{0, 0, descriptor.initialSize.width, descriptor.initialSize.height},
                          false,
                          false};
}

} // namespace

std::vector<PanelDescriptor> EditorPanelCatalog::descriptors() {
    return {
        panel("stage", "Stage", 660, 500),
        panel("score", "Score", 700, 300),
        panel("cast", "Cast", 400, 350),
        panel("property-inspector", "Property Inspector", 280, 400),
        panel("script", "Script", 700, 500),
        panel("message", "Message", 450, 200),
        panel("tool-palette", "Tool Palette", 160, 350, PanelCapabilities{true, true, false, true}),
        panel("paint", "Paint", 500, 400),
        panel("vector-shape", "Vector Shape", 450, 350),
        panel("text", "Text", 400, 300),
        panel("field", "Field", 350, 250),
        panel("sound", "Sound", 400, 250),
        panel("color-palettes", "Color Palettes", 350, 300),
        panel("bytecode-debugger", "Bytecode Debugger", 600, 700),
    };
}

std::optional<PanelDescriptor> EditorPanelCatalog::descriptor(std::string_view panelId) {
    const auto all = descriptors();
    const auto found = std::find_if(all.begin(), all.end(), [panelId](const PanelDescriptor& descriptor) {
        return descriptor.panelId == panelId;
    });
    return found == all.end() ? std::nullopt : std::optional(*found);
}

bool EditorPanelCatalog::isKnownPanel(std::string_view panelId) {
    return descriptor(panelId).has_value();
}

std::vector<PanelPlacement> EditorPanelCatalog::defaultFloatingLayout() {
    std::vector<PanelPlacement> placements{
        visible("stage", 170, 10, 660, 500, true),
        visible("score", 170, 520, 700, 300),
        visible("cast", 880, 520, 400, 300),
        visible("property-inspector", 880, 10, 280, 400),
        visible("script", 170, 520, 500, 400),
        visible("message", 880, 420, 400, 200),
        visible("tool-palette", 5, 10, 160, 350),
    };

    const auto all = descriptors();
    for (const auto& descriptor : all) {
        const auto alreadyPlaced = std::any_of(placements.begin(), placements.end(), [&descriptor](const auto& item) {
            return item.panelId == descriptor.panelId;
        });
        if (!alreadyPlaced) {
            placements.push_back(hidden(descriptor));
        }
    }
    return placements;
}

std::optional<PanelPlacement> EditorPanelCatalog::defaultPlacement(std::string_view panelId) {
    const auto placements = defaultFloatingLayout();
    const auto found = std::find_if(placements.begin(), placements.end(), [panelId](const PanelPlacement& placement) {
        return placement.panelId == panelId;
    });
    return found == placements.end() ? std::nullopt : std::optional(*found);
}

std::vector<std::string> EditorPanelCatalog::defaultVisiblePanelIds() {
    std::vector<std::string> ids;
    for (const auto& placement : defaultFloatingLayout()) {
        if (placement.visible) {
            ids.push_back(placement.panelId);
        }
    }
    return ids;
}

std::vector<std::pair<std::string, std::string>> EditorPanelCatalog::panelTitles() {
    std::vector<std::pair<std::string, std::string>> titles;
    const auto all = descriptors();
    titles.reserve(all.size());
    for (const auto& descriptor : all) {
        titles.emplace_back(descriptor.panelId, descriptor.title);
    }
    return titles;
}

EditorFrameDefaults EditorPanelCatalog::frameDefaults() {
    return EditorFrameDefaults{PanelSize{1280, 900}, 58, 68, 75};
}

std::string EditorPanelCatalog::closedFrameTitle() {
    return "LibreShockwave Editor - Director MX 2004";
}

std::string EditorPanelCatalog::frameTitleForPath(std::string_view path) {
    const auto fileName = util::getFileName(path);
    return fileName.empty() ? closedFrameTitle() : "LibreShockwave Editor - " + fileName;
}

} // namespace libreshockwave::editor::panels
