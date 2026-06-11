#include "libreshockwave/editor/docking/DockingModels.hpp"

#include <algorithm>
#include <iomanip>
#include <sstream>
#include <utility>

namespace libreshockwave::editor::docking {
namespace {

std::string key(std::string_view value) {
    return std::string(value);
}

} // namespace

std::shared_ptr<DockNodeModel> DockNodeModel::center() {
    auto node = std::make_shared<DockNodeModel>();
    node->kind = DockNodeKind::Center;
    return node;
}

std::shared_ptr<DockNodeModel> DockNodeModel::leaf() {
    auto node = std::make_shared<DockNodeModel>();
    node->kind = DockNodeKind::Leaf;
    return node;
}

std::shared_ptr<DockNodeModel> DockNodeModel::split(std::shared_ptr<DockNodeModel> first,
                                                    std::shared_ptr<DockNodeModel> second,
                                                    DockOrientation orientation,
                                                    double dividerFraction) {
    auto node = std::make_shared<DockNodeModel>();
    node->kind = DockNodeKind::Split;
    node->first = std::move(first);
    node->second = std::move(second);
    node->orientation = orientation;
    node->dividerFraction = dividerFraction;
    return node;
}

bool DockNodeModel::hasTabs() const {
    return !tabs.empty();
}

bool DockNodeModel::hasTab(std::string_view panelId) const {
    return findTab(panelId) != nullptr;
}

std::vector<std::string> DockNodeModel::panelIds() const {
    std::vector<std::string> ids;
    ids.reserve(tabs.size());
    for (const auto& tab : tabs) {
        ids.push_back(tab.panelId);
    }
    return ids;
}

const DockPanelTab* DockNodeModel::findTab(std::string_view panelId) const {
    const auto found = std::find_if(tabs.begin(), tabs.end(), [panelId](const DockPanelTab& tab) {
        return tab.panelId == panelId;
    });
    return found == tabs.end() ? nullptr : &*found;
}

const DockPanelTab* DockNodeModel::selectedTab() const {
    if (selectedIndex < 0 || selectedIndex >= static_cast<int>(tabs.size())) {
        return nullptr;
    }
    return &tabs[static_cast<std::size_t>(selectedIndex)];
}

bool DockNodeModel::addTab(DockPanelTab tab) {
    if (kind == DockNodeKind::Split || tab.panelId.empty() || hasTab(tab.panelId)) {
        return false;
    }
    tabs.push_back(std::move(tab));
    selectedIndex = static_cast<int>(tabs.size()) - 1;
    return true;
}

bool DockNodeModel::removeTab(std::string_view panelId) {
    const auto found = std::find_if(tabs.begin(), tabs.end(), [panelId](const DockPanelTab& tab) {
        return tab.panelId == panelId;
    });
    if (found == tabs.end()) {
        return false;
    }
    const auto erasedIndex = static_cast<int>(std::distance(tabs.begin(), found));
    tabs.erase(found);
    if (tabs.empty()) {
        selectedIndex = -1;
    } else if (selectedIndex >= static_cast<int>(tabs.size())) {
        selectedIndex = static_cast<int>(tabs.size()) - 1;
    } else if (selectedIndex > erasedIndex) {
        --selectedIndex;
    }
    return true;
}

bool DockNodeModel::selectTab(std::string_view panelId) {
    for (int index = 0; index < static_cast<int>(tabs.size()); ++index) {
        if (tabs[static_cast<std::size_t>(index)].panelId == panelId) {
            selectedIndex = index;
            return true;
        }
    }
    return false;
}

DockingLayoutModel::DockingLayoutModel()
    : root_(DockNodeModel::center()),
      center_(root_) {}

void DockingLayoutModel::registerPanel(std::string panelId, std::string title) {
    if (panelId.empty()) {
        return;
    }
    panelTitles_[std::move(panelId)] = std::move(title);
}

bool DockingLayoutModel::hasRegisteredPanel(std::string_view panelId) const {
    return panelTitles_.contains(key(panelId));
}

std::optional<std::string> DockingLayoutModel::panelTitle(std::string_view panelId) const {
    const auto found = panelTitles_.find(key(panelId));
    if (found == panelTitles_.end()) {
        return std::nullopt;
    }
    return found->second;
}

const DockNodeModel& DockingLayoutModel::root() const {
    return *root_;
}

const DockNodeModel& DockingLayoutModel::center() const {
    return *center_;
}

const DockNodeModel* DockingLayoutModel::nodeForPanel(std::string_view panelId) const {
    const auto found = panelLocations_.find(key(panelId));
    return found == panelLocations_.end() ? nullptr : found->second.node.get();
}

bool DockingLayoutModel::isDocked(std::string_view panelId) const {
    return panelLocations_.contains(key(panelId));
}

std::vector<std::string> DockingLayoutModel::dockedPanelIds() const {
    std::vector<std::string> ids;
    ids.reserve(panelLocations_.size());
    for (const auto& [panelId, location] : panelLocations_) {
        (void)location;
        ids.push_back(panelId);
    }
    std::sort(ids.begin(), ids.end());
    return ids;
}

bool DockingLayoutModel::dockCenter(std::string_view panelId) {
    if (!hasRegisteredPanel(panelId)) {
        return false;
    }
    if (isDocked(panelId)) {
        undock(panelId);
    }
    const auto title = panelTitle(panelId).value_or(key(panelId));
    if (!center_->addTab(DockPanelTab{key(panelId), title})) {
        return false;
    }
    panelLocations_[key(panelId)] = PanelLocation{center_, true};
    return true;
}

bool DockingLayoutModel::dockAtEdge(std::string_view panelId, DockEdge edge) {
    if (!hasRegisteredPanel(panelId)) {
        return false;
    }
    if (isDocked(panelId)) {
        undock(panelId);
    }

    auto newLeaf = makeLeafForPanel(panelId);
    panelLocations_[key(panelId)] = PanelLocation{newLeaf, false};

    auto edgeZone = findAdjacentNode(edge);
    if (edgeZone) {
        auto split = createEdgeZoneSplit(edgeZone, newLeaf, edge, 0.5);
        replaceNode(edgeZone, split);
    } else {
        auto split = createEdgeSplit(center_, newLeaf, edge, edgeFraction(edge));
        replaceNode(center_, split);
    }
    rebuildLocations();
    return true;
}

bool DockingLayoutModel::dockAdjacentToPanel(std::string_view panelId,
                                             std::string_view targetPanelId,
                                             DockEdge direction) {
    if (panelId == targetPanelId) {
        return false;
    }
    if (!hasRegisteredPanel(panelId) || !isDocked(targetPanelId)) {
        return false;
    }
    auto targetNode = panelLocations_.at(key(targetPanelId)).node;
    if (!targetNode || targetNode->kind != DockNodeKind::Leaf) {
        return false;
    }
    if (isDocked(panelId)) {
        undock(panelId);
    }

    auto newLeaf = makeLeafForPanel(panelId);
    const auto orientation = splitOrientation(direction);
    const bool newFirst = newLeafIsFirst(direction);
    auto split = newFirst
        ? DockNodeModel::split(newLeaf, targetNode, orientation, 0.5)
        : DockNodeModel::split(targetNode, newLeaf, orientation, 0.5);
    replaceNode(targetNode, split);
    rebuildLocations();
    return true;
}

bool DockingLayoutModel::dockAsTab(std::string_view panelId, std::string_view targetPanelId) {
    if (panelId == targetPanelId) {
        return false;
    }
    if (!hasRegisteredPanel(panelId) || !isDocked(targetPanelId)) {
        return false;
    }
    auto targetNode = panelLocations_.at(key(targetPanelId)).node;
    if (!targetNode || targetNode->kind == DockNodeKind::Split) {
        return false;
    }
    if (isDocked(panelId)) {
        undock(panelId);
    }
    const auto title = panelTitle(panelId).value_or(key(panelId));
    if (!targetNode->addTab(DockPanelTab{key(panelId), title})) {
        return false;
    }
    rebuildLocations();
    return true;
}

bool DockingLayoutModel::splitTab(std::string_view panelId, DockEdge direction) {
    if (!isDocked(panelId)) {
        return false;
    }
    auto sourceNode = panelLocations_.at(key(panelId)).node;
    if (!sourceNode || sourceNode->kind != DockNodeKind::Leaf || sourceNode->tabs.size() <= 1) {
        return false;
    }
    const auto* tab = sourceNode->findTab(panelId);
    if (tab == nullptr) {
        return false;
    }
    DockPanelTab movedTab = *tab;
    sourceNode->removeTab(panelId);

    auto newLeaf = DockNodeModel::leaf();
    newLeaf->addTab(std::move(movedTab));
    const auto orientation = splitOrientation(direction);
    const bool newFirst = newLeafIsFirst(direction);
    auto split = newFirst
        ? DockNodeModel::split(newLeaf, sourceNode, orientation, 0.5)
        : DockNodeModel::split(sourceNode, newLeaf, orientation, 0.5);
    replaceNode(sourceNode, split);
    rebuildLocations();
    return true;
}

bool DockingLayoutModel::undock(std::string_view panelId) {
    const auto found = panelLocations_.find(key(panelId));
    if (found == panelLocations_.end()) {
        return false;
    }
    auto node = found->second.node;
    panelLocations_.erase(found);
    if (!node) {
        return false;
    }
    node->removeTab(panelId);
    if (node->kind == DockNodeKind::Leaf && node->tabs.empty()) {
        collapseEmptyLeaf(node);
    }
    rebuildLocations();
    return true;
}

void DockingLayoutModel::undockAll() {
    const auto ids = dockedPanelIds();
    for (const auto& id : ids) {
        undock(id);
    }
}

void DockingLayoutModel::applyDefaultDockedLayout() {
    undockAll();
    dockAtEdge("score", DockEdge::Bottom);
    dockAtEdge("cast", DockEdge::Bottom);
    dockAtEdge("script", DockEdge::Bottom);
    dockAtEdge("message", DockEdge::Bottom);
    dockAtEdge("tool-palette", DockEdge::Left);
    dockAtEdge("property-inspector", DockEdge::Right);
    dockAdjacentToPanel("bytecode-debugger", "property-inspector", DockEdge::Bottom);
}

bool DockingLayoutModel::selectPanel(std::string_view panelId) {
    const auto found = panelLocations_.find(key(panelId));
    if (found == panelLocations_.end() || !found->second.node) {
        return false;
    }
    return found->second.node->selectTab(panelId);
}

std::string DockingLayoutModel::serializeLayout() const {
    return serializeNode(*root_);
}

bool DockingLayoutModel::containsCenter(const DockNodeModel& node) {
    if (node.kind == DockNodeKind::Center) {
        return true;
    }
    if (node.kind == DockNodeKind::Split) {
        return (node.first && containsCenter(*node.first)) ||
               (node.second && containsCenter(*node.second));
    }
    return false;
}

std::string_view DockingLayoutModel::edgeName(DockEdge edge) {
    switch (edge) {
        case DockEdge::Left: return "LEFT";
        case DockEdge::Right: return "RIGHT";
        case DockEdge::Top: return "TOP";
        case DockEdge::Bottom: return "BOTTOM";
    }
    return "LEFT";
}

std::string_view DockingLayoutModel::orientationName(DockOrientation orientation) {
    switch (orientation) {
        case DockOrientation::Horizontal: return "horizontal";
        case DockOrientation::Vertical: return "vertical";
    }
    return "horizontal";
}

std::shared_ptr<DockNodeModel> DockingLayoutModel::makeLeafForPanel(std::string_view panelId) const {
    auto leaf = DockNodeModel::leaf();
    leaf->addTab(DockPanelTab{key(panelId), panelTitle(panelId).value_or(key(panelId))});
    return leaf;
}

std::shared_ptr<DockNodeModel> DockingLayoutModel::findParent(
    const std::shared_ptr<DockNodeModel>& target) const {
    return findParentRecursive(root_, target);
}

std::shared_ptr<DockNodeModel> DockingLayoutModel::findParentRecursive(
    const std::shared_ptr<DockNodeModel>& current,
    const std::shared_ptr<DockNodeModel>& target) const {
    if (!current || current->kind != DockNodeKind::Split) {
        return nullptr;
    }
    if (current->first == target || current->second == target) {
        return current;
    }
    if (auto found = findParentRecursive(current->first, target)) {
        return found;
    }
    return findParentRecursive(current->second, target);
}

std::shared_ptr<DockNodeModel> DockingLayoutModel::findAdjacentNode(DockEdge edge) const {
    auto node = center_;
    std::shared_ptr<DockNodeModel> adjacent;
    while (true) {
        auto parent = findParent(node);
        if (!parent) {
            return adjacent;
        }

        switch (edge) {
            case DockEdge::Left:
                if (parent->orientation == DockOrientation::Horizontal && parent->second == node) {
                    adjacent = parent->first;
                }
                break;
            case DockEdge::Right:
                if (parent->orientation == DockOrientation::Horizontal && parent->first == node) {
                    adjacent = parent->second;
                }
                break;
            case DockEdge::Top:
                if (parent->orientation == DockOrientation::Vertical && parent->second == node) {
                    adjacent = parent->first;
                }
                break;
            case DockEdge::Bottom:
                if (parent->orientation == DockOrientation::Vertical && parent->first == node) {
                    adjacent = parent->second;
                }
                break;
        }

        node = parent;
    }
}

std::shared_ptr<DockNodeModel> DockingLayoutModel::createEdgeSplit(
    std::shared_ptr<DockNodeModel> target,
    std::shared_ptr<DockNodeModel> newLeaf,
    DockEdge edge,
    double fraction) const {
    const bool newFirst = newLeafIsFirst(edge);
    return newFirst
        ? DockNodeModel::split(std::move(newLeaf), std::move(target), splitOrientation(edge), fraction)
        : DockNodeModel::split(std::move(target), std::move(newLeaf), splitOrientation(edge), fraction);
}

std::shared_ptr<DockNodeModel> DockingLayoutModel::createEdgeZoneSplit(
    std::shared_ptr<DockNodeModel> target,
    std::shared_ptr<DockNodeModel> newLeaf,
    DockEdge edge,
    double fraction) const {
    const auto orientation = (edge == DockEdge::Left || edge == DockEdge::Right)
        ? DockOrientation::Vertical
        : DockOrientation::Horizontal;
    return DockNodeModel::split(std::move(target), std::move(newLeaf), orientation, fraction);
}

double DockingLayoutModel::edgeFraction(DockEdge edge) {
    switch (edge) {
        case DockEdge::Left:
        case DockEdge::Top:
            return 0.15;
        case DockEdge::Right:
            return 0.85;
        case DockEdge::Bottom:
            return 0.70;
    }
    return 0.5;
}

DockOrientation DockingLayoutModel::splitOrientation(DockEdge edge) {
    return (edge == DockEdge::Left || edge == DockEdge::Right)
        ? DockOrientation::Horizontal
        : DockOrientation::Vertical;
}

bool DockingLayoutModel::newLeafIsFirst(DockEdge edge) {
    return edge == DockEdge::Left || edge == DockEdge::Top;
}

void DockingLayoutModel::replaceNode(const std::shared_ptr<DockNodeModel>& oldNode,
                                     const std::shared_ptr<DockNodeModel>& newNode) {
    if (root_ == oldNode) {
        root_ = newNode;
        return;
    }
    auto parent = findParent(oldNode);
    if (!parent) {
        return;
    }
    if (parent->first == oldNode) {
        parent->first = newNode;
    } else if (parent->second == oldNode) {
        parent->second = newNode;
    }
}

void DockingLayoutModel::collapseEmptyLeaf(const std::shared_ptr<DockNodeModel>& leaf) {
    auto parent = findParent(leaf);
    if (!parent) {
        root_ = center_;
        return;
    }
    auto sibling = parent->first == leaf ? parent->second : parent->first;
    replaceNode(parent, sibling);
}

void DockingLayoutModel::rebuildLocations() {
    panelLocations_.clear();
    rebuildLocationsRecursive(root_);
}

void DockingLayoutModel::rebuildLocationsRecursive(const std::shared_ptr<DockNodeModel>& node) {
    if (!node) {
        return;
    }
    if (node->kind == DockNodeKind::Leaf || node->kind == DockNodeKind::Center) {
        const bool isCenter = node->kind == DockNodeKind::Center;
        for (const auto& tab : node->tabs) {
            panelLocations_[tab.panelId] = PanelLocation{node, isCenter};
        }
        return;
    }
    rebuildLocationsRecursive(node->first);
    rebuildLocationsRecursive(node->second);
}

std::string DockingLayoutModel::serializeNode(const DockNodeModel& node) const {
    std::ostringstream out;
    if (node.kind == DockNodeKind::Split) {
        out << "{\"type\":\"split\",\"orientation\":\""
            << orientationName(node.orientation)
            << "\",\"fraction\":"
            << std::fixed << std::setprecision(2) << node.dividerFraction
            << ",\"first\":"
            << (node.first ? serializeNode(*node.first) : "{}")
            << ",\"second\":"
            << (node.second ? serializeNode(*node.second) : "{}")
            << "}";
        return out.str();
    }

    if (node.kind == DockNodeKind::Leaf) {
        out << "{\"type\":\"leaf\",\"tabs\":[";
        for (std::size_t index = 0; index < node.tabs.size(); ++index) {
            if (index > 0) {
                out << ",";
            }
            out << "\"" << escapeJson(node.tabs[index].panelId) << "\"";
        }
        out << "]}";
        return out.str();
    }

    out << "{\"type\":\"center\"";
    if (!node.tabs.empty()) {
        out << ",\"centerTabs\":[";
        for (std::size_t index = 0; index < node.tabs.size(); ++index) {
            if (index > 0) {
                out << ",";
            }
            out << "\"" << escapeJson(node.tabs[index].panelId) << "\"";
        }
        out << "]";
    }
    out << "}";
    return out.str();
}

std::string DockingLayoutModel::escapeJson(std::string_view value) {
    std::string escaped;
    escaped.reserve(value.size());
    for (const char ch : value) {
        switch (ch) {
            case '\\':
                escaped += "\\\\";
                break;
            case '"':
                escaped += "\\\"";
                break;
            case '\n':
                escaped += "\\n";
                break;
            case '\r':
                escaped += "\\r";
                break;
            case '\t':
                escaped += "\\t";
                break;
            default:
                escaped += ch;
                break;
        }
    }
    return escaped;
}

} // namespace libreshockwave::editor::docking
