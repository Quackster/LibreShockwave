#include "libreshockwave/lingo/vm/util/AncestorChainWalker.hpp"

#include <cctype>
#include <string>
#include <utility>

namespace libreshockwave::lingo::vm::util {
namespace {

bool equalsIgnoreCase(std::string_view lhs, std::string_view rhs) {
    if (lhs.size() != rhs.size()) {
        return false;
    }
    for (std::size_t index = 0; index < lhs.size(); ++index) {
        const auto left = static_cast<unsigned char>(lhs[index]);
        const auto right = static_cast<unsigned char>(rhs[index]);
        if (std::tolower(left) != std::tolower(right)) {
            return false;
        }
    }
    return true;
}

const Datum* exactProperty(const Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        return nullptr;
    }
    for (const auto& entry : instance.properties()) {
        if (entry.first == propName) {
            return &entry.second;
        }
    }
    return nullptr;
}

const Datum* caseInsensitiveProperty(const Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        return nullptr;
    }
    for (const auto& entry : instance.properties()) {
        if (equalsIgnoreCase(entry.first, propName)) {
            return &entry.second;
        }
    }
    return nullptr;
}

std::pair<std::string*, Datum*> mutableProperty(Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        return {nullptr, nullptr};
    }
    for (auto& entry : instance.properties()) {
        if (entry.first == propName) {
            return {&entry.first, &entry.second};
        }
    }
    for (auto& entry : instance.properties()) {
        if (equalsIgnoreCase(entry.first, propName)) {
            return {&entry.first, &entry.second};
        }
    }
    return {nullptr, nullptr};
}

} // namespace

Datum getProperty(const Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        if (auto ancestor = instance.ancestor()) {
            return Datum::scriptInstanceRef(std::move(ancestor));
        }
        return Datum::voidValue();
    }

    const auto* current = &instance;
    for (int depth = 0; current != nullptr && depth < MAX_ANCESTOR_DEPTH; ++depth) {
        if (const auto* exact = exactProperty(*current, propName)) {
            return *exact;
        }
        if (const auto* caseInsensitive = caseInsensitiveProperty(*current, propName)) {
            return *caseInsensitive;
        }

        current = current->ancestor().get();
    }

    return Datum::voidValue();
}

bool hasProperty(const Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        return instance.ancestor() != nullptr;
    }
    return findOwner(instance, propName) != nullptr;
}

Datum::ScriptInstanceRef* findOwner(Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        return instance.ancestor() != nullptr ? &instance : nullptr;
    }

    auto* current = &instance;
    for (int depth = 0; current != nullptr && depth < MAX_ANCESTOR_DEPTH; ++depth) {
        if (exactProperty(*current, propName) != nullptr ||
            caseInsensitiveProperty(*current, propName) != nullptr) {
            return current;
        }

        current = current->ancestor().get();
    }

    return nullptr;
}

const Datum::ScriptInstanceRef* findOwner(const Datum::ScriptInstanceRef& instance, std::string_view propName) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        return instance.ancestor() != nullptr ? &instance : nullptr;
    }

    const auto* current = &instance;
    for (int depth = 0; current != nullptr && depth < MAX_ANCESTOR_DEPTH; ++depth) {
        if (exactProperty(*current, propName) != nullptr ||
            caseInsensitiveProperty(*current, propName) != nullptr) {
            return current;
        }

        current = current->ancestor().get();
    }

    return nullptr;
}

std::shared_ptr<Datum::ScriptInstanceRef> getAncestorAtDepth(const Datum::ScriptInstanceRef& instance, int depth) {
    if (depth < 1) {
        return nullptr;
    }

    std::shared_ptr<Datum::ScriptInstanceRef> current;
    const Datum::ScriptInstanceRef* currentRaw = &instance;
    for (int index = 0; index < depth && index < MAX_ANCESTOR_DEPTH; ++index) {
        current = currentRaw->ancestor();
        if (!current) {
            return nullptr;
        }
        currentRaw = current.get();
    }
    return current;
}

void setProperty(Datum::ScriptInstanceRef& instance, std::string_view propName, Datum value) {
    if (equalsIgnoreCase(propName, "ancestor")) {
        if (value.type() == DatumType::ScriptInstanceRef) {
            instance.setAncestor(value.scriptInstancePtr());
        }
        return;
    }

    if (auto* owner = findOwner(instance, propName)) {
        auto [actualKey, actualValue] = mutableProperty(*owner, propName);
        if (actualKey != nullptr && actualValue != nullptr) {
            *actualValue = std::move(value);
        }
        return;
    }

    instance.properties().emplace_back(std::string(propName), std::move(value));
}

int getAncestorDepth(const Datum::ScriptInstanceRef& instance) {
    int depth = 0;
    const auto* current = &instance;
    for (int index = 0; current != nullptr && index < MAX_ANCESTOR_DEPTH; ++index) {
        auto ancestor = current->ancestor();
        if (!ancestor) {
            break;
        }
        ++depth;
        current = ancestor.get();
    }
    return depth;
}

} // namespace libreshockwave::lingo::vm::util
