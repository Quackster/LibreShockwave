#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string_view>
#include <vector>

#include "libreshockwave/w3d/W3DEntry.hpp"
#include "libreshockwave/w3d/W3DMaterial.hpp"
#include "libreshockwave/w3d/W3DMeshResource.hpp"
#include "libreshockwave/w3d/W3DNode.hpp"
#include "libreshockwave/w3d/W3DResourceRef.hpp"
#include "libreshockwave/w3d/W3DShape.hpp"
#include "libreshockwave/w3d/W3DTexture.hpp"

namespace libreshockwave {

class W3DFile {
public:
    [[nodiscard]] static W3DFile load(const std::vector<std::uint8_t>& data);
    [[nodiscard]] static W3DFile load(const std::filesystem::path& path);

    [[nodiscard]] int version() const;
    [[nodiscard]] const std::vector<w3d::W3DEntry>& entries() const;
    [[nodiscard]] const std::vector<w3d::W3DNode>& nodes() const;
    [[nodiscard]] const std::vector<w3d::W3DShape>& shapes() const;
    [[nodiscard]] const std::vector<w3d::W3DMeshResource>& meshResources() const;
    [[nodiscard]] const std::vector<w3d::W3DTexture>& textures() const;
    [[nodiscard]] const std::vector<w3d::W3DMaterial>& materials() const;
    [[nodiscard]] const std::vector<w3d::W3DResourceRef>& resourceRefs() const;

    [[nodiscard]] std::optional<w3d::W3DNode> findNode(std::string_view name) const;
    [[nodiscard]] std::optional<w3d::W3DMeshResource> findMeshResource(std::string_view name) const;
    [[nodiscard]] std::optional<w3d::W3DTexture> findTexture(std::string_view name) const;
    [[nodiscard]] std::optional<w3d::W3DMaterial> findMaterial(std::string_view name) const;
    [[nodiscard]] std::optional<w3d::W3DResourceRef> findResourceRef(std::string_view name) const;

private:
    void parse(const std::vector<std::uint8_t>& data);

    int version_ = 0;
    std::vector<w3d::W3DEntry> entries_;
    std::vector<w3d::W3DNode> nodes_;
    std::vector<w3d::W3DShape> shapes_;
    std::vector<w3d::W3DMeshResource> meshResources_;
    std::vector<w3d::W3DTexture> textures_;
    std::vector<w3d::W3DMaterial> materials_;
    std::vector<w3d::W3DResourceRef> resourceRefs_;
};

} // namespace libreshockwave
