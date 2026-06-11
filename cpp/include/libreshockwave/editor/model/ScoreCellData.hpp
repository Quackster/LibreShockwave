#pragma once

#include <string>

namespace libreshockwave::editor::model {

struct ScoreCellData {
    int castLib{};
    int castMember{};
    int spriteType{};
    int ink{};
    int posX{};
    int posY{};
    int width{};
    int height{};
    std::string memberName;

    [[nodiscard]] std::string toString() const;

    friend bool operator==(const ScoreCellData&, const ScoreCellData&) = default;
};

} // namespace libreshockwave::editor::model
