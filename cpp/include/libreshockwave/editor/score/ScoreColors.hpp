#pragma once

#include <optional>
#include <string_view>

namespace libreshockwave::editor::score {

struct ScoreColor {
    int r;
    int g;
    int b;

    friend bool operator==(const ScoreColor&, const ScoreColor&) = default;
};

class ScoreColors {
public:
    static constexpr ScoreColor BITMAP{153, 204, 255};
    static constexpr ScoreColor TEXT{255, 255, 153};
    static constexpr ScoreColor SHAPE{153, 255, 153};
    static constexpr ScoreColor SCRIPT{204, 153, 255};
    static constexpr ScoreColor SOUND{255, 204, 153};
    static constexpr ScoreColor FILM_LOOP{153, 255, 255};
    static constexpr ScoreColor BUTTON{255, 204, 204};
    static constexpr ScoreColor FONT{204, 204, 204};
    static constexpr ScoreColor PALETTE{204, 255, 204};
    static constexpr ScoreColor FIELD{255, 255, 204};
    static constexpr ScoreColor TRANSITION{204, 204, 255};
    static constexpr ScoreColor XTRA{255, 153, 153};
    static constexpr ScoreColor UNKNOWN{200, 200, 200};
    static constexpr ScoreColor SPAN_CONTINUATION{230, 230, 230};

    [[nodiscard]] static std::optional<ScoreColor> getColor(std::string_view memberType);
};

} // namespace libreshockwave::editor::score
