#pragma once

#include "libreshockwave/editor/selection/SelectionEvent.hpp"

namespace libreshockwave::editor::selection {

class SelectionListener {
public:
    virtual ~SelectionListener() = default;
    virtual void selectionChanged(const SelectionEvent& event) = 0;
};

} // namespace libreshockwave::editor::selection
