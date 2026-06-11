#pragma once

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/editor/selection/SelectionEvent.hpp"

namespace libreshockwave::editor::property {

enum class PropertyInspectorTab {
    Sprite,
    Member,
    Behavior,
    Movie
};

struct PropertyRow {
    std::string label;
    std::string value{"-"};
    bool editable{false};

    friend bool operator==(const PropertyRow&, const PropertyRow&) = default;
};

class PropertyGridModel {
public:
    PropertyGridModel() = default;
    explicit PropertyGridModel(std::vector<std::string> labels);

    [[nodiscard]] const std::vector<PropertyRow>& rows() const;
    [[nodiscard]] std::size_t rowCount() const;
    [[nodiscard]] const PropertyRow* row(std::size_t index) const;
    [[nodiscard]] const PropertyRow* findRow(std::string_view label) const;
    [[nodiscard]] std::vector<std::string> labels() const;

    bool setValue(std::string_view label, std::string value);
    bool setValueAt(std::size_t index, std::string value);
    void resetValues(std::string value = "-");

private:
    std::vector<PropertyRow> rows_;
};

class BehaviorListModel {
public:
    BehaviorListModel();

    [[nodiscard]] static std::string_view defaultPlaceholder();
    [[nodiscard]] const std::vector<std::string>& items() const;
    [[nodiscard]] bool canAdd() const;
    [[nodiscard]] bool canRemove() const;

    void setActionsEnabled(bool canAdd, bool canRemove);
    void setBehaviors(std::vector<std::string> behaviors);
    void clearToPlaceholder(std::string placeholder = "(Select a sprite to see its behaviors)");

private:
    std::vector<std::string> items_;
    bool canAdd_{false};
    bool canRemove_{false};
};

class PropertyInspectorModel {
public:
    PropertyInspectorModel();

    [[nodiscard]] static PropertyGridModel makeSpriteTab();
    [[nodiscard]] static PropertyGridModel makeMemberTab();
    [[nodiscard]] static PropertyGridModel makeMovieTab();
    [[nodiscard]] static std::vector<PropertyInspectorTab> tabOrder();
    [[nodiscard]] static std::string_view tabName(PropertyInspectorTab tab);

    [[nodiscard]] const PropertyGridModel& spriteTab() const;
    [[nodiscard]] const PropertyGridModel& memberTab() const;
    [[nodiscard]] const BehaviorListModel& behaviorTab() const;
    [[nodiscard]] const PropertyGridModel& movieTab() const;
    [[nodiscard]] PropertyGridModel& mutableSpriteTab();
    [[nodiscard]] PropertyGridModel& mutableMemberTab();
    [[nodiscard]] BehaviorListModel& mutableBehaviorTab();
    [[nodiscard]] PropertyGridModel& mutableMovieTab();

    [[nodiscard]] PropertyInspectorTab activeTab() const;
    void setActiveTab(PropertyInspectorTab tab);
    void handleSelectionChanged(const selection::SelectionEvent& event);

private:
    PropertyGridModel spriteTab_;
    PropertyGridModel memberTab_;
    BehaviorListModel behaviorTab_;
    PropertyGridModel movieTab_;
    PropertyInspectorTab activeTab_{PropertyInspectorTab::Sprite};
};

} // namespace libreshockwave::editor::property
