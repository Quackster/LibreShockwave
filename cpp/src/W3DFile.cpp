#include "libreshockwave/W3DFile.hpp"

#include <fstream>
#include <iterator>
#include <stdexcept>

#include "libreshockwave/io/BinaryReader.hpp"

namespace libreshockwave {

W3DFile W3DFile::load(const std::vector<std::uint8_t>& data) {
    W3DFile file;
    file.parse(data);
    return file;
}

W3DFile W3DFile::load(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("Failed to open W3D file: " + path.string());
    }
    std::vector<std::uint8_t> data(
        (std::istreambuf_iterator<char>(input)),
        std::istreambuf_iterator<char>());
    return load(data);
}

int W3DFile::version() const {
    return version_;
}

const std::vector<w3d::W3DEntry>& W3DFile::entries() const {
    return entries_;
}

const std::vector<w3d::W3DNode>& W3DFile::nodes() const {
    return nodes_;
}

const std::vector<w3d::W3DShape>& W3DFile::shapes() const {
    return shapes_;
}

const std::vector<w3d::W3DMeshResource>& W3DFile::meshResources() const {
    return meshResources_;
}

const std::vector<w3d::W3DTexture>& W3DFile::textures() const {
    return textures_;
}

const std::vector<w3d::W3DMaterial>& W3DFile::materials() const {
    return materials_;
}

const std::vector<w3d::W3DResourceRef>& W3DFile::resourceRefs() const {
    return resourceRefs_;
}

std::optional<w3d::W3DNode> W3DFile::findNode(std::string_view name) const {
    for (const auto& node : nodes_) {
        if (node.name == name) {
            return node;
        }
    }
    return std::nullopt;
}

std::optional<w3d::W3DMeshResource> W3DFile::findMeshResource(std::string_view name) const {
    for (const auto& meshResource : meshResources_) {
        if (meshResource.name == name) {
            return meshResource;
        }
    }
    return std::nullopt;
}

std::optional<w3d::W3DTexture> W3DFile::findTexture(std::string_view name) const {
    for (const auto& texture : textures_) {
        if (texture.name == name) {
            return texture;
        }
    }
    return std::nullopt;
}

std::optional<w3d::W3DMaterial> W3DFile::findMaterial(std::string_view name) const {
    for (const auto& material : materials_) {
        if (material.name == name) {
            return material;
        }
    }
    return std::nullopt;
}

std::optional<w3d::W3DResourceRef> W3DFile::findResourceRef(std::string_view name) const {
    for (const auto& resourceRef : resourceRefs_) {
        if (resourceRef.name == name) {
            return resourceRef;
        }
    }
    return std::nullopt;
}

void W3DFile::parse(const std::vector<std::uint8_t>& data) {
    io::BinaryReader reader(data, io::ByteOrder::LittleEndian);

    while (reader.bytesLeft() >= 10) {
        auto entry = w3d::W3DEntry::read(reader);

        if (entry.type == w3d::W3DEntryType::Version && entry.data.size() >= 4) {
            io::BinaryReader versionReader(entry.data, io::ByteOrder::LittleEndian);
            version_ = versionReader.readI32();
        }

        switch (entry.type) {
            case w3d::W3DEntryType::Node:
            case w3d::W3DEntryType::LightData:
                nodes_.push_back(w3d::W3DNode::parse(entry.data));
                break;
            case w3d::W3DEntryType::Shape:
                shapes_.push_back(w3d::W3DShape::parse(entry.data));
                break;
            case w3d::W3DEntryType::MeshResource:
                meshResources_.push_back(w3d::W3DMeshResource::parse(entry.data));
                break;
            case w3d::W3DEntryType::BinaryData:
                textures_.push_back(w3d::W3DTexture::parse(entry.data));
                break;
            case w3d::W3DEntryType::Material:
                materials_.push_back(w3d::W3DMaterial::parse(entry.data));
                break;
            case w3d::W3DEntryType::ResourceRef:
                resourceRefs_.push_back(w3d::W3DResourceRef::parse(entry.data));
                break;
            default:
                break;
        }

        entries_.push_back(std::move(entry));
    }
}

} // namespace libreshockwave
