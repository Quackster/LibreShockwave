#include "libreshockwave/editor/selection/SelectionEvent.hpp"

namespace libreshockwave::editor::selection {

SelectionEvent SelectionEvent::none() {
    return SelectionEvent{SelectionType::None, 0, 0, 0, 0};
}

SelectionEvent SelectionEvent::sprite(int channel, int frame) {
    return SelectionEvent{SelectionType::Sprite, channel, frame, 0, 0};
}

SelectionEvent SelectionEvent::castMember(int castLib, int memberNum) {
    return SelectionEvent{SelectionType::CastMember, 0, 0, castLib, memberNum};
}

SelectionEvent SelectionEvent::frameSelection(int frame) {
    return SelectionEvent{SelectionType::Frame, 0, frame, 0, 0};
}

} // namespace libreshockwave::editor::selection
