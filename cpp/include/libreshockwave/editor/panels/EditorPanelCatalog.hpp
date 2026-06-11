#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace libreshockwave::editor::panels {

struct PanelSize {
    int width{};
    int height{};

    friend bool operator==(const PanelSize&, const PanelSize&) = default;
};

struct PanelBounds {
    int x{};
    int y{};
    int width{};
    int height{};

    friend bool operator==(const PanelBounds&, const PanelBounds&) = default;
};

struct PanelCapabilities {
    bool resizable{true};
    bool closable{true};
    bool maximizable{true};
    bool iconifiable{true};

    friend bool operator==(const PanelCapabilities&, const PanelCapabilities&) = default;
};

struct PanelDescriptor {
    std::string panelId;
    std::string title;
    PanelCapabilities capabilities;
    PanelSize initialSize;

    friend bool operator==(const PanelDescriptor&, const PanelDescriptor&) = default;
};

struct PanelPlacement {
    std::string panelId;
    PanelBounds bounds;
    bool visible{false};
    bool selected{false};

    friend bool operator==(const PanelPlacement&, const PanelPlacement&) = default;
};

struct EditorFrameDefaults {
    PanelSize size;
    int desktopBackgroundR{};
    int desktopBackgroundG{};
    int desktopBackgroundB{};

    friend bool operator==(const EditorFrameDefaults&, const EditorFrameDefaults&) = default;
};

class EditorPanelCatalog {
public:
    [[nodiscard]] static std::vector<PanelDescriptor> descriptors();
    [[nodiscard]] static std::optional<PanelDescriptor> descriptor(std::string_view panelId);
    [[nodiscard]] static bool isKnownPanel(std::string_view panelId);

    [[nodiscard]] static std::vector<PanelPlacement> defaultFloatingLayout();
    [[nodiscard]] static std::optional<PanelPlacement> defaultPlacement(std::string_view panelId);
    [[nodiscard]] static std::vector<std::string> defaultVisiblePanelIds();
    [[nodiscard]] static std::vector<std::pair<std::string, std::string>> panelTitles();

    [[nodiscard]] static EditorFrameDefaults frameDefaults();
    [[nodiscard]] static std::string closedFrameTitle();
    [[nodiscard]] static std::string frameTitleForPath(std::string_view path);
};

} // namespace libreshockwave::editor::panels
