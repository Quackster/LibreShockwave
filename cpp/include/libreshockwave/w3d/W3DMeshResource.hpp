#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace libreshockwave::w3d {

struct W3DMeshResource {
    std::string name;
    int vertexCount;
    int faceCount;
    std::vector<std::uint8_t> geometryData;

    [[nodiscard]] static W3DMeshResource parse(const std::vector<std::uint8_t>& data);
};

} // namespace libreshockwave::w3d
