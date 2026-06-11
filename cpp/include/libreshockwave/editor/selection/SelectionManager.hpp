#pragma once

#include <vector>

#include "libreshockwave/editor/selection/SelectionEvent.hpp"
#include "libreshockwave/editor/selection/SelectionListener.hpp"

namespace libreshockwave::editor::selection {

class SelectionManager {
public:
    void addListener(SelectionListener* listener);
    void removeListener(SelectionListener* listener);

    [[nodiscard]] const SelectionEvent& currentSelection() const;
    void select(const SelectionEvent& event);
    void clearSelection();

private:
    std::vector<SelectionListener*> listeners_;
    SelectionEvent currentSelection_{SelectionEvent::none()};
};

} // namespace libreshockwave::editor::selection
