#include "libreshockwave/editor/debug/DebugTableModels.hpp"

#include <array>
#include <utility>

#include "libreshockwave/lingo/vm/datum/DatumFormatter.hpp"

namespace libreshockwave::editor::debug {
namespace {

constexpr std::array<const char*, 3> STACK_COLUMNS{"#", "Type", "Value"};
constexpr std::array<const char*, 3> VARIABLES_COLUMNS{"Name", "Type", "Value"};
constexpr std::array<const char*, 3> WATCHES_COLUMNS{"Expression", "Type", "Value"};
constexpr std::array<const char*, 5> TIMEOUT_COLUMNS{"Name", "Period (ms)", "Handler", "Target", "Persistent"};

template <std::size_t N>
[[nodiscard]] std::string columnNameAt(const std::array<const char*, N>& columns, int column) {
    if (column < 0 || column >= static_cast<int>(columns.size())) {
        return {};
    }
    return columns[static_cast<std::size_t>(column)];
}

template <typename T>
[[nodiscard]] const T* rowAt(const std::vector<T>& rows, int row) {
    if (row < 0 || row >= static_cast<int>(rows.size())) {
        return nullptr;
    }
    return &rows[static_cast<std::size_t>(row)];
}

} // namespace

void StackTableModel::setStack(std::vector<lingo::Datum> stack) {
    stack_ = std::move(stack);
}

int StackTableModel::rowCount() const {
    return static_cast<int>(stack_.size());
}

int StackTableModel::columnCount() const {
    return static_cast<int>(STACK_COLUMNS.size());
}

std::string StackTableModel::columnName(int column) const {
    return columnNameAt(STACK_COLUMNS, column);
}

std::string StackTableModel::valueAt(int row, int column) const {
    const auto* value = datum(row);
    if (!value) {
        return {};
    }

    switch (column) {
        case 0:
            return std::to_string(row);
        case 1:
            return lingo::vm::datum::getTypeName(*value);
        case 2:
            return lingo::vm::datum::format(*value);
        default:
            return {};
    }
}

const lingo::Datum* StackTableModel::datum(int row) const {
    return rowAt(stack_, row);
}

void VariablesTableModel::setVariables(const std::map<std::string, lingo::Datum>& variables) {
    variables_.assign(variables.begin(), variables.end());
}

void VariablesTableModel::setVariables(std::vector<std::pair<std::string, lingo::Datum>> variables) {
    variables_ = std::move(variables);
}

int VariablesTableModel::rowCount() const {
    return static_cast<int>(variables_.size());
}

int VariablesTableModel::columnCount() const {
    return static_cast<int>(VARIABLES_COLUMNS.size());
}

std::string VariablesTableModel::columnName(int column) const {
    return columnNameAt(VARIABLES_COLUMNS, column);
}

std::string VariablesTableModel::valueAt(int row, int column) const {
    const auto* entry = rowAt(variables_, row);
    if (!entry) {
        return {};
    }

    switch (column) {
        case 0:
            return entry->first;
        case 1:
            return lingo::vm::datum::getTypeName(entry->second);
        case 2:
            return lingo::vm::datum::format(entry->second);
        default:
            return {};
    }
}

const lingo::Datum* VariablesTableModel::datum(int row) const {
    const auto* entry = rowAt(variables_, row);
    return entry ? &entry->second : nullptr;
}

std::string VariablesTableModel::name(int row) const {
    const auto* entry = rowAt(variables_, row);
    return entry ? entry->first : std::string{};
}

void WatchesTableModel::setWatches(std::vector<player::debug::WatchExpression> watches) {
    watches_ = std::move(watches);
}

int WatchesTableModel::rowCount() const {
    return static_cast<int>(watches_.size());
}

int WatchesTableModel::columnCount() const {
    return static_cast<int>(WATCHES_COLUMNS.size());
}

std::string WatchesTableModel::columnName(int column) const {
    return columnNameAt(WATCHES_COLUMNS, column);
}

std::string WatchesTableModel::valueAt(int row, int column) const {
    const auto* watchValue = watch(row);
    if (!watchValue) {
        return {};
    }

    switch (column) {
        case 0:
            return watchValue->expression;
        case 1:
            return watchValue->getTypeName();
        case 2:
            return watchValue->getResultDisplay();
        default:
            return {};
    }
}

const player::debug::WatchExpression* WatchesTableModel::watch(int row) const {
    return rowAt(watches_, row);
}

TimeoutSnapshot TimeoutSnapshot::fromEntry(const player::timeout::TimeoutEntry& entry) {
    return TimeoutSnapshot{entry.name, entry.periodMs, entry.handler, entry.target, entry.persistent};
}

void TimeoutTableModel::setTimeouts(std::vector<TimeoutSnapshot> timeouts) {
    timeouts_ = std::move(timeouts);
}

void TimeoutTableModel::setTimeoutEntries(const std::vector<player::timeout::TimeoutEntry>& entries) {
    timeouts_.clear();
    timeouts_.reserve(entries.size());
    for (const auto& entry : entries) {
        timeouts_.push_back(TimeoutSnapshot::fromEntry(entry));
    }
}

int TimeoutTableModel::rowCount() const {
    return static_cast<int>(timeouts_.size());
}

int TimeoutTableModel::columnCount() const {
    return static_cast<int>(TIMEOUT_COLUMNS.size());
}

std::string TimeoutTableModel::columnName(int column) const {
    return columnNameAt(TIMEOUT_COLUMNS, column);
}

std::string TimeoutTableModel::valueAt(int row, int column) const {
    const auto* timeout = rowAt(timeouts_, row);
    if (!timeout) {
        return {};
    }

    switch (column) {
        case 0:
            return timeout->name;
        case 1:
            return std::to_string(timeout->periodMs);
        case 2:
            return timeout->handler;
        case 3:
            return lingo::vm::datum::format(timeout->target);
        case 4:
            return timeout->persistent ? "Yes" : "No";
        default:
            return {};
    }
}

const lingo::Datum* TimeoutTableModel::datum(int row) const {
    const auto* timeout = rowAt(timeouts_, row);
    return timeout ? &timeout->target : nullptr;
}

std::string TimeoutTableModel::name(int row) const {
    const auto* timeout = rowAt(timeouts_, row);
    return timeout ? timeout->name : std::string{};
}

} // namespace libreshockwave::editor::debug
