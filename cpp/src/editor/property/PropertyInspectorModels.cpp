#include "libreshockwave/editor/property/PropertyInspectorModels.hpp"

#include <algorithm>
#include <utility>

namespace libreshockwave::editor::property {

PropertyGridModel::PropertyGridModel(std::vector<std::string> labels) {
    rows_.reserve(labels.size());
    for (auto& label : labels) {
        rows_.push_back(PropertyRow{std::move(label), "-", false});
    }
}

const std::vector<PropertyRow>& PropertyGridModel::rows() const {
    return rows_;
}

std::size_t PropertyGridModel::rowCount() const {
    return rows_.size();
}

const PropertyRow* PropertyGridModel::row(std::size_t index) const {
    if (index >= rows_.size()) {
        return nullptr;
    }
    return &rows_[index];
}

const PropertyRow* PropertyGridModel::findRow(std::string_view label) const {
    const auto found = std::find_if(rows_.begin(), rows_.end(), [label](const PropertyRow& row) {
        return row.label == label;
    });
    return found == rows_.end() ? nullptr : &*found;
}

std::vector<std::string> PropertyGridModel::labels() const {
    std::vector<std::string> result;
    result.reserve(rows_.size());
    for (const auto& row : rows_) {
        result.push_back(row.label);
    }
    return result;
}

bool PropertyGridModel::setValue(std::string_view label, std::string value) {
    const auto found = std::find_if(rows_.begin(), rows_.end(), [label](const PropertyRow& row) {
        return row.label == label;
    });
    if (found == rows_.end()) {
        return false;
    }
    found->value = std::move(value);
    return true;
}

bool PropertyGridModel::setValueAt(std::size_t index, std::string value) {
    if (index >= rows_.size()) {
        return false;
    }
    rows_[index].value = std::move(value);
    return true;
}

void PropertyGridModel::resetValues(std::string value) {
    for (auto& row : rows_) {
        row.value = value;
    }
}

BehaviorListModel::BehaviorListModel() {
    clearToPlaceholder();
}

std::string_view BehaviorListModel::defaultPlaceholder() {
    return "(Select a sprite to see its behaviors)";
}

const std::vector<std::string>& BehaviorListModel::items() const {
    return items_;
}

bool BehaviorListModel::canAdd() const {
    return canAdd_;
}

bool BehaviorListModel::canRemove() const {
    return canRemove_;
}

void BehaviorListModel::setActionsEnabled(bool canAdd, bool canRemove) {
    canAdd_ = canAdd;
    canRemove_ = canRemove;
}

void BehaviorListModel::setBehaviors(std::vector<std::string> behaviors) {
    items_ = std::move(behaviors);
}

void BehaviorListModel::clearToPlaceholder(std::string placeholder) {
    items_.clear();
    items_.push_back(std::move(placeholder));
    canAdd_ = false;
    canRemove_ = false;
}

PropertyInspectorModel::PropertyInspectorModel()
    : spriteTab_(makeSpriteTab()),
      memberTab_(makeMemberTab()),
      movieTab_(makeMovieTab()) {}

PropertyGridModel PropertyInspectorModel::makeSpriteTab() {
    return PropertyGridModel({
        "Sprite:",
        "Member:",
        "X (locH):",
        "Y (locV):",
        "Width:",
        "Height:",
        "Ink:",
        "Blend:",
        "locZ:",
        "Visible:",
        "Moveable:",
        "Editable:",
    });
}

PropertyGridModel PropertyInspectorModel::makeMemberTab() {
    return PropertyGridModel({
        "Name:",
        "Number:",
        "Cast:",
        "Type:",
        "Size:",
        "Modified:",
        "Comments:",
    });
}

PropertyGridModel PropertyInspectorModel::makeMovieTab() {
    return PropertyGridModel({
        "Movie Name:",
        "Stage Width:",
        "Stage Height:",
        "Stage Color:",
        "Palette:",
        "Tempo:",
        "Total Frames:",
        "Total Casts:",
        "Copyright:",
    });
}

std::vector<PropertyInspectorTab> PropertyInspectorModel::tabOrder() {
    return {
        PropertyInspectorTab::Sprite,
        PropertyInspectorTab::Member,
        PropertyInspectorTab::Behavior,
        PropertyInspectorTab::Movie,
    };
}

std::string_view PropertyInspectorModel::tabName(PropertyInspectorTab tab) {
    switch (tab) {
        case PropertyInspectorTab::Sprite: return "Sprite";
        case PropertyInspectorTab::Member: return "Member";
        case PropertyInspectorTab::Behavior: return "Behavior";
        case PropertyInspectorTab::Movie: return "Movie";
    }
    return {};
}

const PropertyGridModel& PropertyInspectorModel::spriteTab() const {
    return spriteTab_;
}

const PropertyGridModel& PropertyInspectorModel::memberTab() const {
    return memberTab_;
}

const BehaviorListModel& PropertyInspectorModel::behaviorTab() const {
    return behaviorTab_;
}

const PropertyGridModel& PropertyInspectorModel::movieTab() const {
    return movieTab_;
}

PropertyGridModel& PropertyInspectorModel::mutableSpriteTab() {
    return spriteTab_;
}

PropertyGridModel& PropertyInspectorModel::mutableMemberTab() {
    return memberTab_;
}

BehaviorListModel& PropertyInspectorModel::mutableBehaviorTab() {
    return behaviorTab_;
}

PropertyGridModel& PropertyInspectorModel::mutableMovieTab() {
    return movieTab_;
}

PropertyInspectorTab PropertyInspectorModel::activeTab() const {
    return activeTab_;
}

void PropertyInspectorModel::setActiveTab(PropertyInspectorTab tab) {
    activeTab_ = tab;
}

void PropertyInspectorModel::handleSelectionChanged(const selection::SelectionEvent& event) {
    switch (event.type) {
        case selection::SelectionType::Sprite:
            activeTab_ = PropertyInspectorTab::Sprite;
            break;
        case selection::SelectionType::CastMember:
            activeTab_ = PropertyInspectorTab::Member;
            break;
        case selection::SelectionType::None:
        case selection::SelectionType::Frame:
        case selection::SelectionType::ScoreCell:
            break;
    }
}

} // namespace libreshockwave::editor::property
