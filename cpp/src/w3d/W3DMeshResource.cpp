#include "libreshockwave/w3d/W3DMeshResource.hpp"

#include "libreshockwave/io/BinaryReader.hpp"

namespace libreshockwave::w3d {

W3DMeshResource W3DMeshResource::parse(const std::vector<std::uint8_t>& data) {
    if (data.size() < 4) {
        return W3DMeshResource{"", 0, 0, {}};
    }

    io::BinaryReader reader(data, io::ByteOrder::LittleEndian);
    std::string name = reader.readPString16();
    const int vertexCount = reader.bytesLeft() >= 4 ? reader.readI32() : 0;
    const int faceCount = reader.bytesLeft() >= 4 ? reader.readI32() : 0;

    std::vector<std::uint8_t> geometryData;
    if (reader.bytesLeft() > 0) {
        geometryData = reader.readBytes(reader.bytesLeft());
    }
    return W3DMeshResource{std::move(name), vertexCount, faceCount, std::move(geometryData)};
}

} // namespace libreshockwave::w3d
