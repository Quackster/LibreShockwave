#pragma once

#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace libreshockwave::editor::docking {

enum class DockNodeKind {
    Center,
    Leaf,
    Split
};

enum class DockOrientation {
    Horizontal,
    Vertical
};

enum class DockEdge {
    Left,
    Right,
    Top,
    Bottom
};

struct DockPanelTab {
    std::string panelId;
    std::string title;

    friend bool operator==(const DockPanelTab&, const DockPanelTab&) = default;
};

struct DockNodeModel {
    DockNodeKind kind{DockNodeKind::Center};
    std::vector<DockPanelTab> tabs;
    int selectedIndex{-1};
    DockOrientation orientation{DockOrientation::Horizontal};
    double dividerFraction{0.5};
    std::shared_ptr<DockNodeModel> first;
    std::shared_ptr<DockNodeModel> second;

    [[nodiscard]] static std::shared_ptr<DockNodeModel> center();
    [[nodiscard]] static std::shared_ptr<DockNodeModel> leaf();
    [[nodiscard]] static std::shared_ptr<DockNodeModel> split(std::shared_ptr<DockNodeModel> first,
                                                              std::shared_ptr<DockNodeModel> second,
                                                              DockOrientation orientation,
                                                              double dividerFraction);

    [[nodiscard]] bool hasTabs() const;
    [[nodiscard]] bool hasTab(std::string_view panelId) const;
    [[nodiscard]] std::vector<std::string> panelIds() const;
    [[nodiscard]] const DockPanelTab* findTab(std::string_view panelId) const;
    [[nodiscard]] const DockPanelTab* selectedTab() const;
    bool addTab(DockPanelTab tab);
    bool removeTab(std::string_view panelId);
    bool selectTab(std::string_view panelId);
};

class DockingLayoutModel {
public:
    DockingLayoutModel();

    void registerPanel(std::string panelId, std::string title);
    [[nodiscard]] bool hasRegisteredPanel(std::string_view panelId) const;
    [[nodiscard]] std::optional<std::string> panelTitle(std::string_view panelId) const;

    [[nodiscard]] const DockNodeModel& root() const;
    [[nodiscard]] const DockNodeModel& center() const;
    [[nodiscard]] const DockNodeModel* nodeForPanel(std::string_view panelId) const;
    [[nodiscard]] bool isDocked(std::string_view panelId) const;
    [[nodiscard]] std::vector<std::string> dockedPanelIds() const;

    bool dockCenter(std::string_view panelId);
    bool dockAtEdge(std::string_view panelId, DockEdge edge);
    bool dockAdjacentToPanel(std::string_view panelId, std::string_view targetPanelId, DockEdge direction);
    bool dockAsTab(std::string_view panelId, std::string_view targetPanelId);
    bool splitTab(std::string_view panelId, DockEdge direction);
    bool undock(std::string_view panelId);
    void undockAll();
    void applyDefaultDockedLayout();
    bool selectPanel(std::string_view panelId);

    [[nodiscard]] std::string serializeLayout() const;
    [[nodiscard]] static bool containsCenter(const DockNodeModel& node);
    [[nodiscard]] static std::string_view edgeName(DockEdge edge);
    [[nodiscard]] static std::string_view orientationName(DockOrientation orientation);

private:
    struct PanelLocation {
        std::shared_ptr<DockNodeModel> node;
        bool center{false};
    };

    [[nodiscard]] std::shared_ptr<DockNodeModel> makeLeafForPanel(std::string_view panelId) const;
    [[nodiscard]] std::shared_ptr<DockNodeModel> findParent(const std::shared_ptr<DockNodeModel>& target) const;
    [[nodiscard]] std::shared_ptr<DockNodeModel> findParentRecursive(const std::shared_ptr<DockNodeModel>& current,
                                                                     const std::shared_ptr<DockNodeModel>& target) const;
    [[nodiscard]] std::shared_ptr<DockNodeModel> findAdjacentNode(DockEdge edge) const;
    [[nodiscard]] std::shared_ptr<DockNodeModel> createEdgeSplit(std::shared_ptr<DockNodeModel> target,
                                                                 std::shared_ptr<DockNodeModel> newLeaf,
                                                                 DockEdge edge,
                                                                 double fraction) const;
    [[nodiscard]] std::shared_ptr<DockNodeModel> createEdgeZoneSplit(std::shared_ptr<DockNodeModel> target,
                                                                     std::shared_ptr<DockNodeModel> newLeaf,
                                                                     DockEdge edge,
                                                                     double fraction) const;
    [[nodiscard]] static double edgeFraction(DockEdge edge);
    [[nodiscard]] static DockOrientation splitOrientation(DockEdge edge);
    [[nodiscard]] static bool newLeafIsFirst(DockEdge edge);
    void replaceNode(const std::shared_ptr<DockNodeModel>& oldNode,
                     const std::shared_ptr<DockNodeModel>& newNode);
    void collapseEmptyLeaf(const std::shared_ptr<DockNodeModel>& leaf);
    void rebuildLocations();
    void rebuildLocationsRecursive(const std::shared_ptr<DockNodeModel>& node);
    [[nodiscard]] std::string serializeNode(const DockNodeModel& node) const;
    [[nodiscard]] static std::string escapeJson(std::string_view value);

    std::shared_ptr<DockNodeModel> root_;
    std::shared_ptr<DockNodeModel> center_;
    std::unordered_map<std::string, std::string> panelTitles_;
    std::unordered_map<std::string, PanelLocation> panelLocations_;
};

} // namespace libreshockwave::editor::docking
