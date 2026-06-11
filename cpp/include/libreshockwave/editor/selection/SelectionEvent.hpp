#pragma once

namespace libreshockwave::editor::selection {

enum class SelectionType {
    None,
    Sprite,
    CastMember,
    Frame,
    ScoreCell
};

struct SelectionEvent {
    SelectionType type{SelectionType::None};
    int channel{};
    int frame{};
    int castLib{};
    int memberNum{};

    [[nodiscard]] static SelectionEvent none();
    [[nodiscard]] static SelectionEvent sprite(int channel, int frame);
    [[nodiscard]] static SelectionEvent castMember(int castLib, int memberNum);
    [[nodiscard]] static SelectionEvent frameSelection(int frame);

    friend bool operator==(const SelectionEvent&, const SelectionEvent&) = default;
};

} // namespace libreshockwave::editor::selection
