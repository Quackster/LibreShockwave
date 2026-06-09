#include "libreshockwave/lingo/vm/OpcodeRegistry.hpp"

#include "libreshockwave/bitmap/Bitmap.hpp"

#include <algorithm>
#include <bit>
#include <charconv>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
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

Datum getObjectProperty(const Datum& object, std::string_view propName) {
    if (object.isVoid()) {
        return Datum::voidValue();
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
    if (equalsIgnoreCase(propName, "ilk")) {
        return Datum::symbol(ilkTypeName(object));
    }
    return Datum::voidValue();
}

void setObjectProperty(Datum& object, std::string_view propName, Datum value) {
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
        if (std::isspace(static_cast<unsigned char>(ch))) {
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

int countLines(std::string_view value) {
    if (value.empty()) {
        return 1;
    }
    int count = 1;
    for (const char ch : value) {
        if (ch == '\n' || ch == '\r') {
            ++count;
        }
    }
    return count;
}

int countItems(std::string_view value, char delimiter = ',') {
    if (value.empty()) {
        return 1;
    }
    return static_cast<int>(std::count(value.begin(), value.end(), delimiter)) + 1;
}

Datum stringObjectMethod(const Datum& target, std::string_view methodName, const std::vector<Datum>& args) {
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
        if (equalsIgnoreCase(chunkType, "item")) return Datum::of(countItems(value));
        return Datum::of(static_cast<int>(value.size()));
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

Datum dispatchObjectMethod(ExecutionContext& context, Datum target, std::string_view methodName, const std::vector<Datum>& args) {
    if (target.isList()) {
        return listObjectMethod(target.listValue(), methodName, args);
    }
    if (target.isPropList()) {
        return propListObjectMethod(target.propListValue(), methodName, args);
    }
    if (target.isString()) {
        return stringObjectMethod(target, methodName, args);
    }
    if (const auto* point = target.asIntPoint()) {
        return pointObjectMethod(*point, methodName, args);
    }
    if (const auto* rect = target.asIntRect()) {
        return rectObjectMethod(*rect, methodName, args);
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
    context.push(builtinConstant(propName).value_or(Datum::voidValue()));
    return true;
}

bool setMovieProp(ExecutionContext& context) {
    (void)context.pop();
    return true;
}

bool getObjProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    const Datum object = context.pop();
    context.push(getObjectProperty(object, propName));
    return true;
}

bool setObjProp(ExecutionContext& context) {
    const std::string propName = context.resolveName(context.argument());
    Datum value = context.pop();
    Datum object = context.pop();
    setObjectProperty(object, propName, std::move(value));
    return true;
}

bool theBuiltin(ExecutionContext& context) {
    (void)context.pop();
    const std::string propName = context.resolveName(context.argument());
    if (equalsIgnoreCase(propName, "paramcount")) {
        context.push(Datum::of(static_cast<int>(context.scope().arguments().size())));
    } else if (equalsIgnoreCase(propName, "result")) {
        context.push(context.scope().returnValue());
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
    registry.registerHandler(Opcode::JOIN_STR, joinStr);
    registry.registerHandler(Opcode::JOIN_PAD_STR, joinPadStr);
    registry.registerHandler(Opcode::CONTAINS_STR, containsStr);
    registry.registerHandler(Opcode::CONTAINS_0_STR, contains0Str);
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
}

void CallOpcodes::registerHandlers(OpcodeRegistry& registry) {
    registry.registerHandler(Opcode::LOCAL_CALL, localCall);
    registry.registerHandler(Opcode::EXT_CALL, extCall);
    registry.registerHandler(Opcode::OBJ_CALL, objCall);
}

} // namespace libreshockwave::lingo::vm
