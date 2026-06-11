#pragma once

namespace libreshockwave::editor::score {

class PlaybackHead {
public:
    [[nodiscard]] int frame() const;
    void setFrame(int frame);

private:
    int frame_{1};
};

} // namespace libreshockwave::editor::score
