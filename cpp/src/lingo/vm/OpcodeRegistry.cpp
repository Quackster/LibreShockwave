#include "libreshockwave/lingo/vm/OpcodeRegistry.hpp"

#include "libreshockwave/bitmap/Bitmap.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <charconv>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <optional>
#include <queue>
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <variant>

namespace libreshockwave::lingo::vm {
namespace {

Datum literalToDatum(const chunks::ScriptChunk::LiteralEntry& literal) {
    switch (literal.type) {
        case 1:
            if (const auto* value = std::get_if<std::string>(&literal.value)) {
                return Datum::of(*value);
            }
            break;
        case 4:
            if (const auto* value = std::get_if<int>(&literal.value)) {
                return Datum::of(*value);
            }
            break;
        case 9:
            return Datum::of(literal.numericValue);
        default:
            break;
    }
    return Datum::voidValue();
}

bool truthy(const Datum& datum) {
    if (datum.isVoid() || datum.isNull()) {
        return false;
    }
    if (const auto* value = datum.asInt()) {
        return value->value != 0;
    }
    if (const auto* value = datum.asFloat()) {
        return value->value != 0.0F;
    }
    if (datum.isString()) {
        return !datum.stringValue().empty();
    }
    return true;
}

bool equalsIgnoreCase(std::string_view lhs, std::string_view rhs) {
    if (lhs.size() != rhs.size()) {
        return false;
    }
    for (std::size_t index = 0; index < lhs.size(); ++index) {
        const auto left = static_cast<unsigned char>(lhs[index]);
        const auto right = static_cast<unsigned char>(rhs[index]);
        if (std::tolower(left) != std::tolower(right)) {
            return false;
        }
    }
    return true;
}

std::string trimCopy(std::string_view value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin))) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1)))) {
        --end;
    }
    return std::string(begin, end);
}

std::optional<int> parseIntStrict(std::string_view value) {
    const std::string trimmed = trimCopy(value);
    if (trimmed.empty()) {
        return std::nullopt;
    }

    int result = 0;
    const auto* begin = trimmed.data();
    const auto* end = trimmed.data() + trimmed.size();
    const auto parsed = std::from_chars(begin, end, result);
    if (parsed.ec != std::errc{} || parsed.ptr != end) {
        return std::nullopt;
    }
    return result;
}

std::optional<double> parseDoubleStrict(std::string_view value) {
    const std::string trimmed = trimCopy(value);
    if (trimmed.empty()) {
        return std::nullopt;
    }

    char* end = nullptr;
    const double result = std::strtod(trimmed.c_str(), &end);
    if (end == trimmed.c_str() || *end != '\0') {
        return std::nullopt;
    }
    return result;
}

std::string toStringLikeJava(const Datum& datum);

std::string datumReprLikeJava(const Datum& datum) {
    if (datum.isVoid()) {
        return "<Void>";
    }
    if (datum.isString()) {
        return "\"" + datum.stringValue() + "\"";
    }
    if (const auto* value = datum.asSymbol()) {
        return "#" + value->name;
    }
    return toStringLikeJava(datum);
}

std::string listStringLikeJava(const Datum::List& list) {
    std::ostringstream out;
    out << '[';
    for (std::size_t index = 0; index < list.items().size(); ++index) {
        if (index > 0) {
            out << ", ";
        }
        out << datumReprLikeJava(list.items()[index]);
    }
    out << ']';
    return out.str();
}

std::string propListStringLikeJava(const Datum::PropList& propList) {
    std::ostringstream out;
    out << '[';
    const auto& properties = propList.properties();
    for (std::size_t index = 0; index < properties.size(); ++index) {
        if (index > 0) {
            out << ", ";
        }
        out << datumReprLikeJava(properties[index].first) << ": " << datumReprLikeJava(properties[index].second);
    }
    out << ']';
    return out.str();
}

std::string toStringLikeJava(const Datum& datum) {
    if (datum.isVoid() || datum.isNull()) {
        return "";
    }
    if (datum.isString()) {
        return datum.stringValue();
    }
    if (const auto* value = datum.asSymbol()) {
        return value->name;
    }
    if (const auto* value = datum.asColorRef()) {
        return "color(" + std::to_string(value->r) + ", " + std::to_string(value->g) + ", " +
               std::to_string(value->b) + ")";
    }
    if (const auto* value = datum.asSpriteRef()) {
        return "sprite(" + std::to_string(value->channel) + ")";
    }
    if (const auto* value = datum.asImageRef()) {
        if (!value->bitmap) {
            return "(image null)";
        }
        return "(image " + std::to_string(value->bitmap->width()) + "x" +
               std::to_string(value->bitmap->height()) + ")";
    }
    if (const auto* value = datum.asCastMemberRef()) {
        return "member(" + std::to_string(value->castMember) + ", " + std::to_string(value->castLib) + ")";
    }
    if (const auto* value = datum.asCastLibRef()) {
        return "castLib(" + std::to_string(value->castLib) + ")";
    }
    if (const auto* value = datum.asScriptRef()) {
        return "<script " + std::to_string(value->memberRef.castMember) + ", " +
               std::to_string(value->memberRef.castLib) + ">";
    }
    if (const auto* value = datum.asXtra()) {
        return "<Xtra \"" + value->name + "\">";
    }
    if (const auto* value = datum.asXtraInstance()) {
        return "<XtraInstance \"" + value->xtraName + "\" #" + std::to_string(value->instanceId) + ">";
    }
    if (datum.isList()) {
        return listStringLikeJava(datum.listValue());
    }
    if (datum.isPropList()) {
        return propListStringLikeJava(datum.propListValue());
    }
    if (datum.type() == DatumType::StageRef) {
        return "(the stage)";
    }
    if (datum.type() == DatumType::MovieRef) {
        return "(the movie)";
    }
    if (datum.type() == DatumType::PlayerRef) {
        return "(the player)";
    }
    try {
        return datum.stringValue();
    } catch (const LingoException&) {
        return datum.typeString();
    }
}

bool containsIgnoreCase(std::string_view haystack, std::string_view needle) {
    if (needle.empty() || needle.size() > haystack.size()) {
        return false;
    }
    for (std::size_t offset = 0; offset + needle.size() <= haystack.size(); ++offset) {
        bool matches = true;
        for (std::size_t index = 0; index < needle.size(); ++index) {
            const auto left = static_cast<unsigned char>(haystack[offset + index]);
            const auto right = static_cast<unsigned char>(needle[index]);
            if (std::tolower(left) != std::tolower(right)) {
                matches = false;
                break;
            }
        }
        if (matches) {
            return true;
        }
    }
    return false;
}

bool startsWithIgnoreCase(std::string_view haystack, std::string_view needle) {
    if (needle.empty() || needle.size() > haystack.size()) {
        return false;
    }
    for (std::size_t index = 0; index < needle.size(); ++index) {
        const auto left = static_cast<unsigned char>(haystack[index]);
        const auto right = static_cast<unsigned char>(needle[index]);
        if (std::tolower(left) != std::tolower(right)) {
            return false;
        }
    }
    return true;
}

int packedColor(const Datum::ColorRef& color) {
    return (color.r << 16) | (color.g << 8) | color.b;
}

std::uint32_t imageColorArgb(const Datum& datum) {
    if (const auto* color = datum.asColorRef()) {
        return 0xFF000000U |
               (static_cast<std::uint32_t>(color->r & 0xFF) << 16) |
               (static_cast<std::uint32_t>(color->g & 0xFF) << 8) |
               static_cast<std::uint32_t>(color->b & 0xFF);
    }
    if (const auto* value = datum.asInt()) {
        if (value->value > 255) {
            return 0xFF000000U | (static_cast<std::uint32_t>(value->value) & 0x00FFFFFFU);
        }
        const auto gray = static_cast<std::uint32_t>((255 - value->value) & 0xFF);
        return 0xFF000000U | (gray << 16) | (gray << 8) | gray;
    }
    if (datum.isString()) {
        std::string value = trimCopy(datum.stringValue());
        if (value.size() >= 2 &&
            ((value.front() == '"' && value.back() == '"') || (value.front() == '\'' && value.back() == '\''))) {
            value = trimCopy(value.substr(1, value.size() - 2));
        }
        if (!value.empty() && value.front() == '#') {
            value.erase(value.begin());
        }
        if (value.size() == 6) {
            try {
                return 0xFF000000U | (static_cast<std::uint32_t>(std::stoul(value, nullptr, 16)) & 0x00FFFFFFU);
            } catch (...) {
            }
        }
    }
    return 0xFF000000U;
}

std::uint32_t imageColorArgb(const Datum& datum, const bitmap::Bitmap* target) {
    if (const auto* value = datum.asInt(); value != nullptr &&
        target != nullptr &&
        target->imagePalette() != nullptr &&
        value->value >= 0 &&
        value->value <= 255) {
        return 0xFF000000U | (target->resolvePaletteIndex(value->value, nullptr) & 0x00FFFFFFU);
    }
    return imageColorArgb(datum);
}

int toIntLikeJava(const Datum& datum) {
    if (const auto* value = datum.asInt()) {
        return value->value;
    }
    if (const auto* value = datum.asFloat()) {
        return static_cast<int>(value->value);
    }
    if (datum.isString()) {
        return parseIntStrict(datum.stringValue()).value_or(0);
    }
    if (const auto* value = datum.asCastLibRef()) {
        return value->castLib;
    }
    if (const auto* value = datum.asSpriteRef()) {
        return value->channel;
    }
    if (const auto* color = datum.asColorRef()) {
        return packedColor(*color);
    }
    return 0;
}

double toDoubleLikeJava(const Datum& datum) {
    if (const auto* value = datum.asInt()) {
        return static_cast<double>(value->value);
    }
    if (const auto* value = datum.asFloat()) {
        return static_cast<double>(value->value);
    }
    if (datum.isString()) {
        return parseDoubleStrict(datum.stringValue()).value_or(0.0);
    }
    if (const auto* value = datum.asCastLibRef()) {
        return static_cast<double>(value->castLib);
    }
    if (const auto* value = datum.asSpriteRef()) {
        return static_cast<double>(value->channel);
    }
    if (const auto* color = datum.asColorRef()) {
        return static_cast<double>(packedColor(*color));
    }
    return 0.0;
}

bool isFloatLike(const Datum& datum) {
    return datum.asFloat() != nullptr;
}

Datum numericResult(const Datum& a, const Datum& b, double value) {
    if (isFloatLike(a) || isFloatLike(b)) {
        return Datum::of(value);
    }
    return Datum::of(static_cast<int>(value));
}

Datum::IntPoint listAsPointDelta(const Datum::List& list) {
    if (list.items().size() >= 2) {
        return Datum::IntPoint{toIntLikeJava(list.items()[0]), toIntLikeJava(list.items()[1])};
    }
    return Datum::IntPoint{0, 0};
}

Datum::IntRect listAsRectDelta(const Datum::List& list) {
    if (list.items().size() >= 4) {
        return Datum::IntRect{toIntLikeJava(list.items()[0]),
                              toIntLikeJava(list.items()[1]),
                              toIntLikeJava(list.items()[2]),
                              toIntLikeJava(list.items()[3])};
    }
    return Datum::IntRect{0, 0, 0, 0};
}

Datum scaleList(const Datum::List& list, double scalar, bool scalarIsFloat) {
    std::vector<Datum> result;
    result.reserve(list.items().size());
    for (const auto& item : list.items()) {
        if (isFloatLike(item) || scalarIsFloat) {
            result.push_back(Datum::of(toDoubleLikeJava(item) * scalar));
        } else {
            result.push_back(Datum::of(static_cast<int>(toIntLikeJava(item) * scalar)));
        }
    }
    return Datum::list(std::move(result));
}

Datum divideList(const Datum::List& list, const Datum& divisor) {
    const double scalar = toDoubleLikeJava(divisor);
    const bool divisorIsFloat = isFloatLike(divisor);
    const int intDivisor = divisorIsFloat ? 0 : toIntLikeJava(divisor);
    std::vector<Datum> result;
    result.reserve(list.items().size());
    for (const auto& item : list.items()) {
        if (isFloatLike(item) || divisorIsFloat) {
            result.push_back(Datum::of(toDoubleLikeJava(item) / scalar));
        } else {
            result.push_back(Datum::of(toIntLikeJava(item) / intDivisor));
        }
    }
    return Datum::list(std::move(result));
}

bool lingoEquals(const Datum& a, const Datum& b) {
    if ((a.isVoid() && b.isNumber()) || (a.isNumber() && b.isVoid()) || (a.isNumber() && b.isNumber())) {
        return toDoubleLikeJava(a) == toDoubleLikeJava(b);
    }
    if ((a.isString() || a.isSymbol()) && (b.isString() || b.isSymbol())) {
        return equalsIgnoreCase(a.stringValue(), b.stringValue());
    }
    return a == b;
}

std::vector<Datum> argListItems(const Datum& datum) {
    if (datum.type() == DatumType::ArgList) {
        return datum.argListValue().args();
    }
    if (datum.type() == DatumType::ArgListNoRet) {
        return datum.argListNoRetValue().args();
    }
    return {};
}

std::string keyNameLikeJava(const Datum& datum) {
    if (const auto* symbol = datum.asSymbol()) {
        return symbol->name;
    }
    return toStringLikeJava(datum);
}

bool lessIgnoreCase(std::string_view lhs, std::string_view rhs) {
    const std::size_t length = std::min(lhs.size(), rhs.size());
    for (std::size_t index = 0; index < length; ++index) {
        const auto left = static_cast<unsigned char>(lhs[index]);
        const auto right = static_cast<unsigned char>(rhs[index]);
        const int lowerLeft = std::tolower(left);
        const int lowerRight = std::tolower(right);
        if (lowerLeft != lowerRight) {
            return lowerLeft < lowerRight;
        }
    }
    return lhs.size() < rhs.size();
}

std::optional<Datum> builtinConstant(std::string_view name) {
    if (equalsIgnoreCase(name, "pi")) return Datum::of(3.14159265358979323846);
    if (equalsIgnoreCase(name, "true")) return Datum::TRUE;
    if (equalsIgnoreCase(name, "false")) return Datum::FALSE;
    if (equalsIgnoreCase(name, "void")) return Datum::voidValue();
    if (equalsIgnoreCase(name, "empty") || equalsIgnoreCase(name, "emptystring")) return Datum::of(std::string());
    if (equalsIgnoreCase(name, "return")) return Datum::of(std::string("\r"));
    if (equalsIgnoreCase(name, "enter")) return Datum::of(std::string("\n"));
    if (equalsIgnoreCase(name, "tab")) return Datum::of(std::string("\t"));
    if (equalsIgnoreCase(name, "quote")) return Datum::of(std::string("\""));
    if (equalsIgnoreCase(name, "backspace")) return Datum::of(std::string("\b"));
    if (equalsIgnoreCase(name, "space")) return Datum::of(std::string(" "));
    return std::nullopt;
}

std::string ilkTypeName(const Datum& datum) {
    switch (datum.type()) {
        case DatumType::Int: return "integer";
        case DatumType::Float: return "float";
        case DatumType::String:
        case DatumType::StringChunk: return "string";
        case DatumType::Symbol: return "symbol";
        case DatumType::List: return "list";
        case DatumType::PropList: return "propList";
        case DatumType::ScriptInstanceRef: return "instance";
        case DatumType::IntPoint: return "point";
        case DatumType::IntRect: return "rect";
        case DatumType::ColorRef: return "color";
        case DatumType::ImageRef: return "image";
        case DatumType::CastLibRef: return "castLib";
        case DatumType::CastMemberRef: return "member";
        case DatumType::SpriteRef: return "sprite";
        case DatumType::SoundChannel: return "sound";
        case DatumType::TimeoutRef: return "timeout";
        case DatumType::Xtra: return "xtra";
        case DatumType::XtraInstance: return "xtraInstance";
        case DatumType::Void: return "void";
        case DatumType::Null: return "null";
        default: return std::string(typeName(datum.type()));
    }
}

Datum getPropListProp(const Datum::PropList& propList, std::string_view propName) {
    if (equalsIgnoreCase(propName, "count") || equalsIgnoreCase(propName, "length")) {
        return Datum::of(propList.count());
    }
    for (const auto& entry : propList.properties()) {
        if (equalsIgnoreCase(keyNameLikeJava(entry.first), propName)) {
            return entry.second;
        }
    }
    if (equalsIgnoreCase(propName, "ilk")) {
        return Datum::symbol("propList");
    }
    return Datum::voidValue();
}

Datum getPropListKey(const Datum::PropList& propList, std::string_view keyName) {
    for (const auto& entry : propList.properties()) {
        if (equalsIgnoreCase(keyNameLikeJava(entry.first), keyName)) {
            return entry.second;
        }
    }
    return Datum::voidValue();
}

void putPropListProp(Datum::PropList& propList, std::string_view propName, Datum value) {
    for (auto& entry : propList.properties()) {
        if (equalsIgnoreCase(keyNameLikeJava(entry.first), propName)) {
            entry.second = std::move(value);
            return;
        }
    }
    propList.properties().emplace_back(Datum::symbol(std::string(propName)), std::move(value));
}

Datum getStringProp(std::string_view value, std::string_view propName) {
    if (equalsIgnoreCase(propName, "length")) {
        return Datum::of(static_cast<int>(value.size()));
    }
    if (equalsIgnoreCase(propName, "ilk")) {
        return Datum::symbol("string");
    }
    return Datum::voidValue();
}

Datum getListProp(const Datum::List& list, std::string_view propName) {
    if (equalsIgnoreCase(propName, "count") || equalsIgnoreCase(propName, "length")) {
        return Datum::of(list.count());
    }
    if (equalsIgnoreCase(propName, "ilk")) {
        return Datum::symbol("list");
    }
    if (const auto index = parseIntStrict(propName)) {
        if (*index >= 1 && *index <= list.count()) {
            return list.getAt(*index);
        }
    }
    return Datum::voidValue();
}

Datum getPointProp(const Datum::IntPoint& point, std::string_view propName) {
    if (equalsIgnoreCase(propName, "loch") || equalsIgnoreCase(propName, "x")) return Datum::of(point.x);
    if (equalsIgnoreCase(propName, "locv") || equalsIgnoreCase(propName, "y")) return Datum::of(point.y);
    if (equalsIgnoreCase(propName, "ilk")) return Datum::symbol("point");
    return Datum::voidValue();
}

Datum getRectProp(const Datum::IntRect& rect, std::string_view propName) {
    if (equalsIgnoreCase(propName, "left")) return Datum::of(rect.left);
    if (equalsIgnoreCase(propName, "top")) return Datum::of(rect.top);
    if (equalsIgnoreCase(propName, "right")) return Datum::of(rect.right);
    if (equalsIgnoreCase(propName, "bottom")) return Datum::of(rect.bottom);
    if (equalsIgnoreCase(propName, "width")) return Datum::of(rect.width());
    if (equalsIgnoreCase(propName, "height")) return Datum::of(rect.height());
    if (equalsIgnoreCase(propName, "ilk")) return Datum::symbol("rect");
    return Datum::voidValue();
}

Datum getColorProp(const Datum::ColorRef& color, std::string_view propName) {
    if (equalsIgnoreCase(propName, "red")) return Datum::of(color.r);
    if (equalsIgnoreCase(propName, "green")) return Datum::of(color.g);
    if (equalsIgnoreCase(propName, "blue")) return Datum::of(color.b);
    if (equalsIgnoreCase(propName, "ilk")) return Datum::symbol("color");
    return Datum::voidValue();
}

Datum getImageProp(const Datum::ImageRef& image, std::string_view propName) {
    const auto& bitmap = image.bitmap;
    if (equalsIgnoreCase(propName, "rect")) {
        return bitmap != nullptr ? Datum::intRect(0, 0, bitmap->width(), bitmap->height()) : Datum::intRect(0, 0, 0, 0);
    }
    if (equalsIgnoreCase(propName, "width")) return Datum::of(bitmap != nullptr ? bitmap->width() : 0);
    if (equalsIgnoreCase(propName, "height")) return Datum::of(bitmap != nullptr ? bitmap->height() : 0);
    if (equalsIgnoreCase(propName, "depth")) return Datum::of(bitmap != nullptr ? bitmap->bitDepth() : 0);
    if (equalsIgnoreCase(propName, "usealpha")) return bitmap != nullptr && bitmap->isNativeAlpha() ? Datum::TRUE : Datum::FALSE;
    if (equalsIgnoreCase(propName, "ilk")) return Datum::symbol("image");
    if (equalsIgnoreCase(propName, "image")) return Datum::imageRef(bitmap);
    if (equalsIgnoreCase(propName, "paletteref") && bitmap != nullptr) {
        if (bitmap->paletteRefCastLib() >= 1 && bitmap->paletteRefMemberNum() >= 1) {
            return Datum::castMemberRef(id::CastLibId(bitmap->paletteRefCastLib()), id::MemberId(bitmap->paletteRefMemberNum()));
        }
        if (bitmap->paletteRefSystemName()) {
            return Datum::symbol(*bitmap->paletteRefSystemName());
        }
    }
    return Datum::voidValue();
}

Datum getCastMemberProp(const Datum::CastMemberRef& member, std::string_view propName) {
    const bool invalidRef = member.castMember <= 0;
    if (equalsIgnoreCase(propName, "number")) {
        return invalidRef ? Datum::of(0) : Datum::of((member.castLib << 16) | (member.castMember & 0xFFFF));
    }
    if (equalsIgnoreCase(propName, "membernum")) {
        return invalidRef ? Datum::of(0) : Datum::of(member.memberNum());
    }
    if (equalsIgnoreCase(propName, "castlibnum")) {
        return Datum::of(member.castLib);
    }
    if (equalsIgnoreCase(propName, "castlib")) {
        return member.castLib >= 1 ? Datum::castLibRef(id::CastLibId(member.castLib)) : Datum::voidValue();
    }
    return Datum::voidValue();
}

Datum getObjectProperty(ExecutionContext& context, const Datum& object, std::string_view propName) {
    if (object.isVoid()) {
        return Datum::voidValue();
    }
    auto* builtinContext = context.builtinContext();
    if (object.type() == DatumType::MovieRef) {
        return builtinContext != nullptr && builtinContext->movieProperties != nullptr
                   ? builtinContext->movieProperties->getMovieProp(propName)
                   : Datum::voidValue();
    }
    if (object.type() == DatumType::PlayerRef) {
        if (const auto constant = builtinConstant(propName)) {
            return *constant;
        }
        return builtinContext != nullptr && builtinContext->movieProperties != nullptr
                   ? builtinContext->movieProperties->getMovieProp(propName)
                   : Datum::voidValue();
    }
    if (object.type() == DatumType::StageRef) {
        return builtinContext != nullptr && builtinContext->movieProperties != nullptr
                   ? builtinContext->movieProperties->getStageProp(propName)
                   : Datum::voidValue();
    }
    if (const auto* sprite = object.asSpriteRef()) {
        return builtinContext != nullptr && builtinContext->spriteProperties != nullptr
                   ? builtinContext->spriteProperties->getSpriteProp(sprite->channel, propName)
                   : Datum::voidValue();
    }
    if (const auto* value = object.asInt(); value != nullptr && !equalsIgnoreCase(propName, "ilk")) {
        return builtinContext != nullptr && builtinContext->spriteProperties != nullptr
                   ? builtinContext->spriteProperties->getSpriteProp(value->value, propName)
                   : Datum::voidValue();
    }
    if (const auto* timeout = object.asTimeoutRef()) {
        return builtinContext != nullptr ? builtin::TimeoutBuiltins::getProperty(*builtinContext, *timeout, propName)
                                         : Datum::voidValue();
    }
    if (const auto* soundChannel = object.asSoundChannel()) {
        return builtinContext != nullptr ? builtin::SoundBuiltins::getProperty(*builtinContext, *soundChannel, propName)
                                         : Datum::voidValue();
    }
    if (const auto* xtraInstance = object.asXtraInstance()) {
        return builtinContext != nullptr ? builtin::XtraBuiltins::getProperty(*builtinContext, *xtraInstance, propName)
                                         : Datum::voidValue();
    }
    if (object.type() == DatumType::ScriptInstanceRef) {
        if (equalsIgnoreCase(propName, "ilk")) {
            return Datum::symbol("instance");
        }
        return object.scriptInstanceValue().getProperty(std::string(propName));
    }
    if (object.isPropList()) {
        return getPropListProp(object.propListValue(), propName);
    }
    if (object.isList()) {
        return getListProp(object.listValue(), propName);
    }
    if (object.isString()) {
        return getStringProp(object.stringValue(), propName);
    }
    if (const auto* point = object.asIntPoint()) {
        return getPointProp(*point, propName);
    }
    if (const auto* rect = object.asIntRect()) {
        return getRectProp(*rect, propName);
    }
    if (const auto* color = object.asColorRef()) {
        return getColorProp(*color, propName);
    }
    if (const auto* image = object.asImageRef()) {
        return getImageProp(*image, propName);
    }
    if (const auto* member = object.asCastMemberRef()) {
        return getCastMemberProp(*member, propName);
    }
    if (equalsIgnoreCase(propName, "ilk")) {
        return Datum::symbol(ilkTypeName(object));
    }
    return Datum::voidValue();
}

void setObjectProperty(ExecutionContext& context, Datum& object, std::string_view propName, Datum value) {
    auto* builtinContext = context.builtinContext();
    if (object.type() == DatumType::MovieRef || object.type() == DatumType::PlayerRef) {
        if (builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
            (void)builtinContext->movieProperties->setMovieProp(propName, value);
        }
        return;
    }
    if (object.type() == DatumType::StageRef) {
        if (builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
            (void)builtinContext->movieProperties->setStageProp(propName, value);
        }
        return;
    }
    if (const auto* sprite = object.asSpriteRef()) {
        if (builtinContext != nullptr && builtinContext->spriteProperties != nullptr) {
            (void)builtinContext->spriteProperties->setSpriteProp(sprite->channel, propName, value);
        }
        return;
    }
    if (const auto* intValue = object.asInt()) {
        if (builtinContext != nullptr && builtinContext->spriteProperties != nullptr) {
            (void)builtinContext->spriteProperties->setSpriteProp(intValue->value, propName, value);
        }
        return;
    }
    if (const auto* timeout = object.asTimeoutRef()) {
        if (builtinContext != nullptr) {
            (void)builtin::TimeoutBuiltins::setProperty(*builtinContext, *timeout, propName, std::move(value));
        }
        return;
    }
    if (const auto* soundChannel = object.asSoundChannel()) {
        if (builtinContext != nullptr) {
            (void)builtin::SoundBuiltins::setProperty(*builtinContext, *soundChannel, propName, std::move(value));
        }
        return;
    }
    if (const auto* xtraInstance = object.asXtraInstance()) {
        if (builtinContext != nullptr) {
            builtin::XtraBuiltins::setProperty(*builtinContext, *xtraInstance, propName, value);
        }
        return;
    }
    if (object.type() == DatumType::ScriptInstanceRef) {
        object.scriptInstanceValue().setProperty(std::string(propName), std::move(value));
        return;
    }
    if (object.isPropList()) {
        putPropListProp(object.propListValue(), propName, std::move(value));
    }
}

bool isNoReturnArgList(const Datum& datum) {
    return datum.type() == DatumType::ArgListNoRet;
}

Datum safeExecuteHandler(ExecutionContext& context,
                         const chunks::ScriptChunk& script,
                         const chunks::ScriptChunk::Handler& handler,
                         const std::vector<Datum>& args,
                         const Datum& receiver) {
    try {
        return context.executeHandler(script, handler, args, receiver);
    } catch (const LingoException&) {
        context.setErrorState(true);
        return Datum::voidValue();
    }
}

void listSetAt(Datum::List& list, int index, Datum value) {
    if (index < 1) {
        return;
    }
    auto& items = list.items();
    const auto zeroIndex = static_cast<std::size_t>(index - 1);
    if (zeroIndex < items.size()) {
        items[zeroIndex] = std::move(value);
        return;
    }
    while (items.size() < zeroIndex) {
        items.push_back(Datum::voidValue());
    }
    items.push_back(std::move(value));
}

Datum listObjectMethod(Datum::List& list, std::string_view methodName, const std::vector<Datum>& args) {
    auto& items = list.items();
    if (equalsIgnoreCase(methodName, "getAt")) {
        if (args.empty()) {
            return Datum::voidValue();
        }
        const int index = toIntLikeJava(args[0]);
        if (index < 1 || index > static_cast<int>(items.size())) {
            throw LingoException("getAt: index " + std::to_string(index) +
                                 " out of range (list size: " + std::to_string(items.size()) + ")");
        }
        return items[static_cast<std::size_t>(index - 1)];
    }
    if (equalsIgnoreCase(methodName, "setAt")) {
        if (args.size() >= 2) {
            listSetAt(list, toIntLikeJava(args[0]), args[1]);
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "count")) {
        return Datum::of(static_cast<int>(items.size()));
    }
    if (equalsIgnoreCase(methodName, "append") || equalsIgnoreCase(methodName, "add")) {
        if (!args.empty()) {
            items.push_back(args[0]);
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "addAt")) {
        if (args.size() >= 2) {
            int index = toIntLikeJava(args[0]) - 1;
            if (index < 0) {
                index = 0;
            }
            const auto insertIndex = static_cast<std::size_t>(index);
            if (insertIndex >= items.size()) {
                items.push_back(args[1]);
            } else {
                items.insert(items.begin() + static_cast<std::ptrdiff_t>(insertIndex), args[1]);
            }
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "deleteAt")) {
        if (!args.empty()) {
            const int index = toIntLikeJava(args[0]) - 1;
            if (index >= 0 && index < static_cast<int>(items.size())) {
                items.erase(items.begin() + index);
            }
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "getOne") || equalsIgnoreCase(methodName, "findPos") ||
        equalsIgnoreCase(methodName, "getPos")) {
        if (args.empty()) {
            return Datum::of(0);
        }
        for (std::size_t index = 0; index < items.size(); ++index) {
            if (lingoEquals(items[index], args[0])) {
                return Datum::of(static_cast<int>(index + 1));
            }
        }
        return Datum::of(0);
    }
    if (equalsIgnoreCase(methodName, "getLast")) {
        return items.empty() ? Datum::voidValue() : items.back();
    }
    if (equalsIgnoreCase(methodName, "deleteOne")) {
        if (args.empty()) {
            return Datum::FALSE;
        }
        for (auto iterator = items.begin(); iterator != items.end(); ++iterator) {
            if (lingoEquals(*iterator, args[0])) {
                items.erase(iterator);
                return Datum::TRUE;
            }
        }
        return Datum::FALSE;
    }
    if (equalsIgnoreCase(methodName, "join")) {
        const std::string separator = args.empty() ? "&" : toStringLikeJava(args[0]);
        std::ostringstream out;
        for (std::size_t index = 0; index < items.size(); ++index) {
            if (index > 0) {
                out << separator;
            }
            out << toStringLikeJava(items[index]);
        }
        return Datum::of(out.str());
    }
    if (equalsIgnoreCase(methodName, "sort")) {
        std::sort(items.begin(), items.end(), [](const Datum& lhs, const Datum& rhs) {
            if (lhs.isInt() && rhs.isInt()) {
                return lhs.intValue() < rhs.intValue();
            }
            return lessIgnoreCase(toStringLikeJava(lhs), toStringLikeJava(rhs));
        });
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "duplicate")) {
        return Datum::list(items, list.sorted());
    }
    return Datum::voidValue();
}

int findPropIndexByKey(const Datum::PropList& propList, const Datum& key) {
    const std::string target = keyNameLikeJava(key);
    const auto& properties = propList.properties();
    for (std::size_t index = 0; index < properties.size(); ++index) {
        if (equalsIgnoreCase(keyNameLikeJava(properties[index].first), target)) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

Datum propListObjectMethod(Datum::PropList& propList, std::string_view methodName, const std::vector<Datum>& args) {
    auto& properties = propList.properties();
    if (equalsIgnoreCase(methodName, "count")) {
        if (!args.empty()) {
            const Datum value = getPropListKey(propList, keyNameLikeJava(args[0]));
            if (value.isList()) return Datum::of(value.listValue().count());
            if (value.isPropList()) return Datum::of(value.propListValue().count());
            return Datum::of(0);
        }
        return Datum::of(propList.count());
    }
    if (equalsIgnoreCase(methodName, "getProp") || equalsIgnoreCase(methodName, "getPropRef") ||
        equalsIgnoreCase(methodName, "getAProp") || equalsIgnoreCase(methodName, "getProperty")) {
        if (args.empty()) {
            return Datum::voidValue();
        }
        Datum value = getPropListKey(propList, keyNameLikeJava(args[0]));
        if (args.size() >= 2 && value.isList()) {
            const int index = toIntLikeJava(args[1]);
            if (index >= 1 && index <= value.listValue().count()) {
                return value.listValue().getAt(index);
            }
            return Datum::voidValue();
        }
        return value;
    }
    if (equalsIgnoreCase(methodName, "setProp") || equalsIgnoreCase(methodName, "setAProp")) {
        if (args.size() >= 2) {
            putPropListProp(propList, keyNameLikeJava(args[0]), args[1]);
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "addProp")) {
        if (args.size() >= 2) {
            properties.emplace_back(args[0], args[1]);
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "getAt")) {
        if (args.empty()) {
            return Datum::voidValue();
        }
        if (args[0].isString() || args[0].isSymbol()) {
            return getPropListKey(propList, keyNameLikeJava(args[0]));
        }
        const int index = toIntLikeJava(args[0]) - 1;
        if (index >= 0 && index < static_cast<int>(properties.size())) {
            return properties[static_cast<std::size_t>(index)].second;
        }
        if (args[0].isInt()) {
            return getPropListKey(propList, keyNameLikeJava(args[0]));
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "setAt")) {
        if (args.size() >= 2) {
            const int index = toIntLikeJava(args[0]) - 1;
            if (args[0].isInt() && index >= 0 && index < static_cast<int>(properties.size())) {
                properties[static_cast<std::size_t>(index)].second = args[1];
            } else {
                putPropListProp(propList, keyNameLikeJava(args[0]), args[1]);
            }
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "getOne")) {
        if (args.empty()) {
            return Datum::of(0);
        }
        for (const auto& entry : properties) {
            if (lingoEquals(entry.second, args[0])) {
                return entry.first;
            }
        }
        return Datum::of(0);
    }
    if (equalsIgnoreCase(methodName, "deleteProp")) {
        if (!args.empty()) {
            const int index = findPropIndexByKey(propList, args[0]);
            if (index >= 0) {
                properties.erase(properties.begin() + index);
            }
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "findPos")) {
        if (args.empty()) {
            return Datum::voidValue();
        }
        const int index = findPropIndexByKey(propList, args[0]);
        return index >= 0 ? Datum::of(index + 1) : Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "getPropAt")) {
        if (!args.empty()) {
            const int index = toIntLikeJava(args[0]) - 1;
            if (index >= 0 && index < static_cast<int>(properties.size())) {
                return properties[static_cast<std::size_t>(index)].first;
            }
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "deleteAt")) {
        if (!args.empty()) {
            const int index = toIntLikeJava(args[0]) - 1;
            if (index >= 0 && index < static_cast<int>(properties.size())) {
                properties.erase(properties.begin() + index);
            }
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "getLast")) {
        return properties.empty() ? Datum::voidValue() : properties.back().second;
    }
    if (equalsIgnoreCase(methodName, "getFirst")) {
        return properties.empty() ? Datum::voidValue() : properties.front().second;
    }
    if (equalsIgnoreCase(methodName, "duplicate")) {
        Datum copy = Datum::propList(propList.sorted());
        copy.propListValue().properties() = properties;
        return copy;
    }
    return Datum::voidValue();
}

std::vector<std::string> splitWords(std::string_view value) {
    std::vector<std::string> words;
    std::string current;
    for (const char ch : value) {
        if (static_cast<unsigned char>(ch) <= static_cast<unsigned char>(' ')) {
            if (!current.empty()) {
                words.push_back(current);
                current.clear();
            }
        } else {
            current.push_back(ch);
        }
    }
    if (!current.empty()) {
        words.push_back(std::move(current));
    }
    return words;
}

std::string pickLineDelimiter(std::string_view value) {
    if (value.find("\r\n") != std::string_view::npos) {
        return "\r\n";
    }
    if (value.find('\n') != std::string_view::npos) {
        return "\n";
    }
    if (value.find('\r') != std::string_view::npos) {
        return "\r";
    }
    return "\r\n";
}

std::vector<std::string> splitLines(std::string_view value) {
    if (value.empty()) {
        return {};
    }

    const std::string delimiter = pickLineDelimiter(value);
    std::vector<std::string> lines;
    std::size_t start = 0;
    while (true) {
        const std::size_t found = value.find(delimiter, start);
        if (found == std::string_view::npos) {
            lines.emplace_back(value.substr(start));
            break;
        }
        lines.emplace_back(value.substr(start, found - start));
        start = found + delimiter.size();
    }
    return lines;
}

std::vector<std::string> splitItems(std::string_view value, char delimiter = ',') {
    if (value.empty()) {
        return {};
    }

    std::vector<std::string> items;
    std::string current;
    for (const char ch : value) {
        if (ch == delimiter) {
            items.push_back(current);
            current.clear();
        } else {
            current.push_back(ch);
        }
    }
    items.push_back(std::move(current));
    return items;
}

std::vector<std::string> splitChars(std::string_view value) {
    std::vector<std::string> chars;
    chars.reserve(value.size());
    for (const char ch : value) {
        chars.emplace_back(1, ch);
    }
    return chars;
}

int countLines(std::string_view value) {
    if (value.empty()) {
        return 1;
    }
    return static_cast<int>(splitLines(value).size());
}

int countItems(std::string_view value, char delimiter = ',') {
    if (value.empty()) {
        return 1;
    }
    return static_cast<int>(std::count(value.begin(), value.end(), delimiter)) + 1;
}

std::vector<std::string> splitChunks(std::string_view value, StringChunkType type, char itemDelimiter = ',') {
    switch (type) {
        case StringChunkType::Char:
            return splitChars(value);
        case StringChunkType::Word:
            return splitWords(value);
        case StringChunkType::Item:
            return splitItems(value, itemDelimiter);
        case StringChunkType::Line:
            return splitLines(value);
    }
    return {};
}

std::string chunkDelimiter(StringChunkType type, char itemDelimiter = ',') {
    switch (type) {
        case StringChunkType::Char:
            return "";
        case StringChunkType::Word:
            return " ";
        case StringChunkType::Item:
            return std::string(1, itemDelimiter);
        case StringChunkType::Line:
            return "\r\n";
    }
    return "";
}

std::string sourceChunkDelimiter(std::string_view value, StringChunkType type, char itemDelimiter = ',') {
    return type == StringChunkType::Line ? pickLineDelimiter(value) : chunkDelimiter(type, itemDelimiter);
}

int countChunks(std::string_view value, StringChunkType type, char itemDelimiter = ',') {
    if (value.empty()) {
        return type == StringChunkType::Item || type == StringChunkType::Line ? 1 : 0;
    }
    if (type == StringChunkType::Char) {
        return static_cast<int>(value.size());
    }
    if (type == StringChunkType::Item) {
        return countItems(value, itemDelimiter);
    }
    return static_cast<int>(splitChunks(value, type, itemDelimiter).size());
}

std::string joinChunkRange(const std::vector<std::string>& chunks,
                           int start,
                           int end,
                           const std::string& delimiter) {
    if (chunks.empty() || start < 1 || start > static_cast<int>(chunks.size())) {
        return "";
    }

    const int actualEnd = std::min(end, static_cast<int>(chunks.size()));
    if (actualEnd < start) {
        return "";
    }

    std::string result;
    for (int index = start; index <= actualEnd; ++index) {
        if (index > start) {
            result += delimiter;
        }
        result += chunks[static_cast<std::size_t>(index - 1)];
    }
    return result;
}

std::string getChunkValue(std::string_view value, StringChunkType type, int index, char itemDelimiter = ',') {
    if (value.empty() || index < 1) {
        return "";
    }
    if (type == StringChunkType::Char) {
        if (index > static_cast<int>(value.size())) {
            return "";
        }
        return std::string(1, value[static_cast<std::size_t>(index - 1)]);
    }

    const auto chunks = splitChunks(value, type, itemDelimiter);
    if (index > static_cast<int>(chunks.size())) {
        return "";
    }
    return chunks[static_cast<std::size_t>(index - 1)];
}

std::string getChunkRangeValue(std::string_view value,
                               StringChunkType type,
                               int start,
                               int end,
                               char itemDelimiter = ',') {
    if (value.empty() || start < 1) {
        return "";
    }
    if (type == StringChunkType::Char) {
        if (start > static_cast<int>(value.size())) {
            return "";
        }
        const int actualEnd = std::min(end, static_cast<int>(value.size()));
        if (actualEnd < start) {
            return "";
        }
        return std::string(value.substr(static_cast<std::size_t>(start - 1),
                                        static_cast<std::size_t>(actualEnd - start + 1)));
    }

    const auto chunks = splitChunks(value, type, itemDelimiter);
    return joinChunkRange(chunks, start, end, chunkDelimiter(type, itemDelimiter));
}

std::string resolveChunkRange(std::string_view value,
                              StringChunkType type,
                              int first,
                              int last,
                              char itemDelimiter = ',') {
    if (first == 0 && last == 0) {
        return std::string(value);
    }
    if (first < 0 || last < 0) {
        const int count = countChunks(value, type, itemDelimiter);
        if (first < 0) {
            first = count;
        }
        if (last < 0) {
            last = count;
        }
    }

    const int effectiveLast = last == 0 ? first : last;
    if (first == effectiveLast) {
        return getChunkValue(value, type, first, itemDelimiter);
    }
    return getChunkRangeValue(value, type, first, effectiveLast, itemDelimiter);
}

std::optional<StringChunkType> stringChunkTypeByCode(int value) {
    switch (value) {
        case 0x01:
            return StringChunkType::Item;
        case 0x02:
            return StringChunkType::Word;
        case 0x03:
            return StringChunkType::Char;
        case 0x04:
            return StringChunkType::Line;
        default:
            return std::nullopt;
    }
}

std::optional<std::string_view> moviePropNameById(int id) {
    switch (id) {
        case 0x00: return "floatPrecision";
        case 0x01: return "mouseDownScript";
        case 0x02: return "mouseUpScript";
        case 0x03: return "keyDownScript";
        case 0x04: return "keyUpScript";
        case 0x05: return "timeoutScript";
        case 0x06: return "short time";
        case 0x07: return "abbr time";
        case 0x08: return "long time";
        case 0x09: return "short date";
        case 0x0A: return "abbr date";
        case 0x0B: return "long date";
        default: return std::nullopt;
    }
}

std::optional<std::string_view> spritePropNameById(int id) {
    switch (id) {
        case 0x01: return "type";
        case 0x02: return "backColor";
        case 0x03: return "bottom";
        case 0x04: return "castNum";
        case 0x05: return "constraint";
        case 0x06: return "cursor";
        case 0x07: return "foreColor";
        case 0x08: return "height";
        case 0x09: return "immediate";
        case 0x0A: return "ink";
        case 0x0B: return "left";
        case 0x0C: return "lineSize";
        case 0x0D: return "locH";
        case 0x0E: return "locV";
        case 0x0F: return "movieRate";
        case 0x10: return "movieTime";
        case 0x11: return "pattern";
        case 0x12: return "puppet";
        case 0x13: return "right";
        case 0x14: return "startTime";
        case 0x15: return "stopTime";
        case 0x16: return "stretch";
        case 0x17: return "top";
        case 0x18: return "trails";
        case 0x19: return "visible";
        case 0x1A: return "volume";
        case 0x1B: return "width";
        case 0x1C: return "blend";
        case 0x1D: return "scriptNum";
        case 0x1E: return "moveableSprite";
        case 0x1F: return "editableText";
        case 0x20: return "scoreColor";
        case 0x21: return "loc";
        case 0x22: return "rect";
        case 0x23: return "memberNum";
        case 0x24: return "castLibNum";
        case 0x25: return "member";
        case 0x26: return "scriptInstanceList";
        case 0x27: return "currentTime";
        case 0x28: return "mostRecentCuePoint";
        case 0x29: return "tweened";
        case 0x2A: return "name";
        default: return std::nullopt;
    }
}

std::optional<std::string_view> animationPropNameById(int id) {
    switch (id) {
        case 0x01: return "beepOn";
        case 0x02: return "buttonStyle";
        case 0x03: return "centerStage";
        case 0x04: return "checkBoxAccess";
        case 0x05: return "checkboxType";
        case 0x06: return "colorDepth";
        case 0x07: return "colorQD";
        case 0x08: return "exitLock";
        case 0x09: return "fixStageSize";
        case 0x0A: return "fullColorPermit";
        case 0x0B: return "imageDirect";
        case 0x0C: return "doubleClick";
        case 0x0D: return "key";
        case 0x0E: return "lastClick";
        case 0x0F: return "lastEvent";
        case 0x10: return "keyCode";
        case 0x11: return "lastKey";
        case 0x12: return "lastRoll";
        case 0x13: return "timeoutLapsed";
        case 0x14: return "multiSound";
        case 0x15: return "pauseState";
        case 0x16: return "quickTimePresent";
        case 0x17: return "selEnd";
        case 0x18: return "selStart";
        case 0x19: return "soundEnabled";
        case 0x1A: return "soundLevel";
        case 0x1B: return "stageColor";
        case 0x1D: return "switchColorDepth";
        case 0x1E: return "timeoutKeyDown";
        case 0x1F: return "timeoutLength";
        case 0x20: return "timeoutMouse";
        case 0x21: return "timeoutPlay";
        case 0x22: return "timer";
        case 0x23: return "preLoadRAM";
        case 0x24: return "videoForWindowsPresent";
        case 0x25: return "netPresent";
        case 0x26: return "safePlayer";
        case 0x27: return "soundKeepDevice";
        case 0x28: return "soundMixMedia";
        default: return std::nullopt;
    }
}

std::optional<std::string_view> animation2PropNameById(int id) {
    switch (id) {
        case 0x01: return "perFrameHook";
        case 0x02: return "number of castMembers";
        case 0x03: return "number of menus";
        case 0x04: return "number of castLibs";
        case 0x05: return "number of xtras";
        default: return std::nullopt;
    }
}

std::optional<std::string_view> soundPropNameById(int id) {
    switch (id) {
        case 0x01: return "volume";
        case 0x02: return "pan";
        case 0x03: return "loopCount";
        case 0x04: return "startTime";
        case 0x05: return "endTime";
        case 0x06: return "loopStartTime";
        case 0x07: return "loopEndTime";
        default: return std::nullopt;
    }
}

char currentItemDelimiter(ExecutionContext& context) {
    if (auto* builtinContext = context.builtinContext(); builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
        return builtinContext->movieProperties->getItemDelimiter();
    }
    return ',';
}

std::string getLastChunkValue(std::string_view value, StringChunkType type, char itemDelimiter = ',') {
    if (value.empty()) {
        return "";
    }
    const int count = countChunks(value, type, itemDelimiter);
    return count == 0 ? std::string() : getChunkValue(value, type, count, itemDelimiter);
}

std::optional<std::pair<int, int>> itemByteRange(std::string_view value, int first, int last, char delimiter) {
    int chunkNum = 1;
    int chunkStart = 0;
    int rangeStart = -1;
    for (int index = 0; index <= static_cast<int>(value.size()); ++index) {
        if (index == static_cast<int>(value.size()) || value[static_cast<std::size_t>(index)] == delimiter) {
            if (chunkNum == first) {
                rangeStart = chunkStart;
            }
            if (chunkNum == last) {
                if (rangeStart < 0) {
                    return std::nullopt;
                }
                return std::pair{rangeStart, index};
            }
            if (chunkNum > last) {
                break;
            }
            ++chunkNum;
            chunkStart = index + 1;
        }
    }
    if (rangeStart >= 0) {
        return std::pair{rangeStart, static_cast<int>(value.size())};
    }
    return std::nullopt;
}

std::optional<std::pair<int, int>> chunkByteRange(std::string_view value,
                                                  StringChunkType type,
                                                  int first,
                                                  int last,
                                                  char itemDelimiter = ',') {
    if (value.empty()) {
        return std::nullopt;
    }
    last = last == 0 ? first : last;
    if (first < 1) {
        first = 1;
    }
    if (last < first) {
        return std::nullopt;
    }

    if (type == StringChunkType::Char) {
        if (first > static_cast<int>(value.size())) {
            return std::nullopt;
        }
        return std::pair{first - 1, std::min(last, static_cast<int>(value.size()))};
    }
    if (type == StringChunkType::Item) {
        return itemByteRange(value, first, last, itemDelimiter);
    }

    const auto chunks = splitChunks(value, type, itemDelimiter);
    if (chunks.empty() || first > static_cast<int>(chunks.size())) {
        return std::nullopt;
    }
    last = std::min(last, static_cast<int>(chunks.size()));
    const std::string delimiter = sourceChunkDelimiter(value, type, itemDelimiter);

    int start = 0;
    for (int index = 0; index < first - 1; ++index) {
        start += static_cast<int>(chunks[static_cast<std::size_t>(index)].size());
        if (index < static_cast<int>(chunks.size()) - 1) {
            start += static_cast<int>(delimiter.size());
        }
    }

    int end = start;
    for (int index = first - 1; index < last; ++index) {
        end += static_cast<int>(chunks[static_cast<std::size_t>(index)].size());
        if (index < last - 1 && index < static_cast<int>(chunks.size()) - 1) {
            end += static_cast<int>(delimiter.size());
        }
    }
    return std::pair{start, end};
}

bool startsWithAt(std::string_view value, std::string_view needle, int offset) {
    if (needle.empty() || offset < 0 || offset + static_cast<int>(needle.size()) > static_cast<int>(value.size())) {
        return false;
    }
    return value.substr(static_cast<std::size_t>(offset), needle.size()) == needle;
}

bool prefixEndsWith(std::string_view value, int end, std::string_view needle) {
    if (needle.empty() || end < static_cast<int>(needle.size()) || end > static_cast<int>(value.size())) {
        return false;
    }
    return value.substr(static_cast<std::size_t>(end) - needle.size(), needle.size()) == needle;
}

std::string deleteChunkValue(std::string_view value,
                             StringChunkType type,
                             int first,
                             int last,
                             char itemDelimiter = ',') {
    auto range = chunkByteRange(value, type, first, last, itemDelimiter);
    if (!range) {
        return std::string(value);
    }

    auto [deleteStart, deleteEnd] = *range;
    if (type != StringChunkType::Char) {
        const std::string delimiter = sourceChunkDelimiter(value, type, itemDelimiter);
        if (deleteEnd < static_cast<int>(value.size()) && startsWithAt(value, delimiter, deleteEnd)) {
            deleteEnd += static_cast<int>(delimiter.size());
        } else if (deleteStart > 0 && prefixEndsWith(value, deleteStart, delimiter)) {
            deleteStart -= static_cast<int>(delimiter.size());
        }
    }

    if (deleteStart == 0) {
        return std::string(value.substr(static_cast<std::size_t>(deleteEnd)));
    }
    if (deleteEnd >= static_cast<int>(value.size())) {
        return std::string(value.substr(0, static_cast<std::size_t>(deleteStart)));
    }
    return std::string(value.substr(0, static_cast<std::size_t>(deleteStart))) +
           std::string(value.substr(static_cast<std::size_t>(deleteEnd)));
}

std::string deleteCharChunkRefValue(std::string_view value, int first, int last) {
    if (value.empty() || first < 1) {
        return std::string(value);
    }
    const int start = std::max(0, first - 1);
    const int end = std::max(start, std::min(static_cast<int>(value.size()), last));
    if (start >= static_cast<int>(value.size()) || start >= end) {
        return std::string(value);
    }
    return std::string(value.substr(0, static_cast<std::size_t>(start))) +
           std::string(value.substr(static_cast<std::size_t>(end)));
}

int normalizeCharChunkIndex(int index, int length, bool before) {
    if (index < 0) {
        return length;
    }
    if (before) {
        return index < 1 ? 1 : index;
    }
    if (index == 0) {
        return 0;
    }
    return index;
}

std::optional<std::pair<int, int>> charChunkReplaceRange(std::string_view value, int first, int last) {
    const int length = static_cast<int>(value.size());
    const int normalizedFirst = normalizeCharChunkIndex(first, length, true);
    const int normalizedLast = normalizeCharChunkIndex(last == 0 ? first : last, length, true);
    if (normalizedFirst < 1 || normalizedFirst > length || normalizedLast < normalizedFirst) {
        return std::nullopt;
    }
    const int rangeStart = normalizedFirst - 1;
    const int rangeEnd = std::min(normalizedLast, length);
    if (rangeEnd < rangeStart) {
        return std::nullopt;
    }
    return std::pair{rangeStart, rangeEnd};
}

int chunkInsertionIndex(std::string_view value,
                        StringChunkType type,
                        int targetIndex,
                        bool before,
                        char itemDelimiter = ',') {
    const int length = static_cast<int>(value.size());
    if (type == StringChunkType::Char) {
        const int normalized = normalizeCharChunkIndex(targetIndex, length, before);
        if (before) {
            if (normalized <= 1) {
                return 0;
            }
            if (normalized > length) {
                return length;
            }
            return normalized - 1;
        }
        if (normalized <= 0) {
            return 0;
        }
        if (normalized >= length) {
            return length;
        }
        return normalized;
    }

    if (const auto range = chunkByteRange(value, type, targetIndex, targetIndex, itemDelimiter)) {
        return before ? range->first : range->second;
    }
    if (before) {
        return targetIndex <= 1 ? 0 : length;
    }
    return targetIndex <= 0 ? 0 : length;
}

std::string putIntoChunkValue(std::string_view value,
                              StringChunkType type,
                              int first,
                              int last,
                              std::string_view replacement,
                              char itemDelimiter = ',') {
    auto range = chunkByteRange(value, type, first, last, itemDelimiter);
    if (!range) {
        return std::string(value);
    }
    return std::string(value.substr(0, static_cast<std::size_t>(range->first))) +
           std::string(replacement) +
           std::string(value.substr(static_cast<std::size_t>(range->second)));
}

std::string putBeforeChunkValue(std::string_view value,
                                StringChunkType type,
                                int first,
                                std::string_view inserted,
                                char itemDelimiter = ',') {
    const int insertAt = chunkInsertionIndex(value, type, first, true, itemDelimiter);
    return std::string(value.substr(0, static_cast<std::size_t>(insertAt))) +
           std::string(inserted) +
           std::string(value.substr(static_cast<std::size_t>(insertAt)));
}

std::string putAfterChunkValue(std::string_view value,
                               StringChunkType type,
                               int targetIndex,
                               std::string_view inserted,
                               char itemDelimiter = ',') {
    const int insertAt = chunkInsertionIndex(value, type, targetIndex, false, itemDelimiter);
    return std::string(value.substr(0, static_cast<std::size_t>(insertAt))) +
           std::string(inserted) +
           std::string(value.substr(static_cast<std::size_t>(insertAt)));
}

struct ChunkSpec {
    StringChunkType type;
    int first;
    int last;
};

ChunkSpec chooseSingleChunkSpec(int firstChar,
                                int lastChar,
                                int firstWord,
                                int lastWord,
                                int firstItem,
                                int lastItem,
                                int firstLine,
                                int lastLine) {
    if (firstLine != 0 || lastLine != 0) {
        return {StringChunkType::Line, firstLine, lastLine};
    }
    if (firstItem != 0 || lastItem != 0) {
        return {StringChunkType::Item, firstItem, lastItem};
    }
    if (firstWord != 0 || lastWord != 0) {
        return {StringChunkType::Word, firstWord, lastWord};
    }
    if (firstChar != 0 || lastChar != 0) {
        return {StringChunkType::Char, firstChar, lastChar};
    }
    return {StringChunkType::Char, 1, 1};
}

Datum stringObjectMethod(ExecutionContext& context,
                         const Datum& target,
                         std::string_view methodName,
                         const std::vector<Datum>& args) {
    const std::string value = toStringLikeJava(target);
    if (equalsIgnoreCase(methodName, "length")) {
        return Datum::of(static_cast<int>(value.size()));
    }
    if (equalsIgnoreCase(methodName, "char")) {
        if (args.empty()) {
            return Datum::of(std::string());
        }
        const int index = toIntLikeJava(args[0]);
        if (index >= 1 && index <= static_cast<int>(value.size())) {
            return Datum::of(value.substr(static_cast<std::size_t>(index - 1), 1));
        }
        return Datum::of(std::string());
    }
    if (equalsIgnoreCase(methodName, "count")) {
        if (args.empty()) {
            return Datum::of(static_cast<int>(value.size()));
        }
        const std::string chunkType = keyNameLikeJava(args[0]);
        if (equalsIgnoreCase(chunkType, "char")) return Datum::of(static_cast<int>(value.size()));
        if (equalsIgnoreCase(chunkType, "word")) return Datum::of(static_cast<int>(splitWords(value).size()));
        if (equalsIgnoreCase(chunkType, "line")) return Datum::of(countLines(value));
        if (equalsIgnoreCase(chunkType, "item")) return Datum::of(countItems(value, currentItemDelimiter(context)));
        return Datum::of(static_cast<int>(value.size()));
    }
    if (equalsIgnoreCase(methodName, "getProp") || equalsIgnoreCase(methodName, "getPropRef")) {
        if (args.size() < 2) {
            return Datum::of(std::string());
        }
        StringChunkType chunkType = StringChunkType::Char;
        try {
            chunkType = stringChunkTypeFromName(keyNameLikeJava(args[0]));
        } catch (const std::invalid_argument&) {
            return Datum::of(std::string());
        }
        const int start = toIntLikeJava(args[1]);
        const int end = equalsIgnoreCase(methodName, "getPropRef") || args.size() < 3 ? start : toIntLikeJava(args[2]);
        return Datum::of(resolveChunkRange(value, chunkType, start, end, currentItemDelimiter(context)));
    }
    return Datum::voidValue();
}

Datum pointObjectMethod(const Datum::IntPoint& point, std::string_view methodName, const std::vector<Datum>& args) {
    if (equalsIgnoreCase(methodName, "getAt")) {
        if (args.empty()) return Datum::voidValue();
        const int index = toIntLikeJava(args[0]);
        if (index == 1) return Datum::of(point.x);
        if (index == 2) return Datum::of(point.y);
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "duplicate")) {
        return Datum::intPoint(point.x, point.y);
    }
    if (equalsIgnoreCase(methodName, "inside")) {
        if (!args.empty()) {
            if (const auto* rect = args[0].asIntRect()) {
                const bool inside = point.x >= rect->left && point.x < rect->right &&
                                    point.y >= rect->top && point.y < rect->bottom;
                return inside ? Datum::TRUE : Datum::FALSE;
            }
        }
        return Datum::FALSE;
    }
    return Datum::voidValue();
}

Datum rectObjectMethod(const Datum::IntRect& rect, std::string_view methodName, const std::vector<Datum>& args) {
    if (equalsIgnoreCase(methodName, "getAt")) {
        if (args.empty()) return Datum::voidValue();
        switch (toIntLikeJava(args[0])) {
            case 1: return Datum::of(rect.left);
            case 2: return Datum::of(rect.top);
            case 3: return Datum::of(rect.right);
            case 4: return Datum::of(rect.bottom);
            default: return Datum::voidValue();
        }
    }
    if (equalsIgnoreCase(methodName, "duplicate")) {
        return Datum::intRect(rect.left, rect.top, rect.right, rect.bottom);
    }
    return Datum::voidValue();
}

bool imageFill(bitmap::Bitmap& bmp, const std::vector<Datum>& args) {
    if (args.size() < 2) return false;

    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    Datum colorDatum = Datum::voidValue();

    if (const auto* rect = args[0].asIntRect()) {
        left = rect->left;
        top = rect->top;
        right = rect->right;
        bottom = rect->bottom;
        colorDatum = args[1];
    } else if (args.size() >= 5) {
        left = toIntLikeJava(args[0]);
        top = toIntLikeJava(args[1]);
        right = toIntLikeJava(args[2]);
        bottom = toIntLikeJava(args[3]);
        colorDatum = args[4];
    } else {
        return false;
    }

    if (colorDatum.isPropList()) {
        const Datum propColor = getPropListKey(colorDatum.propListValue(), "color");
        if (!propColor.isVoid()) {
            colorDatum = propColor;
        }
    }
    if (colorDatum.isVoid()) {
        return false;
    }

    const int width = right - left;
    const int height = bottom - top;
    if (width <= 0 || height <= 0) {
        return false;
    }

    bmp.fillRect(left, top, width, height, imageColorArgb(colorDatum, &bmp));
    return true;
}

void imageDrawRect(bitmap::Bitmap& bmp, int x, int y, int width, int height, std::uint32_t argb) {
    for (int px = x; px < x + width; ++px) {
        bmp.setPixel(px, y, argb);
        bmp.setPixel(px, y + height - 1, argb);
    }
    for (int py = y; py < y + height; ++py) {
        bmp.setPixel(x, py, argb);
        bmp.setPixel(x + width - 1, py, argb);
    }
}

void imageDrawLine(bitmap::Bitmap& bmp, int x0, int y0, int x1, int y1, std::uint32_t argb) {
    const int dx = std::abs(x1 - x0);
    const int dy = std::abs(y1 - y0);
    const int sx = x0 < x1 ? 1 : -1;
    const int sy = y0 < y1 ? 1 : -1;
    int err = dx - dy;

    while (true) {
        bmp.setPixel(x0, y0, argb);
        if (x0 == x1 && y0 == y1) {
            break;
        }
        const int e2 = 2 * err;
        if (e2 > -dy) {
            err -= dy;
            x0 += sx;
        }
        if (e2 < dx) {
            err += dx;
            y0 += sy;
        }
    }
}

void imageDrawEllipse(bitmap::Bitmap& bmp, int cx, int cy, int rx, int ry, std::uint32_t argb) {
    int x = 0;
    int y = ry;
    const int rxSq = rx * rx;
    const int rySq = ry * ry;
    int p = static_cast<int>(rySq - rxSq * ry + 0.25 * rxSq);

    while (rySq * x < rxSq * y) {
        bmp.setPixel(cx + x, cy + y, argb);
        bmp.setPixel(cx - x, cy + y, argb);
        bmp.setPixel(cx + x, cy - y, argb);
        bmp.setPixel(cx - x, cy - y, argb);
        if (p < 0) {
            ++x;
            p += 2 * rySq * x + rySq;
        } else {
            ++x;
            --y;
            p += 2 * rySq * x - 2 * rxSq * y + rySq;
        }
    }

    p = static_cast<int>(rySq * (x + 0.5) * (x + 0.5) + rxSq * (y - 1) * (y - 1) - rxSq * rySq);
    while (y >= 0) {
        bmp.setPixel(cx + x, cy + y, argb);
        bmp.setPixel(cx - x, cy + y, argb);
        bmp.setPixel(cx + x, cy - y, argb);
        bmp.setPixel(cx - x, cy - y, argb);
        if (p > 0) {
            --y;
            p -= 2 * rxSq * y + rxSq;
        } else {
            --y;
            ++x;
            p += 2 * rySq * x - 2 * rxSq * y + rxSq;
        }
    }
}

Datum imageDraw(bitmap::Bitmap& bmp, const std::vector<Datum>& args) {
    if (args.size() < 2) return Datum::voidValue();

    int left = 0;
    int top = 0;
    int right = 0;
    int bottom = 0;
    Datum propsArg = Datum::voidValue();

    if (const auto* rect = args[0].asIntRect()) {
        left = rect->left;
        top = rect->top;
        right = rect->right;
        bottom = rect->bottom;
        propsArg = args[1];
    } else if (args.size() >= 5) {
        left = toIntLikeJava(args[0]);
        top = toIntLikeJava(args[1]);
        right = toIntLikeJava(args[2]);
        bottom = toIntLikeJava(args[3]);
        propsArg = args[4];
    } else {
        return Datum::voidValue();
    }

    std::uint32_t colorArgb = 0xFF000000U;
    std::string shapeType = "rect";
    if (propsArg.isPropList()) {
        const Datum colorDatum = getPropListKey(propsArg.propListValue(), "color");
        if (!colorDatum.isVoid()) {
            colorArgb = imageColorArgb(colorDatum, &bmp);
        }
        const Datum shapeDatum = getPropListKey(propsArg.propListValue(), "shapeType");
        if (const auto* shape = shapeDatum.asSymbol()) {
            shapeType = shape->name;
        }
    } else {
        colorArgb = imageColorArgb(propsArg, &bmp);
    }

    const int width = right - left;
    const int height = bottom - top;
    if (width <= 0 || height <= 0) {
        return Datum::voidValue();
    }

    if (equalsIgnoreCase(shapeType, "oval") || equalsIgnoreCase(shapeType, "ellipse")) {
        imageDrawEllipse(bmp, left + width / 2, top + height / 2, width / 2, height / 2, colorArgb);
    } else if (equalsIgnoreCase(shapeType, "line")) {
        imageDrawLine(bmp, left, top, right, bottom, colorArgb);
    } else {
        imageDrawRect(bmp, left, top, width, height, colorArgb);
    }
    return Datum::voidValue();
}

int imageMaskAlphaFromPixel(std::uint32_t pixel) {
    const int r = static_cast<int>((pixel >> 16) & 0xFFU);
    const int g = static_cast<int>((pixel >> 8) & 0xFFU);
    const int b = static_cast<int>(pixel & 0xFFU);
    return ((77 * r) + (150 * g) + (29 * b) + 128) >> 8;
}

bool imageAlphaHasTransparency(const bitmap::Bitmap& alpha) {
    for (int y = 0; y < alpha.height(); ++y) {
        for (int x = 0; x < alpha.width(); ++x) {
            if (((alpha.getPixel(x, y) >> 24) & 0xFFU) < 255U) {
                return true;
            }
        }
    }
    return false;
}

bool imageHasWhiteEdgeAndDarkInterior(const bitmap::Bitmap& alpha) {
    const int width = alpha.width();
    const int height = alpha.height();
    if (width <= 0 || height <= 0) {
        return false;
    }

    int edgePixels = 0;
    int whiteEdgePixels = 0;
    bool hasDarkPixel = false;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int luma = imageMaskAlphaFromPixel(alpha.getPixel(x, y));
            if (luma < 250) {
                hasDarkPixel = true;
            }
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                ++edgePixels;
                if (luma >= 250) {
                    ++whiteEdgePixels;
                }
            }
        }
    }
    return hasDarkPixel && edgePixels > 0 && whiteEdgePixels * 4 >= edgePixels * 3;
}

std::vector<std::pair<int, int>> imageUniqueCorners(int width, int height) {
    if (width == 1 && height == 1) {
        return {{0, 0}};
    }
    if (height == 1) {
        return {{0, 0}, {width - 1, 0}};
    }
    if (width == 1) {
        return {{0, 0}, {0, height - 1}};
    }
    return {{0, 0}, {width - 1, 0}, {0, height - 1}, {width - 1, height - 1}};
}

bool imageHasWhiteCornersAndDarkPixels(const bitmap::Bitmap& alpha) {
    const int width = alpha.width();
    const int height = alpha.height();
    if (width <= 0 || height <= 0) {
        return false;
    }

    const auto corners = imageUniqueCorners(width, height);
    int whiteCorners = 0;
    for (const auto& [x, y] : corners) {
        if (imageMaskAlphaFromPixel(alpha.getPixel(x, y)) >= 250) {
            ++whiteCorners;
        }
    }
    if (whiteCorners < static_cast<int>(corners.size())) {
        return false;
    }

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (imageMaskAlphaFromPixel(alpha.getPixel(x, y)) < 250) {
                return true;
            }
        }
    }
    return false;
}

bool imageHasMattePolarity(const bitmap::Bitmap& alpha) {
    return imageAlphaHasTransparency(alpha) ||
           imageHasWhiteEdgeAndDarkInterior(alpha) ||
           imageHasWhiteCornersAndDarkPixels(alpha);
}

Datum imageSetAlpha(bitmap::Bitmap& bmp, const std::vector<Datum>& args) {
    if (bmp.bitDepth() != 32 || args.empty()) {
        return Datum::FALSE;
    }

    if (const auto* alphaRef = args[0].asImageRef()) {
        const auto& alpha = alphaRef->bitmap;
        if (alpha == nullptr ||
            alpha->bitDepth() != 8 ||
            alpha->width() != bmp.width() ||
            alpha->height() != bmp.height()) {
            return Datum::FALSE;
        }

        const bool mattePolarity = imageHasMattePolarity(*alpha);
        for (int y = 0; y < bmp.height(); ++y) {
            for (int x = 0; x < bmp.width(); ++x) {
                int alphaLevel = imageMaskAlphaFromPixel(alpha->getPixel(x, y));
                if (mattePolarity) {
                    alphaLevel = 255 - alphaLevel;
                }
                const auto pixel = bmp.getPixel(x, y);
                bmp.setPixel(x, y, (static_cast<std::uint32_t>(alphaLevel & 0xFF) << 24) | (pixel & 0x00FFFFFFU));
            }
        }
        bmp.setNativeAlpha(true);
        return Datum::TRUE;
    }

    const int alphaLevel = std::clamp(toIntLikeJava(args[0]), 0, 255);
    for (int y = 0; y < bmp.height(); ++y) {
        for (int x = 0; x < bmp.width(); ++x) {
            const auto pixel = bmp.getPixel(x, y);
            bmp.setPixel(x, y, (static_cast<std::uint32_t>(alphaLevel & 0xFF) << 24) | (pixel & 0x00FFFFFFU));
        }
    }
    bmp.setNativeAlpha(true);
    return Datum::TRUE;
}

struct ImageFloodFillMatte {
    std::optional<int> paletteIndex;
    int colorRgb = 0xFFFFFF;
    int tolerance = 0;

    [[nodiscard]] bool usesPaletteIndex() const { return paletteIndex.has_value(); }
};

std::vector<int> imageCornerIndices(int width, int height) {
    return {
        0,
        std::max(0, width - 1),
        std::max(0, (height - 1) * width),
        std::max(0, (height - 1) * width + (width - 1)),
    };
}

std::vector<int> imageEdgeIndices(int width, int height) {
    std::vector<int> indices;
    indices.reserve(static_cast<std::size_t>(std::max(1, (width * 2) + std::max(0, height - 2) * 2)));
    for (int x = 0; x < width; ++x) {
        indices.push_back(x);
        if (height > 1) {
            indices.push_back((height - 1) * width + x);
        }
    }
    for (int y = 1; y < height - 1; ++y) {
        indices.push_back(y * width);
        if (width > 1) {
            indices.push_back(y * width + (width - 1));
        }
    }
    return indices;
}

bool imageHasPaletteIndices(const std::optional<std::vector<std::uint8_t>>& paletteIndices, int width, int height) {
    return paletteIndices.has_value() && paletteIndices->size() >= static_cast<std::size_t>(width * height);
}

bool imageIsUniformPaletteIndex(const std::vector<std::uint8_t>& paletteIndices, int paletteIndex) {
    return std::all_of(paletteIndices.begin(), paletteIndices.end(), [&](std::uint8_t entry) {
        return static_cast<int>(entry) == paletteIndex;
    });
}

int imageResolvePaletteIndexRgb(const std::vector<std::uint32_t>& pixels,
                                const std::vector<std::uint8_t>& paletteIndices,
                                int paletteIndex) {
    for (std::size_t index = 0; index < pixels.size() && index < paletteIndices.size(); ++index) {
        if (static_cast<int>(paletteIndices[index]) == paletteIndex) {
            return static_cast<int>(pixels[index] & 0x00FFFFFFU);
        }
    }
    return 0xFFFFFF;
}

bool imageCornerContainsPaletteIndex(const std::vector<std::uint8_t>& paletteIndices,
                                     int width,
                                     int height,
                                     int paletteIndex) {
    for (const int index : imageCornerIndices(width, height)) {
        if (index >= 0 && static_cast<std::size_t>(index) < paletteIndices.size() &&
            static_cast<int>(paletteIndices[static_cast<std::size_t>(index)]) == paletteIndex) {
            return true;
        }
    }
    return false;
}

bool imageDefaultIndexedMatteRgb(int rgb) {
    return rgb == 0x000000 || rgb == 0xFFFFFF;
}

std::optional<int> imageInferDominantEdgePaletteIndex(const std::vector<std::uint32_t>& pixels,
                                                      const std::vector<std::uint8_t>& paletteIndices,
                                                      int width,
                                                      int height) {
    if (width <= 0 || height <= 0) return std::nullopt;

    std::array<int, 256> counts{};
    int opaqueEdgeCount = 0;
    int dominantIndex = -1;
    int dominantCount = 0;
    for (const int index : imageEdgeIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        const int paletteIndex = static_cast<int>(paletteIndices[static_cast<std::size_t>(index)]);
        const int count = ++counts[static_cast<std::size_t>(paletteIndex)];
        ++opaqueEdgeCount;
        if (count > dominantCount) {
            dominantCount = count;
            dominantIndex = paletteIndex;
        }
    }
    if (opaqueEdgeCount == 0 || dominantIndex < 0 || imageIsUniformPaletteIndex(paletteIndices, dominantIndex)) {
        return std::nullopt;
    }

    int opaqueCornerCount = 0;
    for (const int index : imageCornerIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        ++opaqueCornerCount;
        if (static_cast<int>(paletteIndices[static_cast<std::size_t>(index)]) != dominantIndex) {
            return std::nullopt;
        }
    }
    if (opaqueCornerCount == 0 || dominantCount * 4 < opaqueEdgeCount * 3) {
        return std::nullopt;
    }
    return dominantIndex;
}

std::optional<ImageFloodFillMatte> imageResolveIndexedFloodFillMatte(
    const std::vector<std::uint32_t>& pixels,
    const std::vector<std::uint8_t>& paletteIndices,
    int width,
    int height) {
    if (const auto dominant = imageInferDominantEdgePaletteIndex(pixels, paletteIndices, width, height)) {
        const int matteRgb = imageResolvePaletteIndexRgb(pixels, paletteIndices, *dominant);
        if (*dominant == 0 && imageDefaultIndexedMatteRgb(matteRgb)) {
            return ImageFloodFillMatte{*dominant, matteRgb, 0};
        }
    }
    if (imageCornerContainsPaletteIndex(paletteIndices, width, height, 0)) {
        const int indexZeroRgb = imageResolvePaletteIndexRgb(pixels, paletteIndices, 0);
        if (imageDefaultIndexedMatteRgb(indexZeroRgb)) {
            return ImageFloodFillMatte{0, indexZeroRgb, 0};
        }
    }
    return std::nullopt;
}

bool imageIsUniformRgb(const std::vector<std::uint32_t>& pixels, int rgb) {
    for (const auto pixel : pixels) {
        if (((pixel >> 24) & 0xFFU) != 0 && static_cast<int>(pixel & 0x00FFFFFFU) != rgb) {
            return false;
        }
    }
    return true;
}

std::optional<int> imageInferDominantEdgeRgb(const std::vector<std::uint32_t>& pixels, int width, int height) {
    if (width <= 0 || height <= 0) return std::nullopt;

    std::unordered_map<int, int> counts;
    int opaqueEdgeCount = 0;
    int dominantRgb = -1;
    int dominantCount = 0;
    for (const int index : imageEdgeIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        const int rgb = static_cast<int>(pixel & 0x00FFFFFFU);
        const int count = ++counts[rgb];
        ++opaqueEdgeCount;
        if (count > dominantCount) {
            dominantCount = count;
            dominantRgb = rgb;
        }
    }
    if (opaqueEdgeCount == 0 || dominantRgb < 0 || imageIsUniformRgb(pixels, dominantRgb)) {
        return std::nullopt;
    }

    int opaqueCornerCount = 0;
    for (const int index : imageCornerIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) == 0) {
            continue;
        }
        ++opaqueCornerCount;
        if (static_cast<int>(pixel & 0x00FFFFFFU) != dominantRgb) {
            return std::nullopt;
        }
    }
    if (opaqueCornerCount == 0 || dominantCount * 4 < opaqueEdgeCount * 3) {
        return std::nullopt;
    }
    return dominantRgb;
}

bool imageCornerContainsOpaqueRgb(const std::vector<std::uint32_t>& pixels, int width, int height, int rgb) {
    for (const int index : imageCornerIndices(width, height)) {
        const auto pixel = pixels[static_cast<std::size_t>(index)];
        if (((pixel >> 24) & 0xFFU) != 0 && static_cast<int>(pixel & 0x00FFFFFFU) == rgb) {
            return true;
        }
    }
    return false;
}

std::optional<ImageFloodFillMatte> imageResolveRgbFloodFillMatte(const std::vector<std::uint32_t>& pixels,
                                                                 int width,
                                                                 int height) {
    if (const auto dominant = imageInferDominantEdgeRgb(pixels, width, height)) {
        return ImageFloodFillMatte{std::nullopt, *dominant, 0};
    }
    if (!imageCornerContainsOpaqueRgb(pixels, width, height, 0xFFFFFF)) {
        return std::nullopt;
    }
    return ImageFloodFillMatte{std::nullopt, 0xFFFFFF, 0};
}

std::optional<ImageFloodFillMatte> imageResolveFloodFillMatte(
    const std::vector<std::uint32_t>& pixels,
    const std::optional<std::vector<std::uint8_t>>& paletteIndices,
    int width,
    int height) {
    if (imageHasPaletteIndices(paletteIndices, width, height)) {
        return imageResolveIndexedFloodFillMatte(pixels, *paletteIndices, width, height);
    }
    return imageResolveRgbFloodFillMatte(pixels, width, height);
}

bool imageMatchesRgb(std::uint32_t pixel, int matteRgb, int tolerance) {
    const int pr = static_cast<int>((pixel >> 16) & 0xFFU);
    const int pg = static_cast<int>((pixel >> 8) & 0xFFU);
    const int pb = static_cast<int>(pixel & 0xFFU);
    const int mr = (matteRgb >> 16) & 0xFF;
    const int mg = (matteRgb >> 8) & 0xFF;
    const int mb = matteRgb & 0xFF;
    return std::abs(pr - mr) <= tolerance && std::abs(pg - mg) <= tolerance && std::abs(pb - mb) <= tolerance;
}

bool imageIsTransparentOrMatte(const std::vector<std::uint32_t>& pixels,
                               const std::optional<std::vector<std::uint8_t>>& paletteIndices,
                               int index,
                               const ImageFloodFillMatte& matte) {
    const auto pixel = pixels[static_cast<std::size_t>(index)];
    if (((pixel >> 24) & 0xFFU) == 0) {
        return true;
    }
    if (matte.usesPaletteIndex() && paletteIndices.has_value() &&
        static_cast<std::size_t>(index) < paletteIndices->size()) {
        return static_cast<int>((*paletteIndices)[static_cast<std::size_t>(index)]) == *matte.paletteIndex;
    }
    return imageMatchesRgb(pixel, matte.colorRgb, matte.tolerance);
}

std::vector<bool> imageComputeFloodFillTransparency(
    const std::vector<std::uint32_t>& pixels,
    const std::optional<std::vector<std::uint8_t>>& paletteIndices,
    int width,
    int height,
    const ImageFloodFillMatte& matte) {
    std::vector<bool> transparent(static_cast<std::size_t>(width * height), false);
    std::queue<int> queue;
    const auto seed = [&](int x, int y) {
        const int index = y * width + x;
        if (!transparent[static_cast<std::size_t>(index)] &&
            imageIsTransparentOrMatte(pixels, paletteIndices, index, matte)) {
            transparent[static_cast<std::size_t>(index)] = true;
            queue.push(index);
        }
    };

    for (int x = 0; x < width; ++x) {
        seed(x, 0);
        seed(x, height - 1);
    }
    for (int y = 1; y < height - 1; ++y) {
        seed(0, y);
        seed(width - 1, y);
    }

    while (!queue.empty()) {
        const int index = queue.front();
        queue.pop();
        const int x = index % width;
        const int y = index / width;
        if (x > 0) seed(x - 1, y);
        if (x < width - 1) seed(x + 1, y);
        if (y > 0) seed(x, y - 1);
        if (y < height - 1) seed(x, y + 1);
    }
    return transparent;
}

std::shared_ptr<bitmap::Bitmap> imageCreateAlphaMatte(const bitmap::Bitmap& src, int alphaThreshold) {
    const int width = src.width();
    const int height = src.height();
    const int threshold = std::clamp(alphaThreshold, 0, 255);
    std::vector<std::uint32_t> mask(static_cast<std::size_t>(width * height), 0x00FFFFFFU);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int alpha = static_cast<int>((src.getPixel(x, y) >> 24) & 0xFFU);
            if (alpha < threshold) {
                alpha = 0;
            }
            mask[static_cast<std::size_t>(y * width + x)] =
                (static_cast<std::uint32_t>(alpha) << 24) | 0x00FFFFFFU;
        }
    }
    auto matte = std::make_shared<bitmap::Bitmap>(width, height, 32, std::move(mask));
    matte->setNativeAlpha(true);
    return matte;
}

std::shared_ptr<bitmap::Bitmap> imageCreateFloodFillMatte(const bitmap::Bitmap& src) {
    const int width = src.width();
    const int height = src.height();
    const auto pixels = src.pixels();
    const auto paletteIndices = src.paletteIndices();
    const auto matte = imageResolveFloodFillMatte(pixels, paletteIndices, width, height);
    const auto transparent = matte
        ? imageComputeFloodFillTransparency(pixels, paletteIndices, width, height, *matte)
        : std::vector<bool>(static_cast<std::size_t>(width * height), false);

    std::vector<std::uint32_t> mask(static_cast<std::size_t>(width * height), 0x00FFFFFFU);
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        if (transparent[index]) {
            mask[index] = 0x00FFFFFFU;
        } else {
            auto alpha = static_cast<std::uint32_t>((pixels[index] >> 24) & 0xFFU);
            if (alpha == 0) {
                alpha = 0xFFU;
            }
            mask[index] = (alpha << 24) | 0x00FFFFFFU;
        }
    }

    auto matteBitmap = std::make_shared<bitmap::Bitmap>(width, height, 32, std::move(mask));
    matteBitmap->setNativeAlpha(true);
    return matteBitmap;
}

bool imageIsGrayscaleMaskSource(const std::vector<std::uint32_t>& pixels, const std::vector<bool>& transparent) {
    int opaquePixels = 0;
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        if (transparent[index] || ((pixels[index] >> 24) & 0xFFU) == 0) {
            continue;
        }
        const auto r = (pixels[index] >> 16) & 0xFFU;
        const auto g = (pixels[index] >> 8) & 0xFFU;
        const auto b = pixels[index] & 0xFFU;
        if (r != g || g != b) {
            return false;
        }
        ++opaquePixels;
    }
    return opaquePixels > 0 && opaquePixels * 4 <= static_cast<int>(pixels.size()) * 3;
}

bool imageIsWhiteBackedMaskSource(const std::vector<std::uint32_t>& pixels,
                                  const std::vector<bool>& transparent,
                                  const ImageFloodFillMatte& matte) {
    if (imageMaskAlphaFromPixel(0xFF000000U | static_cast<std::uint32_t>(matte.colorRgb & 0x00FFFFFF)) < 250) {
        return false;
    }

    bool hasTransparentMatte = false;
    bool hasOpaqueInk = false;
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        if (((pixels[index] >> 24) & 0xFFU) == 0) {
            continue;
        }
        if (transparent[index]) {
            hasTransparentMatte = true;
        } else {
            hasOpaqueInk = true;
        }
        if (hasTransparentMatte && hasOpaqueInk) {
            return true;
        }
    }
    return false;
}

bool imageIsMaskSource(const std::vector<std::uint32_t>& pixels,
                       const std::vector<bool>& transparent,
                       const ImageFloodFillMatte& matte) {
    return imageIsGrayscaleMaskSource(pixels, transparent) ||
           imageIsWhiteBackedMaskSource(pixels, transparent, matte);
}

std::shared_ptr<bitmap::Bitmap> imageCreateDirectMask(const bitmap::Bitmap& src,
                                                      const ImageFloodFillMatte& matte,
                                                      int alphaThreshold) {
    const int width = src.width();
    const int height = src.height();
    const int threshold = std::clamp(alphaThreshold, 0, 255);
    const auto pixels = src.pixels();
    const int matteLuma = imageMaskAlphaFromPixel(0xFF000000U | static_cast<std::uint32_t>(matte.colorRgb & 0x00FFFFFF));
    const bool lightMatte = matteLuma >= 128;

    std::vector<std::uint32_t> mask(static_cast<std::size_t>(width * height), 0xFFFFFFFFU);
    for (std::size_t index = 0; index < pixels.size(); ++index) {
        const auto pixel = pixels[index];
        if (((pixel >> 24) & 0xFFU) == 0) {
            mask[index] = 0xFFFFFFFFU;
            continue;
        }
        int maskLuma = imageMaskAlphaFromPixel(pixel);
        if (!lightMatte) {
            maskLuma = 255 - maskLuma;
        }
        const int opacity = 255 - maskLuma;
        if (opacity < threshold) {
            maskLuma = 255;
        }
        const auto luma = static_cast<std::uint32_t>(maskLuma & 0xFF);
        mask[index] = 0xFF000000U | (luma << 16) | (luma << 8) | luma;
    }
    return std::make_shared<bitmap::Bitmap>(width, height, src.bitDepth(), std::move(mask));
}

std::shared_ptr<bitmap::Bitmap> imageCreateMatte(const bitmap::Bitmap& src, int alphaThreshold) {
    if (src.width() <= 0 || src.height() <= 0) {
        return std::make_shared<bitmap::Bitmap>(1, 1, 32);
    }
    if (src.hasNativeMatteAlpha()) {
        return imageCreateAlphaMatte(src, alphaThreshold);
    }
    return imageCreateFloodFillMatte(src);
}

std::shared_ptr<bitmap::Bitmap> imageCreateMask(const bitmap::Bitmap& src, int alphaThreshold) {
    if (src.width() <= 0 || src.height() <= 0) {
        return std::make_shared<bitmap::Bitmap>(1, 1, 32);
    }
    if (src.hasNativeMatteAlpha()) {
        return imageCreateAlphaMatte(src, alphaThreshold);
    }

    const auto pixels = src.pixels();
    const auto paletteIndices = src.paletteIndices();
    const auto matte = imageResolveFloodFillMatte(pixels, paletteIndices, src.width(), src.height());
    if (matte.has_value()) {
        const auto transparent =
            imageComputeFloodFillTransparency(pixels, paletteIndices, src.width(), src.height(), *matte);
        if (imageIsMaskSource(pixels, transparent, *matte)) {
            return imageCreateDirectMask(src, *matte, alphaThreshold);
        }
    }
    return imageCreateFloodFillMatte(src);
}

Datum imageCopyPixels(bitmap::Bitmap& dest, const std::vector<Datum>& args) {
    if (args.size() < 3) return Datum::voidValue();
    const auto* srcRef = args[0].asImageRef();
    if (srcRef == nullptr || srcRef->bitmap == nullptr) return Datum::voidValue();
    const auto* destRect = args[1].asIntRect();
    const auto* srcRect = args[2].asIntRect();
    if (destRect == nullptr || srcRect == nullptr) return Datum::voidValue();

    const auto& src = *srcRef->bitmap;
    const int srcWidth = srcRect->right - srcRect->left;
    const int srcHeight = srcRect->bottom - srcRect->top;
    const int destWidth = destRect->right - destRect->left;
    const int destHeight = destRect->bottom - destRect->top;
    if (srcWidth <= 0 || srcHeight <= 0 || destWidth <= 0 || destHeight <= 0) {
        return Datum::voidValue();
    }

    if (dest.imagePalette() == nullptr && src.imagePalette() != nullptr) {
        dest.copyPaletteMetadataFrom(&src);
    }
    if (!dest.hasAnchorPoint() && src.hasAnchorPoint()) {
        dest.setAnchorPoint(destRect->left + src.anchorX() - srcRect->left,
                            destRect->top + src.anchorY() - srcRect->top);
    }

    const auto srcPaletteIndices = src.paletteIndices();
    const bool preservePaletteIndices =
        srcPaletteIndices.has_value() && srcPaletteIndices->size() == src.pixels().size();
    std::optional<std::vector<std::uint8_t>> destPaletteIndices;
    if (preservePaletteIndices) {
        destPaletteIndices = dest.paletteIndices().value_or(
            std::vector<std::uint8_t>(dest.pixels().size(), 0));
    } else {
        dest.clearPaletteIndices();
    }

    for (int dy = 0; dy < destHeight; ++dy) {
        const int sy = srcRect->top + (dy * srcHeight / destHeight);
        const int py = destRect->top + dy;
        if (sy < 0 || sy >= src.height() || py < 0 || py >= dest.height()) {
            continue;
        }
        for (int dx = 0; dx < destWidth; ++dx) {
            const int sx = srcRect->left + (dx * srcWidth / destWidth);
            const int px = destRect->left + dx;
            if (sx < 0 || sx >= src.width() || px < 0 || px >= dest.width()) {
                continue;
            }
            dest.setPixelPreservePaletteIndex(px, py, src.getPixel(sx, sy));
            if (destPaletteIndices.has_value() && srcPaletteIndices.has_value()) {
                const auto srcOffset = static_cast<std::size_t>(sy * src.width() + sx);
                const auto destOffset = static_cast<std::size_t>(py * dest.width() + px);
                if (srcOffset < srcPaletteIndices->size() && destOffset < destPaletteIndices->size()) {
                    (*destPaletteIndices)[destOffset] = (*srcPaletteIndices)[srcOffset];
                }
            }
        }
    }

    if (destPaletteIndices.has_value()) {
        dest.setPaletteIndices(std::move(*destPaletteIndices));
    }
    return Datum::voidValue();
}

Datum imageObjectMethod(const Datum::ImageRef& image, std::string_view methodName, const std::vector<Datum>& args) {
    if (image.bitmap == nullptr) {
        if (equalsIgnoreCase(methodName, "duplicate")) {
            return Datum::imageRef(nullptr);
        }
        if (equalsIgnoreCase(methodName, "getAt")) {
            if (args.empty()) return Datum::voidValue();
            const int index = toIntLikeJava(args[0]);
            return index == 1 || index == 2 ? Datum::of(0) : Datum::voidValue();
        }
        return Datum::voidValue();
    }

    auto& bmp = *image.bitmap;
    if (equalsIgnoreCase(methodName, "fill")) {
        if (imageFill(bmp, args)) {
            bmp.markScriptModified();
        }
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "setAlpha")) {
        bmp.markScriptModified();
        return imageSetAlpha(bmp, args);
    }
    if (equalsIgnoreCase(methodName, "draw")) {
        bmp.markScriptModified();
        return imageDraw(bmp, args);
    }
    if (equalsIgnoreCase(methodName, "createMatte")) {
        const int alphaThreshold = !args.empty() && !args[0].isVoid() ? toIntLikeJava(args[0]) : 0;
        return Datum::imageRef(imageCreateMatte(bmp, alphaThreshold));
    }
    if (equalsIgnoreCase(methodName, "createMask")) {
        const int alphaThreshold = !args.empty() && !args[0].isVoid() ? toIntLikeJava(args[0]) : 0;
        return Datum::imageRef(imageCreateMask(bmp, alphaThreshold));
    }
    if (equalsIgnoreCase(methodName, "copyPixels")) {
        bmp.markScriptModified();
        return imageCopyPixels(bmp, args);
    }
    if (equalsIgnoreCase(methodName, "duplicate")) {
        return Datum::imageRef(std::make_shared<bitmap::Bitmap>(bmp.copy()));
    }
    if (equalsIgnoreCase(methodName, "crop")) {
        if (args.empty()) return Datum::voidValue();
        const auto* rect = args[0].asIntRect();
        if (rect == nullptr) return Datum::voidValue();
        const int width = rect->right - rect->left;
        const int height = rect->bottom - rect->top;
        if (width <= 0 || height <= 0) return Datum::voidValue();
        return Datum::imageRef(std::make_shared<bitmap::Bitmap>(bmp.getRegion(rect->left, rect->top, width, height)));
    }
    if (equalsIgnoreCase(methodName, "trimWhiteSpace")) {
        const auto bounds = bmp.trimWhiteSpace();
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            auto empty = std::make_shared<bitmap::Bitmap>(1, 1, bmp.bitDepth());
            empty->fill(0xFFFFFFFFU);
            return Datum::imageRef(std::move(empty));
        }
        return Datum::imageRef(std::make_shared<bitmap::Bitmap>(
            bmp.getRegion(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top)));
    }
    if (equalsIgnoreCase(methodName, "getAt")) {
        if (args.empty()) return Datum::voidValue();
        const int index = toIntLikeJava(args[0]);
        if (index == 1) return Datum::of(bmp.width());
        if (index == 2) return Datum::of(bmp.height());
        return Datum::voidValue();
    }
    if (equalsIgnoreCase(methodName, "getPixel")) {
        if (args.size() < 2) return Datum::voidValue();
        const int x = toIntLikeJava(args[0]);
        const int y = toIntLikeJava(args[1]);
        if (x < 0 || x >= bmp.width() || y < 0 || y >= bmp.height()) {
            return Datum::voidValue();
        }
        if (const auto index = bmp.paletteIndex(x, y)) {
            return Datum::colorRef(*index, *index, *index);
        }
        const auto pixel = bmp.getPixel(x, y);
        return Datum::colorRef(static_cast<int>((pixel >> 16) & 0xFF),
                               static_cast<int>((pixel >> 8) & 0xFF),
                               static_cast<int>(pixel & 0xFF));
    }
    if (equalsIgnoreCase(methodName, "setPixel")) {
        if (args.size() >= 3) {
            const int x = toIntLikeJava(args[0]);
            const int y = toIntLikeJava(args[1]);
            if (x >= 0 && x < bmp.width() && y >= 0 && y < bmp.height()) {
                bmp.setPixel(x, y, imageColorArgb(args[2]));
                bmp.markScriptModified();
            }
        }
        return Datum::voidValue();
    }
    return Datum::voidValue();
}

Datum castLibObjectMethod(ExecutionContext& context,
                          const Datum::CastLibRef& castLib,
                          std::string_view methodName,
                          const std::vector<Datum>& args) {
    if ((!equalsIgnoreCase(methodName, "getProp") && !equalsIgnoreCase(methodName, "getPropRef")) || args.size() < 2 ||
        !equalsIgnoreCase(keyNameLikeJava(args[0]), "member")) {
        return Datum::voidValue();
    }

    auto* builtinContext = context.builtinContext();
    if (builtinContext == nullptr) {
        return Datum::voidValue();
    }

    const Datum& key = args[1];
    if (key.isInt() || key.isFloat()) {
        return builtinContext->castMemberResolver ? builtinContext->castMemberResolver(castLib.castLib, toIntLikeJava(key))
                                                  : Datum::voidValue();
    }
    return builtinContext->castMemberNameResolver ? builtinContext->castMemberNameResolver(castLib.castLib, toStringLikeJava(key))
                                                  : Datum::voidValue();
}

Datum castMemberObjectMethod(ExecutionContext& context,
                             const Datum::CastMemberRef& member,
                             std::string_view methodName,
                             const std::vector<Datum>& args) {
    auto* builtinContext = context.builtinContext();
    if (builtinContext == nullptr || !builtinContext->castMemberMethodHandler) {
        return Datum::voidValue();
    }
    return builtinContext->castMemberMethodHandler(member.castLib, member.memberNum(), std::string(methodName), args);
}

Datum getContextVar(ExecutionContext& context, id::VarType varType, const Datum& idDatum);
void setContextVar(ExecutionContext& context, id::VarType varType, const Datum& idDatum, Datum value);

Datum chunkRefObjectMethod(ExecutionContext& context,
                           const Datum::ChunkRef& chunkRef,
                           std::string_view methodName,
                           const std::vector<Datum>&) {
    if (!equalsIgnoreCase(methodName, "delete") || chunkRef.chunkType != StringChunkType::Char) {
        return Datum::voidValue();
    }

    const Datum current = getContextVar(context, chunkRef.varType, Datum::of(chunkRef.rawIndex));
    setContextVar(context,
                  chunkRef.varType,
                  Datum::of(chunkRef.rawIndex),
                  Datum::of(deleteCharChunkRefValue(toStringLikeJava(current), chunkRef.start, chunkRef.end)));
    return Datum::voidValue();
}

Datum scriptRefObjectMethod(ExecutionContext& context,
                            const Datum::ScriptRef& scriptRef,
                            std::string_view methodName,
                            const std::vector<Datum>& args) {
    if (!equalsIgnoreCase(methodName, "new")) {
        return Datum::voidValue();
    }

    std::vector<Datum> fullArgs;
    fullArgs.reserve(args.size() + 1);
    fullArgs.push_back(Datum::scriptRef(scriptRef.memberRef));
    fullArgs.insert(fullArgs.end(), args.begin(), args.end());
    if (const auto result = context.invokeBuiltinIfPresent("new", fullArgs)) {
        return *result;
    }
    return Datum::voidValue();
}

Datum varRefObjectMethod(ExecutionContext& context,
                         const Datum::VarRef& varRef,
                         std::string_view methodName,
                         const std::vector<Datum>& args) {
    Datum value = getContextVar(context, varRef.varType, Datum::of(varRef.rawIndex));
    if (equalsIgnoreCase(methodName, "getProp")) {
        if (args.size() < 2) {
            return Datum::of(std::string());
        }
        StringChunkType chunkType = StringChunkType::Char;
        try {
            chunkType = stringChunkTypeFromName(keyNameLikeJava(args[0]));
        } catch (const std::invalid_argument&) {
            chunkType = StringChunkType::Char;
        }
        const int start = toIntLikeJava(args[1]);
        const int end = args.size() >= 3 ? toIntLikeJava(args[2]) : start;
        return Datum::of(resolveChunkRange(toStringLikeJava(value), chunkType, start, end, currentItemDelimiter(context)));
    }
    if (equalsIgnoreCase(methodName, "getPropRef")) {
        if (args.size() < 2) {
            return Datum::voidValue();
        }
        StringChunkType chunkType = StringChunkType::Char;
        try {
            chunkType = stringChunkTypeFromName(keyNameLikeJava(args[0]));
        } catch (const std::invalid_argument&) {
            chunkType = StringChunkType::Char;
        }
        const int start = toIntLikeJava(args[1]);
        const int end = args.size() >= 3 ? toIntLikeJava(args[2]) : start;
        return Datum::chunkRef(varRef.varType, varRef.rawIndex, chunkType, start, end);
    }
    if (value.isList()) {
        return listObjectMethod(value.listValue(), methodName, args);
    }
    if (value.isPropList()) {
        return propListObjectMethod(value.propListValue(), methodName, args);
    }
    if (value.isString()) {
        return stringObjectMethod(context, value, methodName, args);
    }
    if (const auto* point = value.asIntPoint()) {
        return pointObjectMethod(*point, methodName, args);
    }
    if (const auto* rect = value.asIntRect()) {
        return rectObjectMethod(*rect, methodName, args);
    }
    if (value.type() == DatumType::ScriptInstanceRef) {
        return value.scriptInstanceValue().getProperty(std::string(methodName));
    }
    return Datum::voidValue();
}

Datum dispatchObjectMethod(ExecutionContext& context, Datum target, std::string_view methodName, const std::vector<Datum>& args) {
    if (const auto* varRef = target.asVarRef()) {
        return varRefObjectMethod(context, *varRef, methodName, args);
    }
    if (const auto* chunkRef = target.asChunkRef()) {
        return chunkRefObjectMethod(context, *chunkRef, methodName, args);
    }
    if (const auto* scriptRef = target.asScriptRef()) {
        return scriptRefObjectMethod(context, *scriptRef, methodName, args);
    }
    auto* builtinContext = context.builtinContext();
    if (const auto* timeout = target.asTimeoutRef()) {
        return builtinContext != nullptr ? builtin::TimeoutBuiltins::handleMethod(*builtinContext, *timeout, methodName, args)
                                         : Datum::voidValue();
    }
    if (const auto* soundChannel = target.asSoundChannel()) {
        return builtinContext != nullptr ? builtin::SoundBuiltins::handleMethod(*builtinContext, *soundChannel, methodName, args)
                                         : Datum::voidValue();
    }
    if (const auto* xtraInstance = target.asXtraInstance()) {
        return builtinContext != nullptr ? builtin::XtraBuiltins::callHandler(*builtinContext, *xtraInstance, methodName, args)
                                         : Datum::voidValue();
    }
    if (const auto* castLib = target.asCastLibRef()) {
        return castLibObjectMethod(context, *castLib, methodName, args);
    }
    if (target.isList()) {
        return listObjectMethod(target.listValue(), methodName, args);
    }
    if (target.isPropList()) {
        return propListObjectMethod(target.propListValue(), methodName, args);
    }
    if (target.isString()) {
        return stringObjectMethod(context, target, methodName, args);
    }
    if (const auto* point = target.asIntPoint()) {
        return pointObjectMethod(*point, methodName, args);
    }
    if (const auto* rect = target.asIntRect()) {
        return rectObjectMethod(*rect, methodName, args);
    }
    if (const auto* image = target.asImageRef()) {
        return imageObjectMethod(*image, methodName, args);
    }
    if (const auto* member = target.asCastMemberRef()) {
        return castMemberObjectMethod(context, *member, methodName, args);
    }
    if (target.type() == DatumType::ScriptInstanceRef) {
        return target.scriptInstanceValue().getProperty(std::string(methodName));
    }

    std::vector<Datum> fullArgs;
    fullArgs.reserve(args.size() + 1);
    fullArgs.push_back(target);
    fullArgs.insert(fullArgs.end(), args.begin(), args.end());
    if (const auto builtinResult = context.invokeBuiltinIfPresent(methodName, fullArgs)) {
        return *builtinResult;
    }
    return Datum::voidValue();
}

bool pushZero(ExecutionContext& context) {
    context.push(Datum::of(0));
    return true;
}

bool pushInt(ExecutionContext& context) {
    context.push(Datum::of(context.argument()));
    return true;
}

bool pushFloat(ExecutionContext& context) {
    context.push(Datum::of(std::bit_cast<float>(context.argument())));
    return true;
}

bool pushCons(ExecutionContext& context) {
    const auto& literals = context.literals();
    const int index = context.scaledArgument();
    if (index >= 0 && index < static_cast<int>(literals.size())) {
        context.push(literalToDatum(literals[static_cast<std::size_t>(index)]));
    } else {
        context.push(Datum::voidValue());
    }
    return true;
}

bool pushSymb(ExecutionContext& context) {
    context.push(Datum::symbol(context.resolveName(context.argument())));
    return true;
}

bool pushChunkVarRef(ExecutionContext& context) {
    const int rawIndex = toIntLikeJava(context.pop());
    context.push(Datum::varRef(id::varTypeFromCode(context.argument()), rawIndex));
    return true;
}

bool swap(ExecutionContext& context) {
    context.swap();
    return true;
}

bool pop(ExecutionContext& context) {
    const int count = context.argument();
    if (count <= 1) {
        (void)context.pop();
    } else {
        for (int index = 0; index < count; ++index) {
            (void)context.pop();
        }
    }
    return true;
}

bool peek(ExecutionContext& context) {
    context.push(context.peek(context.argument()));
    return true;
}

Datum fallbackScriptInstance(const Datum& scriptArg) {
    if (const auto* scriptRef = scriptArg.asScriptRef()) {
        return Datum::scriptInstance("script", scriptRef->memberRef);
    }
    if (const auto* memberRef = scriptArg.asCastMemberRef()) {
        return Datum::scriptInstance("script", *memberRef);
    }
    if (const auto* symbol = scriptArg.asSymbol()) {
        return Datum::scriptInstance(symbol->name);
    }
    if (scriptArg.isString()) {
        return Datum::scriptInstance(scriptArg.stringValue());
    }
    return Datum::scriptInstance(toStringLikeJava(scriptArg));
}

bool newObj(ExecutionContext& context) {
    const std::string objectType = context.resolveName(context.argument());
    if (!equalsIgnoreCase(objectType, "script")) {
        context.push(Datum::voidValue());
        return true;
    }

    const Datum argListDatum = context.pop();
    const std::vector<Datum> args = argListItems(argListDatum);
    if (args.empty()) {
        context.push(Datum::voidValue());
        return true;
    }

    if (const auto result = context.invokeBuiltinIfPresent("new", args); result && !result->isVoid()) {
        context.push(*result);
        return true;
    }

    context.push(fallbackScriptInstance(args.front()));
    return true;
}

bool ret(ExecutionContext& context) {
    context.setReturnValue(context.pop());
    return true;
}

bool retFactory(ExecutionContext& context) {
    context.setReturnValue(Datum::voidValue());
    return true;
}

bool jmp(ExecutionContext& context) {
    context.jumpTo(context.instructionOffset() + context.argument());
    return false;
}

bool jmpIfZero(ExecutionContext& context) {
    if (!truthy(context.pop())) {
        context.jumpTo(context.instructionOffset() + context.argument());
        return false;
    }
    return true;
}

bool endRepeat(ExecutionContext& context) {
    context.jumpTo(context.instructionOffset() - context.argument());
    return false;
}

bool add(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();

    if (const auto* point = a.asIntPoint()) {
        int dx = toIntLikeJava(b);
        int dy = dx;
        if (const auto* other = b.asIntPoint()) {
            dx = other->x;
            dy = other->y;
        } else if (b.isList()) {
            const auto delta = listAsPointDelta(b.listValue());
            dx = delta.x;
            dy = delta.y;
        }
        context.push(Datum::intPoint(point->x + dx, point->y + dy));
        return true;
    }
    if (const auto* point = b.asIntPoint(); point != nullptr && a.isList()) {
        const auto delta = listAsPointDelta(a.listValue());
        context.push(Datum::intPoint(delta.x + point->x, delta.y + point->y));
        return true;
    }
    if (const auto* rect = a.asIntRect()) {
        int dl = toIntLikeJava(b);
        int dt = dl;
        int dr = dl;
        int db = dl;
        if (const auto* other = b.asIntRect()) {
            dl = other->left;
            dt = other->top;
            dr = other->right;
            db = other->bottom;
        } else if (b.isList()) {
            const auto delta = listAsRectDelta(b.listValue());
            dl = delta.left;
            dt = delta.top;
            dr = delta.right;
            db = delta.bottom;
        }
        context.push(Datum::intRect(rect->left + dl, rect->top + dt, rect->right + dr, rect->bottom + db));
        return true;
    }
    if (a.isList() && b.isList()) {
        const auto& lhs = a.listValue().items();
        const auto& rhs = b.listValue().items();
        std::vector<Datum> result;
        const auto count = std::min(lhs.size(), rhs.size());
        result.reserve(count);
        for (std::size_t index = 0; index < count; ++index) {
            const double value = toDoubleLikeJava(lhs[index]) + toDoubleLikeJava(rhs[index]);
            result.push_back(numericResult(lhs[index], rhs[index], value));
        }
        context.push(Datum::list(std::move(result)));
        return true;
    }
    if (const auto* lhs = a.asColorRef()) {
        if (const auto* rhs = b.asColorRef()) {
            context.push(Datum::colorRef(std::min(255, lhs->r + rhs->r),
                                         std::min(255, lhs->g + rhs->g),
                                         std::min(255, lhs->b + rhs->b)));
            return true;
        }
    }

    context.push(numericResult(a, b, toDoubleLikeJava(a) + toDoubleLikeJava(b)));
    return true;
}

bool sub(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();

    if (const auto* point = a.asIntPoint()) {
        int dx = toIntLikeJava(b);
        int dy = dx;
        if (const auto* other = b.asIntPoint()) {
            dx = other->x;
            dy = other->y;
        } else if (b.isList()) {
            const auto delta = listAsPointDelta(b.listValue());
            dx = delta.x;
            dy = delta.y;
        }
        context.push(Datum::intPoint(point->x - dx, point->y - dy));
        return true;
    }
    if (const auto* rect = a.asIntRect()) {
        int dl = toIntLikeJava(b);
        int dt = dl;
        int dr = dl;
        int db = dl;
        if (const auto* other = b.asIntRect()) {
            dl = other->left;
            dt = other->top;
            dr = other->right;
            db = other->bottom;
        } else if (b.isList()) {
            const auto delta = listAsRectDelta(b.listValue());
            dl = delta.left;
            dt = delta.top;
            dr = delta.right;
            db = delta.bottom;
        }
        context.push(Datum::intRect(rect->left - dl, rect->top - dt, rect->right - dr, rect->bottom - db));
        return true;
    }
    if (a.isList() && b.isList()) {
        const auto& lhs = a.listValue().items();
        const auto& rhs = b.listValue().items();
        std::vector<Datum> result;
        const auto count = std::min(lhs.size(), rhs.size());
        result.reserve(count);
        for (std::size_t index = 0; index < count; ++index) {
            const double value = toDoubleLikeJava(lhs[index]) - toDoubleLikeJava(rhs[index]);
            result.push_back(numericResult(lhs[index], rhs[index], value));
        }
        context.push(Datum::list(std::move(result)));
        return true;
    }
    if (const auto* lhs = a.asColorRef()) {
        if (const auto* rhs = b.asColorRef()) {
            context.push(Datum::colorRef(std::max(0, lhs->r - rhs->r),
                                         std::max(0, lhs->g - rhs->g),
                                         std::max(0, lhs->b - rhs->b)));
            return true;
        }
    }

    context.push(numericResult(a, b, toDoubleLikeJava(a) - toDoubleLikeJava(b)));
    return true;
}

bool mul(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();

    if (const auto* point = a.asIntPoint()) {
        const double scalar = toDoubleLikeJava(b);
        context.push(Datum::intPoint(static_cast<int>(point->x * scalar), static_cast<int>(point->y * scalar)));
        return true;
    }
    if (const auto* point = b.asIntPoint()) {
        const double scalar = toDoubleLikeJava(a);
        context.push(Datum::intPoint(static_cast<int>(point->x * scalar), static_cast<int>(point->y * scalar)));
        return true;
    }
    if (const auto* rect = a.asIntRect()) {
        const double scalar = toDoubleLikeJava(b);
        context.push(Datum::intRect(static_cast<int>(rect->left * scalar),
                                    static_cast<int>(rect->top * scalar),
                                    static_cast<int>(rect->right * scalar),
                                    static_cast<int>(rect->bottom * scalar)));
        return true;
    }
    if (const auto* rect = b.asIntRect()) {
        const double scalar = toDoubleLikeJava(a);
        context.push(Datum::intRect(static_cast<int>(rect->left * scalar),
                                    static_cast<int>(rect->top * scalar),
                                    static_cast<int>(rect->right * scalar),
                                    static_cast<int>(rect->bottom * scalar)));
        return true;
    }
    if (a.isList() && !b.isList()) {
        context.push(scaleList(a.listValue(), toDoubleLikeJava(b), isFloatLike(b)));
        return true;
    }
    if (b.isList() && !a.isList()) {
        context.push(scaleList(b.listValue(), toDoubleLikeJava(a), isFloatLike(a)));
        return true;
    }

    context.push(numericResult(a, b, toDoubleLikeJava(a) * toDoubleLikeJava(b)));
    return true;
}

bool div(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    const double divisor = toDoubleLikeJava(b);
    if (divisor == 0.0) {
        throw context.error("Division by zero");
    }

    if (const auto* point = a.asIntPoint()) {
        context.push(Datum::intPoint(static_cast<int>(point->x / divisor), static_cast<int>(point->y / divisor)));
        return true;
    }
    if (const auto* rect = a.asIntRect()) {
        context.push(Datum::intRect(static_cast<int>(rect->left / divisor),
                                    static_cast<int>(rect->top / divisor),
                                    static_cast<int>(rect->right / divisor),
                                    static_cast<int>(rect->bottom / divisor)));
        return true;
    }
    if (a.isList()) {
        context.push(divideList(a.listValue(), b));
        return true;
    }

    context.push(numericResult(a, b, toDoubleLikeJava(a) / divisor));
    return true;
}

bool mod(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    const int divisor = toIntLikeJava(b);
    if (divisor == 0) {
        throw context.error("Modulo by zero");
    }
    context.push(Datum::of(toIntLikeJava(a) % divisor));
    return true;
}

bool inv(ExecutionContext& context) {
    const Datum value = context.pop();
    if (const auto* intValue = value.asInt()) {
        context.push(Datum::of(-intValue->value));
    } else if (const auto* floatValue = value.asFloat()) {
        context.push(Datum::of(-floatValue->value));
    } else if (const auto* point = value.asIntPoint()) {
        context.push(Datum::intPoint(-point->x, -point->y));
    } else if (const auto* rect = value.asIntRect()) {
        context.push(Datum::intRect(-rect->left, -rect->top, -rect->right, -rect->bottom));
    } else {
        context.push(Datum::of(-toIntLikeJava(value)));
    }
    return true;
}

bool lt(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(toDoubleLikeJava(a) < toDoubleLikeJava(b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool ltEq(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(toDoubleLikeJava(a) <= toDoubleLikeJava(b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool gt(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(toDoubleLikeJava(a) > toDoubleLikeJava(b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool gtEq(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(toDoubleLikeJava(a) >= toDoubleLikeJava(b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool eq(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(lingoEquals(a, b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool notEq(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(!lingoEquals(a, b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool logicalAnd(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(truthy(a) && truthy(b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool logicalOr(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    context.push(truthy(a) || truthy(b) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool logicalNot(ExecutionContext& context) {
    context.push(truthy(context.pop()) ? Datum::FALSE : Datum::TRUE);
    return true;
}

bool joinStr(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    const std::string aString = toStringLikeJava(a);
    const std::string bString = toStringLikeJava(b);
    if (aString.empty()) {
        context.push(b.asString() != nullptr ? b : Datum::of(bString));
        return true;
    }
    if (bString.empty()) {
        context.push(a.asString() != nullptr ? a : Datum::of(aString));
        return true;
    }
    context.push(Datum::of(aString + bString));
    return true;
}

bool joinPadStr(ExecutionContext& context) {
    const Datum b = context.pop();
    const Datum a = context.pop();
    const std::string aString = toStringLikeJava(a);
    const std::string bString = toStringLikeJava(b);
    if (aString.empty()) {
        context.push(b.asString() != nullptr ? b : Datum::of(bString));
        return true;
    }
    if (bString.empty()) {
        context.push(a.asString() != nullptr ? a : Datum::of(aString));
        return true;
    }
    context.push(Datum::of(aString + " " + bString));
    return true;
}

bool containsStr(ExecutionContext& context) {
    const Datum needle = context.pop();
    const Datum haystack = context.pop();
    context.push(containsIgnoreCase(toStringLikeJava(haystack), toStringLikeJava(needle)) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool contains0Str(ExecutionContext& context) {
    const Datum needle = context.pop();
    const Datum haystack = context.pop();
    if (haystack.isVoid()) {
        context.push(Datum::FALSE);
        return true;
    }
    context.push(startsWithIgnoreCase(toStringLikeJava(haystack), toStringLikeJava(needle)) ? Datum::TRUE : Datum::FALSE);
    return true;
}

bool getChunk(ExecutionContext& context) {
    const Datum stringDatum = context.pop();
    const int lastLine = toIntLikeJava(context.pop());
    const int firstLine = toIntLikeJava(context.pop());
    const int lastItem = toIntLikeJava(context.pop());
    const int firstItem = toIntLikeJava(context.pop());
    const int lastWord = toIntLikeJava(context.pop());
    const int firstWord = toIntLikeJava(context.pop());
    const int lastChar = toIntLikeJava(context.pop());
    const int firstChar = toIntLikeJava(context.pop());

    const std::string value = toStringLikeJava(stringDatum);
    if (firstChar != 0 && lastChar == 0 &&
        firstWord == 0 && lastWord == 0 &&
        firstItem == 0 && lastItem == 0 &&
        firstLine == 0 && lastLine == 0) {
        const int resolvedChar = firstChar < 0 ? static_cast<int>(value.size()) : firstChar;
        const int index = resolvedChar - 1;
        if (index >= 0 && index < static_cast<int>(value.size())) {
            context.push(Datum::of(value.substr(static_cast<std::size_t>(index), 1)));
        } else {
            context.push(Datum::of(std::string()));
        }
        return true;
    }

    if (firstChar != 0 && lastChar != 0 &&
        firstWord == 0 && lastWord == 0 &&
        firstItem == 0 && lastItem == 0 &&
        firstLine == 0 && lastLine == 0) {
        const int resolvedFirst = firstChar < 0 ? static_cast<int>(value.size()) : firstChar;
        const int resolvedLast = lastChar < 0 ? static_cast<int>(value.size()) : lastChar;
        const int start = resolvedFirst - 1;
        const int end = std::min(resolvedLast, static_cast<int>(value.size()));
        if (start >= 0 && start < static_cast<int>(value.size()) && end > start) {
            context.push(Datum::of(value.substr(static_cast<std::size_t>(start),
                                                static_cast<std::size_t>(end - start))));
        } else {
            context.push(Datum::of(std::string()));
        }
        return true;
    }

    const char itemDelimiter = currentItemDelimiter(context);
    std::string result = value;
    if (firstLine != 0 || lastLine != 0) {
        result = resolveChunkRange(result, StringChunkType::Line, firstLine, lastLine, itemDelimiter);
    }
    if (firstItem != 0 || lastItem != 0) {
        result = resolveChunkRange(result, StringChunkType::Item, firstItem, lastItem, itemDelimiter);
    }
    if (firstWord != 0 || lastWord != 0) {
        result = resolveChunkRange(result, StringChunkType::Word, firstWord, lastWord, itemDelimiter);
    }
    if (firstChar != 0 || lastChar != 0) {
        result = resolveChunkRange(result, StringChunkType::Char, firstChar, lastChar, itemDelimiter);
    }
    context.push(Datum::of(std::move(result)));
    return true;
}

Datum getContextVar(ExecutionContext& context, id::VarType varType, const Datum& idDatum) {
    switch (varType) {
        case id::VarType::LOCAL:
            return context.getLocal(toIntLikeJava(idDatum) / context.variableMultiplier());
        case id::VarType::PARAM:
            return context.getParam(toIntLikeJava(idDatum) / context.variableMultiplier());
        case id::VarType::GLOBAL:
        case id::VarType::GLOBAL2:
            return context.getGlobal(context.resolveName(toIntLikeJava(idDatum)));
        case id::VarType::PROPERTY: {
            const Datum receiver = context.scope().receiver();
            if (receiver.type() == DatumType::ScriptInstanceRef) {
                return receiver.scriptInstanceValue().getProperty(context.resolveName(toIntLikeJava(idDatum)));
            }
            return Datum::voidValue();
        }
        case id::VarType::FIELD:
            return Datum::of(std::string());
    }
    return Datum::voidValue();
}

void setContextVar(ExecutionContext& context, id::VarType varType, const Datum& idDatum, Datum value) {
    switch (varType) {
        case id::VarType::LOCAL:
            context.setLocal(toIntLikeJava(idDatum) / context.variableMultiplier(), std::move(value));
            return;
        case id::VarType::PARAM:
            context.setParam(toIntLikeJava(idDatum) / context.variableMultiplier(), std::move(value));
            return;
        case id::VarType::GLOBAL:
        case id::VarType::GLOBAL2:
            context.setGlobal(context.resolveName(toIntLikeJava(idDatum)), std::move(value));
            return;
        case id::VarType::PROPERTY: {
            Datum receiver = context.scope().receiver();
            if (receiver.type() == DatumType::ScriptInstanceRef) {
                receiver.scriptInstanceValue().setProperty(context.resolveName(toIntLikeJava(idDatum)), std::move(value));
            }
            return;
        }
        case id::VarType::FIELD:
            return;
    }
}

bool put(ExecutionContext& context) {
    const int encoded = context.argument();
    const int putType = (encoded >> 4) & 0xF;
    const auto varType = id::varTypeFromCode(encoded & 0xF);

    if (varType == id::VarType::FIELD) {
        (void)context.pop();
    }
    const Datum idDatum = context.pop();
    Datum value = context.pop();

    switch (putType) {
        case 1:
            setContextVar(context, varType, idDatum, std::move(value));
            break;
        case 3: {
            const Datum current = getContextVar(context, varType, idDatum);
            const std::string valueString = toStringLikeJava(value);
            const std::string currentString = toStringLikeJava(current);
            const std::string newValue = currentString.empty()
                                             ? valueString
                                             : valueString.empty() ? currentString : valueString + currentString;
            setContextVar(context, varType, idDatum, Datum::of(newValue));
            break;
        }
        case 2: {
            const Datum current = getContextVar(context, varType, idDatum);
            const std::string currentString = toStringLikeJava(current);
            const std::string valueString = toStringLikeJava(value);
            const std::string newValue = currentString.empty()
                                             ? valueString
                                             : valueString.empty() ? currentString : currentString + valueString;
            setContextVar(context, varType, idDatum, Datum::of(newValue));
            break;
        }
        default:
            break;
    }
    return true;
}

bool deleteChunk(ExecutionContext& context) {
    const auto varType = id::varTypeFromCode(context.argument());
    if (varType == id::VarType::FIELD) {
        (void)context.pop();
    }
    const Datum idDatum = context.pop();

    const int lastLine = toIntLikeJava(context.pop());
    const int firstLine = toIntLikeJava(context.pop());
    const int lastItem = toIntLikeJava(context.pop());
    const int firstItem = toIntLikeJava(context.pop());
    const int lastWord = toIntLikeJava(context.pop());
    const int firstWord = toIntLikeJava(context.pop());
    const int lastChar = toIntLikeJava(context.pop());
    const int firstChar = toIntLikeJava(context.pop());

    ChunkSpec chunk = chooseSingleChunkSpec(firstChar,
                                            lastChar,
                                            firstWord,
                                            lastWord,
                                            firstItem,
                                            lastItem,
                                            firstLine,
                                            lastLine);

    const Datum current = getContextVar(context, varType, idDatum);
    const std::string currentString = toStringLikeJava(current);
    const char itemDelimiter = currentItemDelimiter(context);

    if (chunk.type == StringChunkType::Char) {
        if (chunk.first < 0) {
            chunk.first = static_cast<int>(currentString.size());
        }
        if (chunk.last < 0) {
            chunk.last = static_cast<int>(currentString.size());
        }
    } else if (chunk.first < 0 || chunk.last < 0) {
        const int count = countChunks(currentString, chunk.type, itemDelimiter);
        if (chunk.first < 0) {
            chunk.first = count;
        }
        if (chunk.last < 0) {
            chunk.last = count;
        }
    }

    setContextVar(context,
                  varType,
                  idDatum,
                  Datum::of(deleteChunkValue(currentString, chunk.type, chunk.first, chunk.last, itemDelimiter)));
    return true;
}

bool putChunk(ExecutionContext& context) {
    const int encoded = context.argument();
    const int putType = (encoded >> 4) & 0xF;
    const auto varType = id::varTypeFromCode(encoded & 0xF);

    if (varType == id::VarType::FIELD) {
        (void)context.pop();
    }
    const Datum idDatum = context.pop();
    const Datum value = context.pop();

    const int lastLine = toIntLikeJava(context.pop());
    const int firstLine = toIntLikeJava(context.pop());
    const int lastItem = toIntLikeJava(context.pop());
    const int firstItem = toIntLikeJava(context.pop());
    const int lastWord = toIntLikeJava(context.pop());
    const int firstWord = toIntLikeJava(context.pop());
    const int lastChar = toIntLikeJava(context.pop());
    const int firstChar = toIntLikeJava(context.pop());

    ChunkSpec chunk = chooseSingleChunkSpec(firstChar,
                                            lastChar,
                                            firstWord,
                                            lastWord,
                                            firstItem,
                                            lastItem,
                                            firstLine,
                                            lastLine);

    const std::string currentString = toStringLikeJava(getContextVar(context, varType, idDatum));
    const std::string valueString = toStringLikeJava(value);
    const char itemDelimiter = currentItemDelimiter(context);
    std::string newString = currentString;

    if (chunk.type == StringChunkType::Char) {
        switch (putType) {
            case 1:
                if (const auto range = charChunkReplaceRange(currentString, chunk.first, chunk.last)) {
                    newString = std::string(currentString.substr(0, static_cast<std::size_t>(range->first))) +
                                valueString +
                                std::string(currentString.substr(static_cast<std::size_t>(range->second)));
                }
                break;
            case 3:
                newString = putBeforeChunkValue(currentString, chunk.type, chunk.first, valueString, itemDelimiter);
                break;
            case 2:
                newString = putAfterChunkValue(currentString,
                                               chunk.type,
                                               chunk.last == 0 ? chunk.first : chunk.last,
                                               valueString,
                                               itemDelimiter);
                break;
            default:
                break;
        }
        setContextVar(context, varType, idDatum, Datum::of(std::move(newString)));
        return true;
    }

    if (chunk.first < 0 || chunk.last < 0) {
        const int count = countChunks(currentString, chunk.type, itemDelimiter);
        if (chunk.first < 0) {
            chunk.first = count;
        }
        if (chunk.last < 0) {
            chunk.last = count;
        }
    }

    switch (putType) {
        case 1:
            newString = putIntoChunkValue(currentString, chunk.type, chunk.first, chunk.last, valueString, itemDelimiter);
            break;
        case 3:
            newString = putBeforeChunkValue(currentString, chunk.type, chunk.first, valueString, itemDelimiter);
            break;
        case 2:
            newString = putAfterChunkValue(currentString,
                                           chunk.type,
                                           chunk.last == 0 ? chunk.first : chunk.last,
                                           valueString,
                                           itemDelimiter);
            break;
        default:
            break;
    }

    setContextVar(context, varType, idDatum, Datum::of(std::move(newString)));
    return true;
}

bool getLocal(ExecutionContext& context) {
    context.push(context.getLocal(context.scaledArgument()));
    return true;
}

bool setLocal(ExecutionContext& context) {
    context.setLocal(context.scaledArgument(), context.pop());
    return true;
}

bool getParam(ExecutionContext& context) {
    context.push(context.getParam(context.scaledArgument()));
    return true;
}

bool setParam(ExecutionContext& context) {
    context.setParam(context.scaledArgument(), context.pop());
    return true;
}

bool getGlobal(ExecutionContext& context) {
    const std::string name = context.resolveName(context.argument());
    context.push(context.getGlobal(name));
    return true;
}

bool setGlobal(ExecutionContext& context) {
    const std::string name = context.resolveName(context.argument());
    context.setGlobal(name, context.pop());
    return true;
}

bool pushList(ExecutionContext& context) {
    const Datum argListDatum = context.pop();
    std::vector<Datum> items = argListItems(argListDatum);
    if (items.empty() && argListDatum.type() != DatumType::ArgList && argListDatum.type() != DatumType::ArgListNoRet &&
        !argListDatum.isVoid()) {
        items.push_back(argListDatum);
    }
    context.push(Datum::list(std::move(items)));
    return true;
}

bool pushPropList(ExecutionContext& context) {
    const Datum argListDatum = context.pop();
    const std::vector<Datum> items = argListItems(argListDatum);
    Datum propList = Datum::propList();
    auto& properties = propList.propListValue().properties();
    for (std::size_t index = 0; index + 1 < items.size(); index += 2) {
        properties.emplace_back(items[index], items[index + 1]);
    }
    context.push(std::move(propList));
    return true;
}

bool pushArgList(ExecutionContext& context) {
    context.push(Datum::argList(context.popArgs(context.argument())));
    return true;
}

bool pushArgListNoRet(ExecutionContext& context) {
    context.push(Datum::argListNoRet(context.popArgs(context.argument())));
    return true;
}

bool getProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    const Datum receiver = context.scope().receiver();
    if (receiver.type() == DatumType::ScriptInstanceRef) {
        context.push(receiver.scriptInstanceValue().getProperty(propName));
    } else {
        context.push(Datum::voidValue());
    }
    return true;
}

bool setProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    Datum receiver = context.scope().receiver();
    Datum value = context.pop();
    if (receiver.type() == DatumType::ScriptInstanceRef) {
        receiver.scriptInstanceValue().setProperty(propName, std::move(value));
    }
    return true;
}

bool getMovieProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    if (auto* builtinContext = context.builtinContext(); builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
        context.push(builtinContext->movieProperties->getMovieProp(propName));
    } else {
        context.push(builtinConstant(propName).value_or(Datum::voidValue()));
    }
    return true;
}

bool setMovieProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    const Datum value = context.pop();
    if (auto* builtinContext = context.builtinContext(); builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
        (void)builtinContext->movieProperties->setMovieProp(propName, value);
    }
    return true;
}

bool getObjProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    const Datum object = context.pop();
    context.push(getObjectProperty(context, object, propName));
    return true;
}

bool getChainedProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    const Datum object = context.pop();
    context.push(getObjectProperty(context, object, propName));
    return true;
}

bool getTopLevelProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    if (equalsIgnoreCase(propName, "_player")) {
        context.push(Datum::playerRef());
    } else if (equalsIgnoreCase(propName, "_movie")) {
        context.push(Datum::movieRef());
    } else {
        context.push(Datum::voidValue());
    }
    return true;
}

bool setObjProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    Datum value = context.pop();
    Datum object = context.pop();
    setObjectProperty(context, object, propName, std::move(value));
    return true;
}

bool getLegacyProperty(ExecutionContext& context) {
    const int propertyId = toIntLikeJava(context.pop());
    const int propertyType = context.argument();
    const char itemDelimiter = currentItemDelimiter(context);
    auto* builtinContext = context.builtinContext();

    if (propertyType == 0x00) {
        if (propertyId <= 0x0B) {
            if (const auto propName = moviePropNameById(propertyId);
                propName && builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
                context.push(builtinContext->movieProperties->getMovieProp(*propName));
            } else {
                context.push(Datum::voidValue());
            }
            return true;
        }
        if (const auto chunkType = stringChunkTypeByCode(propertyId - 0x0B)) {
            const std::string value = toStringLikeJava(context.pop());
            context.push(Datum::of(getLastChunkValue(value, *chunkType, itemDelimiter)));
            return true;
        }
        context.push(Datum::voidValue());
        return true;
    }

    if (propertyType == 0x01) {
        const std::string value = toStringLikeJava(context.pop());
        if (const auto chunkType = stringChunkTypeByCode(propertyId)) {
            context.push(Datum::of(countChunks(value, *chunkType, itemDelimiter)));
        } else {
            context.push(Datum::voidValue());
        }
        return true;
    }

    if (propertyType == 0x06) {
        const int spriteNum = toIntLikeJava(context.pop());
        if (const auto propName = spritePropNameById(propertyId);
            propName && builtinContext != nullptr && builtinContext->spriteProperties != nullptr) {
            context.push(builtinContext->spriteProperties->getSpriteProp(spriteNum, *propName));
        } else {
            context.push(Datum::voidValue());
        }
        return true;
    }

    if (propertyType == 0x07 || propertyType == 0x09) {
        if (const auto propName = animationPropNameById(propertyId);
            propName && builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
            context.push(builtinContext->movieProperties->getMovieProp(*propName));
        } else {
            context.push(Datum::voidValue());
        }
        return true;
    }

    if (propertyType == 0x08) {
        if (propertyId == 0x02) {
            (void)context.pop();
            context.push(Datum::of(0));
            return true;
        }
        if (const auto propName = animation2PropNameById(propertyId);
            propName && builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
            context.push(builtinContext->movieProperties->getMovieProp(*propName));
        } else {
            context.push(Datum::voidValue());
        }
        return true;
    }

    if (propertyType == 0x0B) {
        const int channelNum = toIntLikeJava(context.pop());
        if (const auto propName = soundPropNameById(propertyId);
            propName && channelNum >= 1 && channelNum <= 8 && builtinContext != nullptr) {
            context.push(builtin::SoundBuiltins::getProperty(*builtinContext, Datum::SoundChannel{channelNum}, *propName));
        } else {
            context.push(Datum::voidValue());
        }
        return true;
    }

    context.push(Datum::voidValue());
    return true;
}

bool getField(ExecutionContext& context) {
    const Datum castIdDatum = context.pop();
    const Datum fieldNameOrNum = context.pop();
    const int castId = toIntLikeJava(castIdDatum);

    std::vector<Datum> args;
    if (fieldNameOrNum.asString() != nullptr || fieldNameOrNum.asInt() != nullptr) {
        args.push_back(fieldNameOrNum);
    } else {
        args.push_back(Datum::of(toStringLikeJava(fieldNameOrNum)));
    }
    if (castId > 0) {
        args.push_back(Datum::castLibRef(id::CastLibId(castId)));
    }

    if (const auto result = context.invokeBuiltinIfPresent("field", args)) {
        context.push(*result);
    } else {
        context.push(Datum::of(std::string()));
    }
    return true;
}

bool setLegacyProperty(ExecutionContext& context) {
    const int propertyId = toIntLikeJava(context.pop());
    const Datum value = context.pop();
    const int propertyType = context.argument();
    auto* builtinContext = context.builtinContext();

    if (propertyType == 0x00) {
        if (const auto propName = moviePropNameById(propertyId);
            propertyId <= 0x0B && propName && builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
            (void)builtinContext->movieProperties->setMovieProp(*propName, value);
        }
        return true;
    }

    if (propertyType == 0x04) {
        const int channelNum = toIntLikeJava(context.pop());
        if (const auto propName = soundPropNameById(propertyId);
            propName && channelNum >= 1 && channelNum <= 8 && builtinContext != nullptr) {
            (void)builtin::SoundBuiltins::setProperty(*builtinContext, Datum::SoundChannel{channelNum}, *propName, value);
        }
        return true;
    }

    if (propertyType == 0x06) {
        const int spriteNum = toIntLikeJava(context.pop());
        if (const auto propName = spritePropNameById(propertyId);
            propName && builtinContext != nullptr && builtinContext->spriteProperties != nullptr) {
            (void)builtinContext->spriteProperties->setSpriteProp(spriteNum, *propName, value);
        }
        return true;
    }

    if (propertyType == 0x07) {
        if (const auto propName = animationPropNameById(propertyId);
            propName && builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
            (void)builtinContext->movieProperties->setMovieProp(*propName, value);
        }
        return true;
    }

    return true;
}

bool theBuiltin(ExecutionContext& context) {
    (void)context.pop();
    const std::string propName = context.resolveName(context.argument());
    if (equalsIgnoreCase(propName, "paramcount")) {
        context.push(Datum::of(static_cast<int>(context.scope().arguments().size())));
    } else if (equalsIgnoreCase(propName, "result")) {
        context.push(context.scope().returnValue());
    } else if (auto* builtinContext = context.builtinContext(); builtinContext != nullptr && builtinContext->movieProperties != nullptr) {
        Datum value = builtinContext->movieProperties->getMovieProp(propName);
        if (value.isVoid()) {
            value = builtinConstant(propName).value_or(Datum::voidValue());
        }
        context.push(std::move(value));
    } else {
        context.push(builtinConstant(propName).value_or(Datum::voidValue()));
    }
    return true;
}

bool localCall(ExecutionContext& context) {
    const auto targetHandler = context.findLocalHandler(context.argument());
    const auto* script = context.scope().script();
    if (!targetHandler || script == nullptr) {
        return true;
    }

    const Datum argListDatum = context.pop();
    const bool noReturn = isNoReturnArgList(argListDatum);
    std::vector<Datum> args = argListItems(argListDatum);
    Datum receiver = context.scope().receiver();
    if (!receiver.isVoid() && !receiver.isNull() && !args.empty() && args.front() == receiver) {
        bool handlerDeclaresMe = false;
        if (!targetHandler->argNameIds.empty()) {
            handlerDeclaresMe = equalsIgnoreCase(context.resolveName(targetHandler->argNameIds.front()), "me");
        }
        if (handlerDeclaresMe) {
            receiver = Datum::voidValue();
        } else {
            receiver = args.front();
            args.erase(args.begin());
        }
    }

    const Datum result = safeExecuteHandler(context, *script, *targetHandler, args, receiver);
    if (!noReturn) {
        context.push(result);
    }
    return true;
}

bool extCall(ExecutionContext& context) {
    const std::string handlerName = context.resolveName(context.argument());
    const Datum argListDatum = context.pop();
    const bool noReturn = isNoReturnArgList(argListDatum);
    std::vector<Datum> args = argListItems(argListDatum);

    Datum result = Datum::voidValue();
    if (const auto handler = context.findHandler(handlerName)) {
        result = safeExecuteHandler(context, *handler->script, handler->handler, args, Datum::voidValue());
    } else if (const auto builtinResult = context.invokeBuiltinIfPresent(handlerName, args)) {
        result = *builtinResult;
    } else if (!args.empty()) {
        Datum target = args.front();
        args.erase(args.begin());
        result = dispatchObjectMethod(context, std::move(target), handlerName, args);
    } else if (args.empty()) {
        result = builtinConstant(handlerName).value_or(Datum::voidValue());
    }

    if (!noReturn) {
        context.push(result);
    }
    return true;
}

bool objCall(ExecutionContext& context) {
    const std::string methodName = context.resolveName(context.argument());
    const Datum argListDatum = context.pop();
    const bool noReturn = isNoReturnArgList(argListDatum);
    std::vector<Datum> args = argListItems(argListDatum);
    Datum target = Datum::voidValue();
    if (!args.empty()) {
        target = args.front();
        args.erase(args.begin());
    }

    const Datum result = dispatchObjectMethod(context, std::move(target), methodName, args);
    if (!noReturn) {
        context.push(result);
    }
    return true;
}

} // namespace

OpcodeRegistry::OpcodeRegistry() {
    StackOpcodes::registerHandlers(*this);
    ArithmeticOpcodes::registerHandlers(*this);
    ComparisonOpcodes::registerHandlers(*this);
    LogicalOpcodes::registerHandlers(*this);
    StringOpcodes::registerHandlers(*this);
    VariableOpcodes::registerHandlers(*this);
    ControlFlowOpcodes::registerHandlers(*this);
    ListOpcodes::registerHandlers(*this);
    CallOpcodes::registerHandlers(*this);
    PropertyOpcodes::registerHandlers(*this);
}

const OpcodeHandler* OpcodeRegistry::get(Opcode opcode) const {
    const auto found = handlers_.find(opcode);
    return found == handlers_.end() ? nullptr : &found->second;
}

bool OpcodeRegistry::hasHandler(Opcode opcode) const {
    return get(opcode) != nullptr;
}

bool OpcodeRegistry::execute(Opcode opcode, ExecutionContext& context) const {
    const auto* handler = get(opcode);
    return handler != nullptr && (*handler)(context);
}

void OpcodeRegistry::registerHandler(Opcode opcode, OpcodeHandler handler) {
    handlers_[opcode] = std::move(handler);
}

void StackOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::PUSH_ZERO, pushZero);
    registry.registerHandler(Opcode::PUSH_INT8, pushInt);
    registry.registerHandler(Opcode::PUSH_INT16, pushInt);
    registry.registerHandler(Opcode::PUSH_INT32, pushInt);
    registry.registerHandler(Opcode::PUSH_FLOAT32, pushFloat);
    registry.registerHandler(Opcode::PUSH_CONS, pushCons);
    registry.registerHandler(Opcode::PUSH_SYMB, pushSymb);
    registry.registerHandler(Opcode::PUSH_CHUNK_VAR_REF, pushChunkVarRef);
    registry.registerHandler(Opcode::SWAP, swap);
    registry.registerHandler(Opcode::POP, pop);
    registry.registerHandler(Opcode::PEEK, peek);
    registry.registerHandler(Opcode::NEW_OBJ, newObj);
}

void ControlFlowOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::RET, ret);
    registry.registerHandler(Opcode::RET_FACTORY, retFactory);
    registry.registerHandler(Opcode::JMP, jmp);
    registry.registerHandler(Opcode::JMP_IF_Z, jmpIfZero);
    registry.registerHandler(Opcode::END_REPEAT, endRepeat);
}

void ArithmeticOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::ADD, add);
    registry.registerHandler(Opcode::SUB, sub);
    registry.registerHandler(Opcode::MUL, mul);
    registry.registerHandler(Opcode::DIV, div);
    registry.registerHandler(Opcode::MOD, mod);
    registry.registerHandler(Opcode::INV, inv);
}

void ComparisonOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::LT, lt);
    registry.registerHandler(Opcode::LT_EQ, ltEq);
    registry.registerHandler(Opcode::GT, gt);
    registry.registerHandler(Opcode::GT_EQ, gtEq);
    registry.registerHandler(Opcode::EQ, eq);
    registry.registerHandler(Opcode::NT_EQ, notEq);
}

void LogicalOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::AND, logicalAnd);
    registry.registerHandler(Opcode::OR, logicalOr);
    registry.registerHandler(Opcode::NOT, logicalNot);
}

void StringOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::GET_CHUNK, getChunk);
    registry.registerHandler(Opcode::JOIN_STR, joinStr);
    registry.registerHandler(Opcode::JOIN_PAD_STR, joinPadStr);
    registry.registerHandler(Opcode::CONTAINS_STR, containsStr);
    registry.registerHandler(Opcode::CONTAINS_0_STR, contains0Str);
    registry.registerHandler(Opcode::PUT, put);
    registry.registerHandler(Opcode::DELETE_CHUNK, deleteChunk);
    registry.registerHandler(Opcode::PUT_CHUNK, putChunk);
}

void VariableOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::GET_LOCAL, getLocal);
    registry.registerHandler(Opcode::SET_LOCAL, setLocal);
    registry.registerHandler(Opcode::GET_PARAM, getParam);
    registry.registerHandler(Opcode::SET_PARAM, setParam);
    registry.registerHandler(Opcode::GET_GLOBAL, getGlobal);
    registry.registerHandler(Opcode::GET_GLOBAL2, getGlobal);
    registry.registerHandler(Opcode::SET_GLOBAL, setGlobal);
    registry.registerHandler(Opcode::SET_GLOBAL2, setGlobal);
}

void ListOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::PUSH_LIST, pushList);
    registry.registerHandler(Opcode::PUSH_PROP_LIST, pushPropList);
    registry.registerHandler(Opcode::PUSH_ARG_LIST, pushArgList);
    registry.registerHandler(Opcode::PUSH_ARG_LIST_NO_RET, pushArgListNoRet);
}

void PropertyOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::GET_PROP, getProp);
    registry.registerHandler(Opcode::SET_PROP, setProp);
    registry.registerHandler(Opcode::GET_MOVIE_PROP, getMovieProp);
    registry.registerHandler(Opcode::SET_MOVIE_PROP, setMovieProp);
    registry.registerHandler(Opcode::GET_OBJ_PROP, getObjProp);
    registry.registerHandler(Opcode::SET_OBJ_PROP, setObjProp);
    registry.registerHandler(Opcode::THE_BUILTIN, theBuiltin);
    registry.registerHandler(Opcode::GET_CHAINED_PROP, getChainedProp);
    registry.registerHandler(Opcode::GET_TOP_LEVEL_PROP, getTopLevelProp);
    registry.registerHandler(Opcode::GET, getLegacyProperty);
    registry.registerHandler(Opcode::SET, setLegacyProperty);
    registry.registerHandler(Opcode::GET_FIELD, getField);
}

void CallOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::LOCAL_CALL, localCall);
    registry.registerHandler(Opcode::EXT_CALL, extCall);
    registry.registerHandler(Opcode::OBJ_CALL, objCall);
}

} // namespace libreshockwave::lingo::vm
