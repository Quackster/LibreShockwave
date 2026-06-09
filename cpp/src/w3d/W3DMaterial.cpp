#include "libreshockwave/w3d/W3DMaterial.hpp"

#include "libreshockwave/io/BinaryReader.hpp"

namespace libreshockwave::w3d {

W3DMaterial W3DMaterial::parse(const std::vector<std::uint8_t>& data) {
    if (data.size() < 4) {
        return W3DMaterial{"", {}};
    }

    io::BinaryReader reader(data, io::ByteOrder::LittleEndian);
    std::string name = reader.readPString16();
    std::vector<std::uint8_t> materialData;
    if (reader.bytesLeft() > 0) {
        materialData = reader.readBytes(reader.bytesLeft());
    }
    return W3DMaterial{std::move(name), std::move(materialData)};
}

} // namespace libreshockwave::w3d
