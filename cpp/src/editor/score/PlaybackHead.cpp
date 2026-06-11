#include "libreshockwave/editor/score/PlaybackHead.hpp"

#include <algorithm>

namespace libreshockwave::editor::score {

int PlaybackHead::frame() const {
    return frame_;
}

void PlaybackHead::setFrame(int frame) {
    frame_ = std::max(1, frame);
}

} // namespace libreshockwave::editor::score
