#include "libreshockwave/editor/selection/SelectionManager.hpp"

#include <algorithm>

namespace libreshockwave::editor::selection {

void SelectionManager::addListener(SelectionListener* listener) {
    listeners_.push_back(listener);
}

void SelectionManager::removeListener(SelectionListener* listener) {
    const auto found = std::find(listeners_.begin(), listeners_.end(), listener);
    if (found != listeners_.end()) {
        listeners_.erase(found);
    }
}

const SelectionEvent& SelectionManager::currentSelection() const {
    return currentSelection_;
}

void SelectionManager::select(const SelectionEvent& event) {
    currentSelection_ = event;
    const auto listeners = listeners_;
    for (SelectionListener* listener : listeners) {
        if (listener != nullptr) {
            listener->selectionChanged(event);
        }
    }
}

void SelectionManager::clearSelection() {
    select(SelectionEvent::none());
}

} // namespace libreshockwave::editor::selection
