#include "libreshockwave/cast/CastMember.hpp"

#include <sstream>
#include <utility>

#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"

namespace libreshockwave::cast {

CastMember::CastMember(int id, int castLib, int memberNum, std::shared_ptr<chunks::CastMemberChunk> chunk)
    : id_(id),
      castLib_(castLib),
      memberNum_(memberNum),
      memberType_(chunk ? chunk->memberType() : MemberType::Unknown),
      name_(chunk ? chunk->name() : ""),
      scriptId_(chunk ? chunk->scriptId() : 0),
      rawChunk_(std::move(chunk)) {
    parseSpecificData();
}

CastMember::CastMember(int castLib, int memberNum, MemberType memberType)
    : id_(0),
      castLib_(castLib),
      memberNum_(memberNum),
      memberType_(memberType),
      scriptId_(0) {}

int CastMember::id() const { return id_; }
int CastMember::castLib() const { return castLib_; }
int CastMember::memberNum() const { return memberNum_; }
MemberType CastMember::memberType() const { return memberType_; }
const std::string& CastMember::name() const { return name_; }
void CastMember::setName(std::string name) { name_ = std::move(name); }
int CastMember::scriptId() const { return scriptId_; }
const std::shared_ptr<chunks::CastMemberChunk>& CastMember::rawChunk() const { return rawChunk_; }

const std::optional<BitmapInfo>& CastMember::bitmapInfo() const { return bitmapInfo_; }
const std::optional<ShapeInfo>& CastMember::shapeInfo() const { return shapeInfo_; }
const std::optional<FilmLoopInfo>& CastMember::filmLoopInfo() const { return filmLoopInfo_; }
const std::optional<ScriptType>& CastMember::scriptType() const { return scriptType_; }
const std::optional<Shockwave3DInfo>& CastMember::shockwave3DInfo() const { return shockwave3DInfo_; }

bool CastMember::isBitmap() const { return memberType_ == MemberType::Bitmap; }
bool CastMember::isText() const { return memberType_ == MemberType::Text; }
bool CastMember::isSound() const { return memberType_ == MemberType::Sound; }
bool CastMember::isScript() const { return memberType_ == MemberType::Script; }
bool CastMember::isShape() const { return memberType_ == MemberType::Shape; }
bool CastMember::isFilmLoop() const { return memberType_ == MemberType::FilmLoop; }
bool CastMember::isPalette() const { return memberType_ == MemberType::Palette; }
bool CastMember::isFont() const { return memberType_ == MemberType::Font; }
bool CastMember::isShockwave3D() const { return memberType_ == MemberType::Shockwave3D; }

int CastMember::width() const {
    if (runtimeBitmap_) return runtimeBitmap_->width();
    if (bitmapInfo_) return bitmapInfo_->width;
    if (shapeInfo_) return shapeInfo_->width;
    if (filmLoopInfo_) return filmLoopInfo_->width();
    return 0;
}

int CastMember::height() const {
    if (runtimeBitmap_) return runtimeBitmap_->height();
    if (bitmapInfo_) return bitmapInfo_->height;
    if (shapeInfo_) return shapeInfo_->height;
    if (filmLoopInfo_) return filmLoopInfo_->height();
    return 0;
}

int CastMember::regX() const {
    if (runtimeRegX_.has_value()) return *runtimeRegX_;
    if (bitmapInfo_) return bitmapInfo_->regX;
    if (shapeInfo_) return shapeInfo_->regX;
    if (filmLoopInfo_) return filmLoopInfo_->regX();
    return 0;
}

int CastMember::regY() const {
    if (runtimeRegY_.has_value()) return *runtimeRegY_;
    if (bitmapInfo_) return bitmapInfo_->regY;
    if (shapeInfo_) return shapeInfo_->regY;
    if (filmLoopInfo_) return filmLoopInfo_->regY();
    return 0;
}

std::shared_ptr<bitmap::Bitmap> CastMember::runtimeBitmap() const {
    return runtimeBitmap_;
}

void CastMember::setRuntimeBitmap(const bitmap::Bitmap& bitmap, bool markScriptModified) {
    auto copy = std::make_shared<bitmap::Bitmap>(bitmap.copy());
    if (markScriptModified) {
        copy->markScriptModified();
    }
    runtimeRegX_ = bitmap.hasAnchorPoint() ? std::optional<int>(bitmap.anchorX()) : std::optional<int>(0);
    runtimeRegY_ = bitmap.hasAnchorPoint() ? std::optional<int>(bitmap.anchorY()) : std::optional<int>(0);
    runtimeBitmap_ = std::move(copy);
}

std::string CastMember::toString() const {
    std::ostringstream out;
    out << "CastMember[lib=" << castLib_
        << ", num=" << memberNum_
        << ", type=" << libreshockwave::cast::name(memberType_);
    if (!name_.empty()) {
        out << ", name=\"" << name_ << "\"";
    }
    if (bitmapInfo_) {
        out << ", " << bitmapInfo_->width << "x" << bitmapInfo_->height
            << "x" << bitmapInfo_->bitDepth << "bit";
    }
    if (scriptType_) {
        out << ", scriptType=" << libreshockwave::cast::name(*scriptType_);
    }
    out << "]";
    return out.str();
}

void CastMember::parseSpecificData() {
    if (!rawChunk_) {
        return;
    }

    const auto& specificData = rawChunk_->specificData();
    switch (memberType_) {
        case MemberType::Bitmap:
            bitmapInfo_ = BitmapInfo::parse(specificData);
            break;
        case MemberType::Shape:
            shapeInfo_ = ShapeInfo::parse(specificData);
            break;
        case MemberType::FilmLoop:
            filmLoopInfo_ = FilmLoopInfo::parse(specificData);
            break;
        case MemberType::Script:
            if (specificData.size() >= 2) {
                const int typeCode = (static_cast<int>(specificData[0]) << 8) |
                                     static_cast<int>(specificData[1]);
                scriptType_ = scriptTypeFromCode(typeCode);
            }
            break;
        case MemberType::Shockwave3D:
            shockwave3DInfo_ = Shockwave3DInfo::parse(specificData);
            break;
        default:
            break;
    }
}

} // namespace libreshockwave::cast
