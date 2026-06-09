#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace libreshockwave::w3d {

struct W3DMaterial {
    std::string name;
    std::vector<std::uint8_t> materialData;

    [[nodiscard]] static W3DMaterial parse(const std::vector<std::uint8_t>& data);
};

} // namespace libreshockwave::w3d
