#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <string>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"

namespace libreshockwave::player::timeout {

struct TimeoutEntry {
    std::string name;
    int periodMs{0};
    std::string handler;
    lingo::Datum target;
    bool persistent{false};
    bool oneShot{false};
    long long lastFiredMs{0};
};

class TimeoutManager {
public:
    [[nodiscard]] lingo::Datum createTimeout(std::string name,
                                             int periodMs,
                                             std::string handler,
                                             lingo::Datum target);
    [[nodiscard]] lingo::Datum createTimeout(std::string name,
                                             int periodMs,
                                             std::string handler,
                                             lingo::Datum target,
                                             bool oneShot);
    void forgetTimeout(const std::string& name);
    [[nodiscard]] bool timeoutExists(const std::string& name) const;
    [[nodiscard]] lingo::Datum getTimeoutProp(const std::string& name, const std::string& prop) const;
    bool setTimeoutProp(const std::string& name, const std::string& prop, lingo::Datum value);
    [[nodiscard]] std::vector<std::string> getTimeoutNames() const;
    [[nodiscard]] int getTimeoutCount() const;
    void clear();

    [[nodiscard]] const TimeoutEntry* getEntry(const std::string& name) const;

private:
    [[nodiscard]] static long long currentTimeMillis();

    std::map<std::string, TimeoutEntry> timeouts_;
};

} // namespace libreshockwave::player::timeout
