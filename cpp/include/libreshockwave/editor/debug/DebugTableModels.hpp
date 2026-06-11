#pragma once

#include <map>
#include <string>
#include <utility>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/debug/WatchExpression.hpp"
#include "libreshockwave/player/timeout/TimeoutManager.hpp"

namespace libreshockwave::player {
class MovieProperties;
} // namespace libreshockwave::player

namespace libreshockwave::editor::debug {

class StackTableModel {
public:
    void setStack(std::vector<lingo::Datum> stack);

    [[nodiscard]] int rowCount() const;
    [[nodiscard]] int columnCount() const;
    [[nodiscard]] std::string columnName(int column) const;
    [[nodiscard]] std::string valueAt(int row, int column) const;
    [[nodiscard]] const lingo::Datum* datum(int row) const;

private:
    std::vector<lingo::Datum> stack_;
};

class VariablesTableModel {
public:
    void setVariables(const std::map<std::string, lingo::Datum>& variables);
    void setVariables(std::vector<std::pair<std::string, lingo::Datum>> variables);

    [[nodiscard]] int rowCount() const;
    [[nodiscard]] int columnCount() const;
    [[nodiscard]] std::string columnName(int column) const;
    [[nodiscard]] std::string valueAt(int row, int column) const;
    [[nodiscard]] const lingo::Datum* datum(int row) const;
    [[nodiscard]] std::string name(int row) const;

private:
    std::vector<std::pair<std::string, lingo::Datum>> variables_;
};

class WatchesTableModel {
public:
    void setWatches(std::vector<player::debug::WatchExpression> watches);

    [[nodiscard]] int rowCount() const;
    [[nodiscard]] int columnCount() const;
    [[nodiscard]] std::string columnName(int column) const;
    [[nodiscard]] std::string valueAt(int row, int column) const;
    [[nodiscard]] const player::debug::WatchExpression* watch(int row) const;

private:
    std::vector<player::debug::WatchExpression> watches_;
};

struct TimeoutSnapshot {
    std::string name;
    int periodMs{0};
    std::string handler;
    lingo::Datum target;
    bool persistent{false};

    [[nodiscard]] static TimeoutSnapshot fromEntry(const player::timeout::TimeoutEntry& entry);

    friend bool operator==(const TimeoutSnapshot&, const TimeoutSnapshot&) = default;
};

class TimeoutTableModel {
public:
    void setTimeouts(std::vector<TimeoutSnapshot> timeouts);
    void setTimeoutEntries(const std::vector<player::timeout::TimeoutEntry>& entries);

    [[nodiscard]] int rowCount() const;
    [[nodiscard]] int columnCount() const;
    [[nodiscard]] std::string columnName(int column) const;
    [[nodiscard]] std::string valueAt(int row, int column) const;
    [[nodiscard]] const lingo::Datum* datum(int row) const;
    [[nodiscard]] std::string name(int row) const;

private:
    std::vector<TimeoutSnapshot> timeouts_;
};

class MoviePropertiesTableModel {
public:
    void setProperties(std::vector<std::pair<std::string, lingo::Datum>> properties);

    [[nodiscard]] int rowCount() const;
    [[nodiscard]] int columnCount() const;
    [[nodiscard]] std::string columnName(int column) const;
    [[nodiscard]] std::string valueAt(int row, int column) const;
    [[nodiscard]] const lingo::Datum* datum(int row) const;
    [[nodiscard]] std::string name(int row) const;

private:
    std::vector<std::pair<std::string, lingo::Datum>> properties_;
};

struct DebugObjectsSnapshot {
    std::vector<TimeoutSnapshot> timeouts;
    std::map<std::string, lingo::Datum> globals;
    std::vector<std::pair<std::string, lingo::Datum>> movieProperties;

    friend bool operator==(const DebugObjectsSnapshot&, const DebugObjectsSnapshot&) = default;
};

class DebugObjectsSnapshotBuilder {
public:
    [[nodiscard]] static std::vector<std::string> moviePropertyNames();
    [[nodiscard]] static std::vector<std::pair<std::string, lingo::Datum>>
    moviePropertyEntries(const player::MovieProperties& movieProperties);
    [[nodiscard]] static std::vector<TimeoutSnapshot>
    timeoutSnapshots(const player::timeout::TimeoutManager& timeoutManager);
    [[nodiscard]] static DebugObjectsSnapshot snapshot(const player::timeout::TimeoutManager& timeoutManager,
                                                       const std::map<std::string, lingo::Datum>& globals,
                                                       const player::MovieProperties& movieProperties);
};

} // namespace libreshockwave::editor::debug
