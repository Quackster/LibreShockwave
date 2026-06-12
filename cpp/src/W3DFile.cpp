#include "libreshockwave/W3DFile.hpp"

#include <algorithm>
#include <array>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

#include "libreshockwave/io/BinaryReader.hpp"

namespace libreshockwave {
namespace {

using Transform = std::array<float, 16>;

Transform identityTransform() {
    Transform transform{};
    transform[0] = 1.0F;
    transform[5] = 1.0F;
    transform[10] = 1.0F;
    transform[15] = 1.0F;
    return transform;
}

Transform multiplyTransforms(const Transform& parent, const Transform& local) {
    Transform result{};
    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            float value = 0.0F;
            for (int k = 0; k < 4; ++k) {
                value += parent[static_cast<std::size_t>(k * 4 + row)] *
                    local[static_cast<std::size_t>(column * 4 + k)];
            }
            result[static_cast<std::size_t>(column * 4 + row)] = value;
        }
    }
    return result;
}

const w3d::W3DNode* findNodePtr(const std::vector<w3d::W3DNode>& nodes, std::string_view name) {
    for (const auto& node : nodes) {
        if (node.name == name) {
            return &node;
        }
    }
    return nullptr;
}

const w3d::W3DShape* findShapePtr(const std::vector<w3d::W3DShape>& shapes, std::string_view name) {
    for (const auto& shape : shapes) {
        if (shape.name == name) {
            return &shape;
        }
    }
    return nullptr;
}

bool stackContains(const std::vector<std::string>& stack, std::string_view name) {
    return std::any_of(stack.begin(), stack.end(), [name](const std::string& value) {
        return value == name;
    });
}

std::optional<Transform> worldTransformForNodeImpl(const std::vector<w3d::W3DNode>& nodes,
                                                   std::string_view name,
                                                   std::vector<std::string>& stack) {
    const auto* node = findNodePtr(nodes, name);
    if (node == nullptr || stackContains(stack, node->name)) {
        return std::nullopt;
    }

    stack.push_back(node->name);
    const Transform local = node->transform.value_or(identityTransform());
    if (node->parentName.empty()) {
        stack.pop_back();
        return local;
    }

    auto parent = worldTransformForNodeImpl(nodes, node->parentName, stack);
    stack.pop_back();
    if (!parent.has_value()) {
        return local;
    }
    return multiplyTransforms(*parent, local);
}

} // namespace

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

std::vector<w3d::W3DNode> W3DFile::childNodes(std::string_view parentName) const {
    std::vector<w3d::W3DNode> result;
    for (const auto& node : nodes_) {
        if (node.parentName == parentName) {
            result.push_back(node);
        }
    }
    return result;
}

std::vector<w3d::W3DShape> W3DFile::childShapes(std::string_view parentName) const {
    std::vector<w3d::W3DShape> result;
    for (const auto& shape : shapes_) {
        if (shape.parentName == parentName) {
            result.push_back(shape);
        }
    }
    return result;
}

std::optional<std::array<float, 16>> W3DFile::worldTransformForNode(std::string_view name) const {
    std::vector<std::string> stack;
    return worldTransformForNodeImpl(nodes_, name, stack);
}

std::optional<std::array<float, 16>> W3DFile::worldTransformForShape(std::string_view name) const {
    const auto* shape = findShapePtr(shapes_, name);
    if (shape == nullptr) {
        return std::nullopt;
    }

    const Transform local = shape->transform.value_or(identityTransform());
    if (shape->parentName.empty()) {
        return local;
    }

    auto parent = worldTransformForNode(shape->parentName);
    if (!parent.has_value()) {
        return local;
    }
    return multiplyTransforms(*parent, local);
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
