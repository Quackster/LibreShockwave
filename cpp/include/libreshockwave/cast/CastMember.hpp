#pragma once

#include <memory>
#include <optional>
#include <string>

#include "libreshockwave/cast/BitmapInfo.hpp"
#include "libreshockwave/cast/FilmLoopInfo.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/cast/ScriptType.hpp"
#include "libreshockwave/cast/ShapeInfo.hpp"
#include "libreshockwave/cast/Shockwave3DInfo.hpp"

namespace libreshockwave::chunks {
class CastMemberChunk;
}

namespace libreshockwave::cast {

class CastMember {
public:
    CastMember(int id, int castLib, int memberNum, std::shared_ptr<chunks::CastMemberChunk> chunk);

    [[nodiscard]] int id() const;
    [[nodiscard]] int castLib() const;
    [[nodiscard]] int memberNum() const;
    [[nodiscard]] MemberType memberType() const;
    [[nodiscard]] const std::string& name() const;
    [[nodiscard]] int scriptId() const;
    [[nodiscard]] const std::shared_ptr<chunks::CastMemberChunk>& rawChunk() const;

    [[nodiscard]] const std::optional<BitmapInfo>& bitmapInfo() const;
    [[nodiscard]] const std::optional<ShapeInfo>& shapeInfo() const;
    [[nodiscard]] const std::optional<FilmLoopInfo>& filmLoopInfo() const;
    [[nodiscard]] const std::optional<ScriptType>& scriptType() const;
    [[nodiscard]] const std::optional<Shockwave3DInfo>& shockwave3DInfo() const;

    [[nodiscard]] bool isBitmap() const;
    [[nodiscard]] bool isText() const;
    [[nodiscard]] bool isSound() const;
    [[nodiscard]] bool isScript() const;
    [[nodiscard]] bool isShape() const;
    [[nodiscard]] bool isFilmLoop() const;
    [[nodiscard]] bool isPalette() const;
    [[nodiscard]] bool isFont() const;
    [[nodiscard]] bool isShockwave3D() const;

    [[nodiscard]] int width() const;
    [[nodiscard]] int height() const;
    [[nodiscard]] int regX() const;
    [[nodiscard]] int regY() const;
    [[nodiscard]] std::string toString() const;

private:
    void parseSpecificData();

    int id_;
    int castLib_;
    int memberNum_;
    MemberType memberType_;
    std::string name_;
    int scriptId_;
    std::shared_ptr<chunks::CastMemberChunk> rawChunk_;

    std::optional<BitmapInfo> bitmapInfo_;
    std::optional<ShapeInfo> shapeInfo_;
    std::optional<FilmLoopInfo> filmLoopInfo_;
    std::optional<ScriptType> scriptType_;
    std::optional<Shockwave3DInfo> shockwave3DInfo_;
};

} // namespace libreshockwave::cast
