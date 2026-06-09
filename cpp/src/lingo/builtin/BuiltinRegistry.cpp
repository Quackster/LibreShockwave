#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <sstream>
#include <utility>

namespace libreshockwave::lingo::builtin {
namespace {

Datum voidDatum() {
    return Datum::voidValue();
}

bool isMovieRef(const Datum& datum) {
    return datum.type() == DatumType::MovieRef;
}

bool isResetPaletteArg(const Datum& datum) {
    if (!datum.isInt()) {
        return false;
    }
    const int value = datum.intValue();
    return value == 0 || value == -1;
}

bool isEmptyRect(const Datum::IntRect& rect) {
    return rect.right <= rect.left || rect.bottom <= rect.top;
}

Datum boolDatum(bool value) {
    return value ? Datum::TRUE : Datum::FALSE;
}

std::string trimCopy(const std::string& value) {
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

std::optional<long long> parseLongStrict(const std::string& value, int base) {
    const std::string trimmed = trimCopy(value);
    if (trimmed.empty()) {
        return std::nullopt;
    }
    char* end = nullptr;
    const long long result = std::strtoll(trimmed.c_str(), &end, base);
    if (end == trimmed.c_str() || *end != '\0') {
        return std::nullopt;
    }
    return result;
}

std::optional<int> parseIntStrict(const std::string& value) {
    const auto parsed = parseLongStrict(value, 10);
    if (!parsed || *parsed < std::numeric_limits<int>::min() || *parsed > std::numeric_limits<int>::max()) {
        return std::nullopt;
    }
    return static_cast<int>(*parsed);
}

std::optional<double> parseDoubleStrict(const std::string& value) {
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
    if (const auto* value = datum.asColorRef()) {
        return packedColor(*value);
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
    if (const auto* value = datum.asColorRef()) {
        return static_cast<double>(packedColor(*value));
    }
    return 0.0;
}

int javaRoundToInt(double value) {
    if (!std::isfinite(value)) {
        return 0;
    }
    return static_cast<int>(std::floor(value + 0.5));
}

std::string toStringLikeJava(const Datum& datum);
std::string datumReprLikeJava(const Datum& datum);
std::string keyName(const Datum& datum);

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

std::string datumReprLikeJava(const Datum& datum) {
    if (datum.isVoid()) {
        return "<Void>";
    }
    if (const auto* value = datum.asString()) {
        return "\"" + value->value + "\"";
    }
    if (const auto* value = datum.asSymbol()) {
        return "#" + value->name;
    }
    return toStringLikeJava(datum);
}

std::string toStringLikeJava(const Datum& datum) {
    if (datum.isVoid() || datum.isNull()) {
        return "";
    }
    if (const auto* value = datum.asString()) {
        return value->value;
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

bool regionMatchesIgnoreCase(const std::string& value, std::size_t offset, const std::string& pattern) {
    if (offset + pattern.size() > value.size()) {
        return false;
    }
    for (std::size_t index = 0; index < pattern.size(); ++index) {
        const auto lhs = static_cast<unsigned char>(value[offset + index]);
        const auto rhs = static_cast<unsigned char>(pattern[index]);
        if (std::tolower(lhs) != std::tolower(rhs)) {
            return false;
        }
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

bool lessIgnoreCase(const std::string& lhs, const std::string& rhs) {
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

bool lingoEquals(const Datum& lhs, const Datum& rhs) {
    if ((lhs.isVoid() && rhs.isNumber()) || (lhs.isNumber() && rhs.isVoid())) {
        return toDoubleLikeJava(lhs) == toDoubleLikeJava(rhs);
    }
    if (lhs.isNumber() && rhs.isNumber()) {
        return toDoubleLikeJava(lhs) == toDoubleLikeJava(rhs);
    }
    if ((lhs.isString() || lhs.isSymbol()) && (rhs.isString() || rhs.isSymbol())) {
        return equalsIgnoreCase(keyName(lhs), keyName(rhs));
    }
    return lhs == rhs;
}

int findPropIndexTyped(const Datum::PropList& propList, const Datum& key) {
    const std::string target = keyName(key);
    const bool targetIsSymbol = key.asSymbol() != nullptr;
    int fallback = -1;
    const auto& properties = propList.properties();
    for (std::size_t index = 0; index < properties.size(); ++index) {
        const std::string entryName = keyName(properties[index].first);
        if (equalsIgnoreCase(entryName, target)) {
            const bool entryIsSymbol = properties[index].first.asSymbol() != nullptr;
            if (entryIsSymbol == targetIsSymbol) {
                return static_cast<int>(index);
            }
            if (fallback < 0 && entryName == target) {
                fallback = static_cast<int>(index);
            }
        }
    }
    return fallback;
}

int findPropIndexSameType(const Datum::PropList& propList, const Datum& key) {
    const std::string target = keyName(key);
    const bool targetIsSymbol = key.asSymbol() != nullptr;
    const auto& properties = propList.properties();
    for (std::size_t index = 0; index < properties.size(); ++index) {
        if ((properties[index].first.asSymbol() != nullptr) == targetIsSymbol &&
            equalsIgnoreCase(keyName(properties[index].first), target)) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

int findPropIndexUntyped(const Datum::PropList& propList, const Datum& key) {
    const std::string target = keyName(key);
    const auto& properties = propList.properties();
    for (std::size_t index = 0; index < properties.size(); ++index) {
        if (equalsIgnoreCase(keyName(properties[index].first), target)) {
            return static_cast<int>(index);
        }
    }
    return -1;
}

void putPropTyped(Datum::PropList& propList, const Datum& key, Datum value) {
    const int index = findPropIndexTyped(propList, key);
    if (index >= 0) {
        propList.properties()[static_cast<std::size_t>(index)].second = std::move(value);
        return;
    }
    propList.properties().emplace_back(key, std::move(value));
}

void putPropSameType(Datum::PropList& propList, const Datum& key, Datum value) {
    const int index = findPropIndexSameType(propList, key);
    if (index >= 0) {
        propList.properties()[static_cast<std::size_t>(index)].second = std::move(value);
        return;
    }
    propList.properties().emplace_back(key, std::move(value));
}

Datum& mutableArg(const std::vector<Datum>& args, std::size_t index) {
    return const_cast<Datum&>(args[index]);
}

std::string keyName(const Datum& datum) {
    if (const auto* symbol = datum.asSymbol()) {
        return symbol->name;
    }
    if (datum.isVoid()) {
        return "";
    }
    if (datum.isString() || datum.isNumber()) {
        return datum.stringValue();
    }
    try {
        return datum.stringValue();
    } catch (const LingoException&) {
        return datum.typeString();
    }
}

std::string toKeyNameLikeJava(const Datum& datum) {
    if (const auto* symbol = datum.asSymbol()) {
        return symbol->name;
    }
    return toStringLikeJava(datum);
}

std::string ilkType(const Datum& datum) {
    switch (datum.type()) {
        case DatumType::Void: return "void";
        case DatumType::Int: return "integer";
        case DatumType::Float: return "float";
        case DatumType::String:
        case DatumType::StringChunk: return "string";
        case DatumType::Symbol: return "symbol";
        case DatumType::List: return "list";
        case DatumType::PropList: return "propList";
        case DatumType::IntPoint: return "point";
        case DatumType::IntRect: return "rect";
        case DatumType::ColorRef: return "color";
        case DatumType::BitmapRef: return "image";
        case DatumType::SpriteRef: return "sprite";
        case DatumType::CastMemberRef: return "member";
        case DatumType::CastLibRef: return "castLib";
        case DatumType::ScriptInstanceRef: return "instance";
        case DatumType::SoundChannel: return "instance";
        case DatumType::ScriptRef: return "script";
        case DatumType::Xtra: return "xtra";
        case DatumType::XtraInstance: return "xtraInstance";
        case DatumType::StageRef: return "stage";
        default: return "object";
    }
}

} // namespace

BuiltinRegistry::BuiltinRegistry() {
    MathBuiltins::registerBuiltins(*this);
    StringBuiltins::registerBuiltins(*this);
    ListBuiltins::registerBuiltins(*this);
    ConstructorBuiltins::registerBuiltins(*this);
    TypeBuiltins::registerBuiltins(*this);
    SpriteBuiltins::registerBuiltins(*this);
    TimeoutBuiltins::registerBuiltins(*this);
    MovieBuiltins::registerBuiltins(*this);
}

bool BuiltinRegistry::contains(std::string_view name) const {
    return builtins_.contains(normalizeName(name));
}

Datum BuiltinRegistry::invoke(std::string_view name,
                              BuiltinContext& context,
                              const std::vector<Datum>& args) const {
    if (auto result = invokeIfPresent(name, context, args)) {
        return *result;
    }
    return Datum::voidValue();
}

std::optional<Datum> BuiltinRegistry::invokeIfPresent(std::string_view name,
                                                      BuiltinContext& context,
                                                      const std::vector<Datum>& args) const {
    const auto found = builtins_.find(normalizeName(name));
    if (found == builtins_.end()) {
        return std::nullopt;
    }
    return found->second(context, args);
}

const BuiltinFunction* BuiltinRegistry::get(std::string_view name) const {
    const auto found = builtins_.find(normalizeName(name));
    return found == builtins_.end() ? nullptr : &found->second;
}

void BuiltinRegistry::registerBuiltin(std::string_view name, BuiltinFunction function) {
    builtins_[normalizeName(name)] = std::move(function);
}

const std::unordered_map<std::string, BuiltinFunction>& BuiltinRegistry::map() const {
    return builtins_;
}

std::string BuiltinRegistry::normalizeName(std::string_view name) {
    std::string result(name);
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return result;
}

void MathBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("abs", MathBuiltins::abs);
    registry.registerBuiltin("sqrt", MathBuiltins::sqrt);
    registry.registerBuiltin("sin", MathBuiltins::sin);
    registry.registerBuiltin("cos", MathBuiltins::cos);
    registry.registerBuiltin("random", MathBuiltins::random);
    registry.registerBuiltin("integer", MathBuiltins::integer);
    registry.registerBuiltin("float", MathBuiltins::toFloat);
    registry.registerBuiltin("bitand", MathBuiltins::bitAnd);
    registry.registerBuiltin("bitor", MathBuiltins::bitOr);
    registry.registerBuiltin("bitxor", MathBuiltins::bitXor);
    registry.registerBuiltin("bitnot", MathBuiltins::bitNot);
    registry.registerBuiltin("power", MathBuiltins::power);
    registry.registerBuiltin("min", MathBuiltins::min);
    registry.registerBuiltin("max", MathBuiltins::max);
}

Datum MathBuiltins::abs(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    if (args[0].isFloat()) {
        return Datum::of(std::fabs(toDoubleLikeJava(args[0])));
    }
    const int value = toIntLikeJava(args[0]);
    if (value == std::numeric_limits<int>::min()) {
        return Datum::of(value);
    }
    return Datum::of(std::abs(value));
}

Datum MathBuiltins::sqrt(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    return Datum::of(std::sqrt(toDoubleLikeJava(args[0])));
}

Datum MathBuiltins::sin(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    return Datum::of(std::sin(toDoubleLikeJava(args[0]) * 3.14159265358979323846 / 180.0));
}

Datum MathBuiltins::cos(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    return Datum::of(std::cos(toDoubleLikeJava(args[0]) * 3.14159265358979323846 / 180.0));
}

Datum MathBuiltins::random(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(1);
    }
    const int max = toIntLikeJava(args[0]);
    if (max <= 0) {
        return Datum::of(1);
    }
    if (context.randomIntHandler) {
        return Datum::of(context.randomIntHandler(max));
    }
    return Datum::of(1);
}

Datum MathBuiltins::integer(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    if (args[0].isString()) {
        const std::string trimmed = trimCopy(args[0].stringValue());
        if (trimmed.empty()) {
            return Datum::of(0);
        }
        if (trimmed.front() == '*' && trimmed.size() > 1) {
            if (const auto parsed = parseLongStrict(trimmed.substr(1), 16)) {
                return Datum::of(static_cast<int>(*parsed));
            }
        }
        if (const auto parsedInt = parseIntStrict(trimmed)) {
            return Datum::of(*parsedInt);
        }
        if (const auto parsedDouble = parseDoubleStrict(trimmed)) {
            return Datum::of(javaRoundToInt(*parsedDouble));
        }
        return Datum::voidValue();
    }
    return Datum::of(javaRoundToInt(toDoubleLikeJava(args[0])));
}

Datum MathBuiltins::toFloat(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0.0);
    }
    if (args[0].isString()) {
        if (const auto parsed = parseDoubleStrict(args[0].stringValue())) {
            return Datum::of(*parsed);
        }
        return args[0];
    }
    return Datum::of(toDoubleLikeJava(args[0]));
}

Datum MathBuiltins::bitAnd(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::of(0);
    }
    return Datum::of(toIntLikeJava(args[0]) & toIntLikeJava(args[1]));
}

Datum MathBuiltins::bitOr(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::of(0);
    }
    return Datum::of(toIntLikeJava(args[0]) | toIntLikeJava(args[1]));
}

Datum MathBuiltins::bitXor(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::of(0);
    }
    return Datum::of(toIntLikeJava(args[0]) ^ toIntLikeJava(args[1]));
}

Datum MathBuiltins::bitNot(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    return Datum::of(~toIntLikeJava(args[0]));
}

Datum MathBuiltins::power(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::of(0);
    }
    const double result = std::pow(toDoubleLikeJava(args[0]), toDoubleLikeJava(args[1]));
    if (result == static_cast<int>(result) && !args[0].isFloat() && !args[1].isFloat()) {
        return Datum::of(static_cast<int>(result));
    }
    return Datum::of(result);
}

Datum MathBuiltins::min(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() == 1 && args[0].isList()) {
        const auto& items = args[0].listValue().items();
        if (items.empty()) {
            return Datum::of(0);
        }
        Datum result = items.front();
        bool floatResult = result.isFloat();
        for (std::size_t index = 1; index < items.size(); ++index) {
            floatResult = floatResult || items[index].isFloat();
            if (floatResult) {
                result = Datum::of(std::min(toDoubleLikeJava(result), toDoubleLikeJava(items[index])));
            } else {
                result = Datum::of(std::min(toIntLikeJava(result), toIntLikeJava(items[index])));
            }
        }
        return result;
    }
    if (args.size() < 2) {
        return args.empty() ? Datum::of(0) : args[0];
    }
    if (args[0].isFloat() || args[1].isFloat()) {
        return Datum::of(std::min(toDoubleLikeJava(args[0]), toDoubleLikeJava(args[1])));
    }
    return Datum::of(std::min(toIntLikeJava(args[0]), toIntLikeJava(args[1])));
}

Datum MathBuiltins::max(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() == 1 && args[0].isList()) {
        const auto& items = args[0].listValue().items();
        if (items.empty()) {
            return Datum::of(0);
        }
        Datum result = items.front();
        bool floatResult = result.isFloat();
        for (std::size_t index = 1; index < items.size(); ++index) {
            floatResult = floatResult || items[index].isFloat();
            if (floatResult) {
                result = Datum::of(std::max(toDoubleLikeJava(result), toDoubleLikeJava(items[index])));
            } else {
                result = Datum::of(std::max(toIntLikeJava(result), toIntLikeJava(items[index])));
            }
        }
        return result;
    }
    if (args.size() < 2) {
        return args.empty() ? Datum::of(0) : args[0];
    }
    if (args[0].isFloat() || args[1].isFloat()) {
        return Datum::of(std::max(toDoubleLikeJava(args[0]), toDoubleLikeJava(args[1])));
    }
    return Datum::of(std::max(toIntLikeJava(args[0]), toIntLikeJava(args[1])));
}

void StringBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("string", StringBuiltins::string);
    registry.registerBuiltin("length", StringBuiltins::length);
    registry.registerBuiltin("chars", StringBuiltins::chars);
    registry.registerBuiltin("chartonum", StringBuiltins::charToNum);
    registry.registerBuiltin("numtochar", StringBuiltins::numToChar);
    registry.registerBuiltin("offset", StringBuiltins::offset);
    registry.registerBuiltin("getpref", StringBuiltins::getPref);
    registry.registerBuiltin("setpref", StringBuiltins::setPref);
}

Datum StringBuiltins::string(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(std::string());
    }
    return Datum::of(toStringLikeJava(args[0]));
}

Datum StringBuiltins::length(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    if (args[0].isList()) {
        return Datum::of(args[0].listValue().count());
    }
    if (args[0].isPropList()) {
        return Datum::of(args[0].propListValue().count());
    }
    return Datum::of(static_cast<int>(toStringLikeJava(args[0]).size()));
}

Datum StringBuiltins::chars(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 3) {
        return Datum::of(std::string());
    }
    const std::string value = toStringLikeJava(args[0]);
    int start = toIntLikeJava(args[1]) - 1;
    int end = toIntLikeJava(args[2]);
    if (start < 0) {
        start = 0;
    }
    if (end > static_cast<int>(value.size())) {
        end = static_cast<int>(value.size());
    }
    if (start >= end) {
        return Datum::of(std::string());
    }
    return Datum::of(value.substr(static_cast<std::size_t>(start), static_cast<std::size_t>(end - start)));
}

Datum StringBuiltins::charToNum(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    const std::string value = toStringLikeJava(args[0]);
    if (value.empty()) {
        return Datum::of(0);
    }
    return Datum::of(static_cast<int>(static_cast<unsigned char>(value.front())));
}

Datum StringBuiltins::numToChar(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(std::string());
    }
    const char value = static_cast<char>(toIntLikeJava(args[0]));
    return Datum::of(std::string(1, value));
}

Datum StringBuiltins::offset(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::of(0);
    }
    const std::string needle = toStringLikeJava(args[0]);
    const std::string haystack = toStringLikeJava(args[1]);
    if (needle.empty()) {
        return Datum::of(0);
    }
    if (needle.size() > haystack.size()) {
        return Datum::of(0);
    }
    const std::size_t limit = haystack.size() - needle.size();
    for (std::size_t index = 0; index <= limit; ++index) {
        if (regionMatchesIgnoreCase(haystack, index, needle)) {
            return Datum::of(static_cast<int>(index + 1));
        }
    }
    return Datum::of(0);
}

Datum StringBuiltins::getPref(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    const std::string key = toStringLikeJava(args[0]);
    if (key.empty() || !context.getPrefHandler) {
        return Datum::voidValue();
    }
    return context.getPrefHandler(key);
}

Datum StringBuiltins::setPref(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    const std::string key = toStringLikeJava(args[0]);
    if (key.empty() || !context.setPrefHandler) {
        return Datum::voidValue();
    }
    return context.setPrefHandler(key, args[1]);
}

void ListBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("count", ListBuiltins::count);
    registry.registerBuiltin("getat", ListBuiltins::getAt);
    registry.registerBuiltin("setat", ListBuiltins::setAt);
    registry.registerBuiltin("addat", ListBuiltins::addAt);
    registry.registerBuiltin("deleteat", ListBuiltins::deleteAt);
    registry.registerBuiltin("append", ListBuiltins::append);
    registry.registerBuiltin("add", ListBuiltins::append);
    registry.registerBuiltin("getaprop", ListBuiltins::getaProp);
    registry.registerBuiltin("setaprop", ListBuiltins::setaProp);
    registry.registerBuiltin("addprop", ListBuiltins::addProp);
    registry.registerBuiltin("deleteprop", ListBuiltins::deleteProp);
    registry.registerBuiltin("getpropat", ListBuiltins::getPropAt);
    registry.registerBuiltin("findpos", ListBuiltins::findPos);
    registry.registerBuiltin("getone", ListBuiltins::getOne);
    registry.registerBuiltin("getpos", ListBuiltins::getOne);
    registry.registerBuiltin("deleteone", ListBuiltins::deleteOne);
    registry.registerBuiltin("sort", ListBuiltins::sort);
    registry.registerBuiltin("listp", ListBuiltins::listp);
    registry.registerBuiltin("list", ListBuiltins::list);
    registry.registerBuiltin("getlast", ListBuiltins::getLast);
}

Datum ListBuiltins::count(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(0);
    }
    if (args[0].isList()) {
        return Datum::of(args[0].listValue().count());
    }
    if (args[0].isPropList()) {
        return Datum::of(args[0].propListValue().count());
    }
    return Datum::of(0);
}

Datum ListBuiltins::getAt(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    const Datum& container = args[0];
    const Datum& keyOrIndex = args[1];
    if (container.isList()) {
        const int index = toIntLikeJava(keyOrIndex);
        const auto& items = container.listValue().items();
        if (index >= 1 && index <= static_cast<int>(items.size())) {
            return items[static_cast<std::size_t>(index - 1)];
        }
        return Datum::voidValue();
    }
    if (container.isPropList()) {
        const auto& propList = container.propListValue();
        int index = -1;
        if (keyOrIndex.asSymbol() != nullptr || keyOrIndex.isString()) {
            index = findPropIndexTyped(propList, keyOrIndex);
        } else {
            const int position = toIntLikeJava(keyOrIndex);
            if (position >= 1 && position <= propList.count()) {
                index = position - 1;
            }
        }
        if (index >= 0) {
            return propList.properties()[static_cast<std::size_t>(index)].second;
        }
        return Datum::voidValue();
    }
    if (const auto* point = container.asIntPoint()) {
        switch (toIntLikeJava(keyOrIndex)) {
            case 1: return Datum::of(point->x);
            case 2: return Datum::of(point->y);
            default: return Datum::voidValue();
        }
    }
    if (const auto* rect = container.asIntRect()) {
        switch (toIntLikeJava(keyOrIndex)) {
            case 1: return Datum::of(rect->left);
            case 2: return Datum::of(rect->top);
            case 3: return Datum::of(rect->right);
            case 4: return Datum::of(rect->bottom);
            default: return Datum::voidValue();
        }
    }
    return Datum::voidValue();
}

Datum ListBuiltins::setAt(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 3) {
        return Datum::voidValue();
    }
    const Datum& keyOrIndex = args[1];
    const Datum& value = args[2];
    if (args[0].isList()) {
        const int index = toIntLikeJava(keyOrIndex);
        auto& items = mutableArg(args, 0).listValue().items();
        if (index >= 1 && index <= static_cast<int>(items.size())) {
            items[static_cast<std::size_t>(index - 1)] = value;
        }
        return Datum::voidValue();
    }
    if (args[0].isPropList()) {
        auto& propList = mutableArg(args, 0).propListValue();
        if (keyOrIndex.asSymbol() != nullptr || keyOrIndex.isString()) {
            putPropSameType(propList, keyOrIndex, value);
        } else {
            const int position = toIntLikeJava(keyOrIndex);
            if (position >= 1 && position <= propList.count()) {
                propList.properties()[static_cast<std::size_t>(position - 1)].second = value;
            }
        }
        return Datum::voidValue();
    }
    return Datum::voidValue();
}

Datum ListBuiltins::addAt(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 3 || !args[0].isList()) {
        return Datum::voidValue();
    }
    auto& items = mutableArg(args, 0).listValue().items();
    int position = toIntLikeJava(args[1]) - 1;
    if (position < 0) {
        position = 0;
    }
    if (position > static_cast<int>(items.size())) {
        position = static_cast<int>(items.size());
    }
    items.insert(items.begin() + position, args[2]);
    return Datum::voidValue();
}

Datum ListBuiltins::deleteAt(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    const int position = toIntLikeJava(args[1]);
    if (args[0].isList()) {
        auto& items = mutableArg(args, 0).listValue().items();
        if (position >= 1 && position <= static_cast<int>(items.size())) {
            items.erase(items.begin() + (position - 1));
        }
        return Datum::voidValue();
    }
    if (args[0].isPropList()) {
        auto& properties = mutableArg(args, 0).propListValue().properties();
        if (position >= 1 && position <= static_cast<int>(properties.size())) {
            properties.erase(properties.begin() + (position - 1));
        }
    }
    return Datum::voidValue();
}

Datum ListBuiltins::append(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2 || !args[0].isList()) {
        return Datum::voidValue();
    }
    mutableArg(args, 0).listValue().items().push_back(args[1]);
    return Datum::voidValue();
}

Datum ListBuiltins::getaProp(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2 || !args[0].isPropList()) {
        return Datum::voidValue();
    }
    const int index = findPropIndexTyped(args[0].propListValue(), args[1]);
    return index >= 0 ? args[0].propListValue().properties()[static_cast<std::size_t>(index)].second
                      : Datum::voidValue();
}

Datum ListBuiltins::setaProp(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 3 || !args[0].isPropList()) {
        return Datum::voidValue();
    }
    putPropTyped(mutableArg(args, 0).propListValue(), args[1], args[2]);
    return Datum::voidValue();
}

Datum ListBuiltins::addProp(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 3 || !args[0].isPropList()) {
        return Datum::voidValue();
    }
    mutableArg(args, 0).propListValue().properties().emplace_back(args[1], args[2]);
    return Datum::voidValue();
}

Datum ListBuiltins::deleteProp(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2 || !args[0].isPropList()) {
        return Datum::voidValue();
    }
    const int index = findPropIndexTyped(args[0].propListValue(), args[1]);
    if (index >= 0) {
        auto& properties = mutableArg(args, 0).propListValue().properties();
        properties.erase(properties.begin() + index);
    }
    return Datum::voidValue();
}

Datum ListBuiltins::getPropAt(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2 || !args[0].isPropList()) {
        return Datum::voidValue();
    }
    const int position = toIntLikeJava(args[1]);
    const auto& properties = args[0].propListValue().properties();
    if (position >= 1 && position <= static_cast<int>(properties.size())) {
        return properties[static_cast<std::size_t>(position - 1)].first;
    }
    return Datum::voidValue();
}

Datum ListBuiltins::findPos(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2 || !args[0].isPropList()) {
        return Datum::voidValue();
    }
    const int index = findPropIndexUntyped(args[0].propListValue(), args[1]);
    return index >= 0 ? Datum::of(index + 1) : Datum::voidValue();
}

Datum ListBuiltins::getOne(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::of(0);
    }
    if (args[0].isList()) {
        const auto& items = args[0].listValue().items();
        for (std::size_t index = 0; index < items.size(); ++index) {
            if (lingoEquals(items[index], args[1])) {
                return Datum::of(static_cast<int>(index + 1));
            }
        }
    }
    return Datum::of(0);
}

Datum ListBuiltins::deleteOne(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2 || !args[0].isList()) {
        return Datum::voidValue();
    }
    auto& items = mutableArg(args, 0).listValue().items();
    for (auto iterator = items.begin(); iterator != items.end(); ++iterator) {
        if (lingoEquals(*iterator, args[1])) {
            items.erase(iterator);
            break;
        }
    }
    return Datum::voidValue();
}

Datum ListBuiltins::sort(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty() || !args[0].isList()) {
        return Datum::voidValue();
    }
    auto& items = mutableArg(args, 0).listValue().items();
    std::sort(items.begin(), items.end(), [](const Datum& lhs, const Datum& rhs) {
        if (lhs.isInt() && rhs.isInt()) {
            return lhs.intValue() < rhs.intValue();
        }
        return lessIgnoreCase(toStringLikeJava(lhs), toStringLikeJava(rhs));
    });
    return Datum::voidValue();
}

Datum ListBuiltins::listp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(!args.empty() && (args[0].isList() || args[0].isPropList()));
}

Datum ListBuiltins::list(BuiltinContext&, const std::vector<Datum>& args) {
    return Datum::list(args);
}

Datum ListBuiltins::getLast(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    if (args[0].isList()) {
        const auto& items = args[0].listValue().items();
        return items.empty() ? Datum::voidValue() : items.back();
    }
    if (args[0].isPropList()) {
        const auto& properties = args[0].propListValue().properties();
        return properties.empty() ? Datum::voidValue() : properties.back().second;
    }
    return Datum::voidValue();
}

void TimeoutBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("timeout", TimeoutBuiltins::timeout);
}

Datum TimeoutBuiltins::timeout(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::timeoutRef("");
    }
    return Datum::timeoutRef(toStringLikeJava(args[0]));
}

Datum TimeoutBuiltins::handleMethod(BuiltinContext& context,
                                    const Datum::TimeoutRef& ref,
                                    std::string_view methodName,
                                    const std::vector<Datum>& args) {
    const std::string method = BuiltinRegistry::normalizeName(methodName);
    auto* manager = context.timeoutManager;

    if (method == "new") {
        if (manager == nullptr) {
            return Datum::voidValue();
        }

        std::string name = ref.name;
        std::size_t argOffset = 0;
        if (name.empty() && !args.empty()) {
            name = toStringLikeJava(args[0]);
            argOffset = 1;
        }
        if (name.empty()) {
            return Datum::voidValue();
        }

        const int periodMs = args.size() > argOffset ? toIntLikeJava(args[argOffset]) : 1000;
        const std::string handler = args.size() > argOffset + 1 ? toKeyNameLikeJava(args[argOffset + 1]) : "";
        const Datum target = args.size() > argOffset + 2 ? args[argOffset + 2] : Datum::voidValue();
        return manager->createTimeout(name, periodMs, handler, target);
    }

    if (method == "forget") {
        if (manager != nullptr && !ref.name.empty()) {
            manager->forgetTimeout(ref.name);
        }
        return Datum::voidValue();
    }

    if (manager != nullptr && !ref.name.empty()) {
        return manager->getTimeoutProp(ref.name, std::string(methodName));
    }
    return Datum::voidValue();
}

Datum TimeoutBuiltins::getProperty(BuiltinContext& context,
                                   const Datum::TimeoutRef& ref,
                                   std::string_view propName) {
    if (context.timeoutManager == nullptr || ref.name.empty()) {
        return Datum::voidValue();
    }
    return context.timeoutManager->getTimeoutProp(ref.name, std::string(propName));
}

bool TimeoutBuiltins::setProperty(BuiltinContext& context,
                                  const Datum::TimeoutRef& ref,
                                  std::string_view propName,
                                  Datum value) {
    if (context.timeoutManager == nullptr || ref.name.empty()) {
        return false;
    }
    return context.timeoutManager->setTimeoutProp(ref.name, std::string(propName), std::move(value));
}

void ConstructorBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("point", ConstructorBuiltins::point);
    registry.registerBuiltin("rect", ConstructorBuiltins::rect);
    registry.registerBuiltin("union", ConstructorBuiltins::unionRect);
    registry.registerBuiltin("intersect", ConstructorBuiltins::intersect);
    registry.registerBuiltin("color", ConstructorBuiltins::color);
    registry.registerBuiltin("rgb", ConstructorBuiltins::rgb);
    registry.registerBuiltin("paletteindex", ConstructorBuiltins::paletteIndex);
    registry.registerBuiltin("sprite", ConstructorBuiltins::sprite);
    registry.registerBuiltin("new", ConstructorBuiltins::newInstance);
}

Datum ConstructorBuiltins::point(BuiltinContext&, const std::vector<Datum>& args) {
    const int x = args.empty() ? 0 : args[0].intValue();
    const int y = args.size() < 2 ? 0 : args[1].intValue();
    return Datum::intPoint(x, y);
}

Datum ConstructorBuiltins::rect(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() == 2 && args[0].asIntPoint() != nullptr && args[1].asIntPoint() != nullptr) {
        const auto* first = args[0].asIntPoint();
        const auto* second = args[1].asIntPoint();
        return Datum::intRect(first->x, first->y, second->x, second->y);
    }
    const int left = args.empty() ? 0 : args[0].intValue();
    const int top = args.size() < 2 ? 0 : args[1].intValue();
    const int right = args.size() < 3 ? 0 : args[2].intValue();
    const int bottom = args.size() < 4 ? 0 : args[3].intValue();
    return Datum::intRect(left, top, right, bottom);
}

Datum ConstructorBuiltins::unionRect(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    const auto* first = args[0].asIntRect();
    const auto* second = args[1].asIntRect();
    if (first == nullptr || second == nullptr) {
        return Datum::voidValue();
    }
    const bool firstEmpty = isEmptyRect(*first);
    const bool secondEmpty = isEmptyRect(*second);
    if (firstEmpty && secondEmpty) {
        return Datum::intRect(0, 0, 0, 0);
    }
    if (firstEmpty) {
        return args[1];
    }
    if (secondEmpty) {
        return args[0];
    }
    return Datum::intRect(std::min(first->left, second->left),
                          std::min(first->top, second->top),
                          std::max(first->right, second->right),
                          std::max(first->bottom, second->bottom));
}

Datum ConstructorBuiltins::intersect(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    const auto* first = args[0].asIntRect();
    const auto* second = args[1].asIntRect();
    if (first == nullptr || second == nullptr) {
        return Datum::voidValue();
    }
    const int left = std::max(first->left, second->left);
    const int top = std::max(first->top, second->top);
    const int right = std::min(first->right, second->right);
    const int bottom = std::min(first->bottom, second->bottom);
    if (right <= left || bottom <= top) {
        return Datum::intRect(0, 0, 0, 0);
    }
    return Datum::intRect(left, top, right, bottom);
}

Datum ConstructorBuiltins::color(BuiltinContext&, const std::vector<Datum>& args) {
    const int r = args.empty() ? 0 : args[0].intValue();
    const int g = args.size() < 2 ? 0 : args[1].intValue();
    const int b = args.size() < 3 ? 0 : args[2].intValue();
    return Datum::colorRef(r, g, b);
}

Datum ConstructorBuiltins::rgb(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::colorRef(0, 0, 0);
    }
    if (args.size() == 1) {
        if (args[0].asColorRef() != nullptr) {
            return args[0];
        }
        if (args[0].isString()) {
            std::string hex = trimCopy(args[0].stringValue());
            if (!hex.empty() && hex.front() == '#') {
                hex.erase(hex.begin());
            }
            char* end = nullptr;
            const long value = std::strtol(hex.c_str(), &end, 16);
            if (end == hex.c_str() || *end != '\0') {
                return Datum::colorRef(0, 0, 0);
            }
            return Datum::colorRef(static_cast<int>((value >> 16) & 0xFF),
                                   static_cast<int>((value >> 8) & 0xFF),
                                   static_cast<int>(value & 0xFF));
        }
    }
    if (args.size() >= 3) {
        return Datum::colorRef(args[0].intValue(), args[1].intValue(), args[2].intValue());
    }
    const int value = args[0].intValue();
    return Datum::colorRef((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
}

Datum ConstructorBuiltins::paletteIndex(BuiltinContext&, const std::vector<Datum>& args) {
    const int index = args.empty() ? 0 : args[0].intValue() & 0xFF;
    return Datum::colorRef(index, index, index);
}

Datum ConstructorBuiltins::sprite(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    const int channel = args[0].intValue();
    if (channel < 0) {
        return Datum::voidValue();
    }
    return Datum::spriteRef(id::ChannelId(channel));
}

Datum ConstructorBuiltins::newInstance(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }

    const Datum& target = args.front();
    std::vector<Datum> constructorArgs(args.begin() + 1, args.end());
    if (const auto* typeSymbol = target.asSymbol();
        typeSymbol != nullptr && !constructorArgs.empty() && constructorArgs.front().asCastLibRef() != nullptr) {
        if (context.castMemberCreator) {
            return context.castMemberCreator(constructorArgs.front().asCastLibRef()->castLib, typeSymbol->name);
        }
        return Datum::voidValue();
    }

    if (context.newInstanceHandler) {
        return context.newInstanceHandler(target, constructorArgs);
    }
    return Datum::voidValue();
}

void TypeBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("objectp", TypeBuiltins::objectp);
    registry.registerBuiltin("voidp", TypeBuiltins::voidp);
    registry.registerBuiltin("value", TypeBuiltins::value);
    registry.registerBuiltin("script", TypeBuiltins::script);
    registry.registerBuiltin("ilk", TypeBuiltins::ilk);
    registry.registerBuiltin("listp", TypeBuiltins::listp);
    registry.registerBuiltin("stringp", TypeBuiltins::stringp);
    registry.registerBuiltin("integerp", TypeBuiltins::integerp);
    registry.registerBuiltin("floatp", TypeBuiltins::floatp);
    registry.registerBuiltin("symbolp", TypeBuiltins::symbolp);
    registry.registerBuiltin("symbol", TypeBuiltins::symbol);
    registry.registerBuiltin("callancestor", TypeBuiltins::callAncestor);
}

Datum TypeBuiltins::objectp(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::FALSE;
    }
    const Datum& value = args[0];
    return boolDatum(!(value.isVoid() || value.isNumber() || value.isSymbol() || value.isString()));
}

Datum TypeBuiltins::voidp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(args.empty() || args[0].isVoid());
}

Datum TypeBuiltins::value(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    if (!args[0].isString()) {
        return args[0];
    }
    if (context.valueEvaluator) {
        return context.valueEvaluator(args[0]);
    }
    return Datum::voidValue();
}

Datum TypeBuiltins::script(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }

    const Datum& identifier = args[0];
    std::optional<Datum> scope;
    if (args.size() >= 2) {
        scope = args[1];
    }

    if (const auto* member = identifier.asCastMemberRef()) {
        return Datum::scriptRef(*member);
    }

    if (identifier.isList()) {
        for (const auto& item : identifier.listValue().items()) {
            std::vector<Datum> nestedArgs{item};
            if (scope) {
                nestedArgs.push_back(*scope);
            }
            Datum result = script(context, nestedArgs);
            if (!result.isVoid()) {
                return result;
            }
        }
        return Datum::voidValue();
    }

    if (context.scriptResolver) {
        return context.scriptResolver(identifier, scope);
    }
    return Datum::voidValue();
}

Datum TypeBuiltins::ilk(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::symbol("void");
    }

    const std::string typeName = ilkType(args[0]);
    if (args.size() < 2) {
        return Datum::symbol(typeName);
    }

    const std::string typeKey = BuiltinRegistry::normalizeName(typeName);
    const std::string checkKey = BuiltinRegistry::normalizeName(keyName(args[1]));
    if (typeKey == checkKey) {
        return Datum::TRUE;
    }
    if (checkKey == "list" && (typeKey == "proplist" || typeKey == "rect" || typeKey == "point")) {
        return Datum::TRUE;
    }
    if (checkKey == "linearlist" && typeKey == "list") {
        return Datum::TRUE;
    }
    if (checkKey == "number" && (typeKey == "integer" || typeKey == "float")) {
        return Datum::TRUE;
    }
    if (checkKey == "object" &&
        (typeKey == "instance" || typeKey == "member" || typeKey == "xtra" || typeKey == "xtrainstance" ||
         typeKey == "script" || typeKey == "castlib" || typeKey == "sprite" || typeKey == "stage" ||
         typeKey == "image")) {
        return Datum::TRUE;
    }
    return Datum::FALSE;
}

Datum TypeBuiltins::listp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(!args.empty() && (args[0].isList() || args[0].isPropList()));
}

Datum TypeBuiltins::stringp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(!args.empty() && args[0].isString());
}

Datum TypeBuiltins::integerp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(!args.empty() && args[0].isInt());
}

Datum TypeBuiltins::floatp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(!args.empty() && args[0].isFloat());
}

Datum TypeBuiltins::symbolp(BuiltinContext&, const std::vector<Datum>& args) {
    return boolDatum(!args.empty() && args[0].isSymbol());
}

Datum TypeBuiltins::symbol(BuiltinContext&, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    if (args[0].isSymbol()) {
        return args[0];
    }
    if (!args[0].isString()) {
        return Datum::voidValue();
    }
    std::string value = args[0].stringValue();
    if (!value.empty() && value.front() == '#') {
        value.erase(value.begin());
    }
    return Datum::symbol(std::move(value));
}

Datum TypeBuiltins::callAncestor(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    if (context.ancestorCallHandler) {
        return context.ancestorCallHandler(args);
    }
    return Datum::voidValue();
}

void MovieBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("label", MovieBuiltins::label);
    registry.registerBuiltin("marker", MovieBuiltins::marker);
}

Datum MovieBuiltins::label(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty() || context.movieProperties == nullptr) {
        return Datum::of(0);
    }

    const Datum& labelArg = args.size() >= 2 && isMovieRef(args[0]) ? args[1] : args[0];
    return Datum::of(std::max(context.movieProperties->getFrameForLabel(labelArg.stringValue()), 0));
}

Datum MovieBuiltins::marker(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty() || context.movieProperties == nullptr) {
        return Datum::of(0);
    }

    const Datum& markerArg = args.size() >= 2 && isMovieRef(args[0]) ? args[1] : args[0];
    if (markerArg.isString()) {
        return Datum::of(std::max(context.movieProperties->getFrameForLabel(markerArg.stringValue()), 0));
    }
    return Datum::of(std::max(context.movieProperties->getMarkerFrame(markerArg.intValue()), 0));
}

void SpriteBuiltins::registerBuiltins(BuiltinRegistry& registry) {
    registry.registerBuiltin("puppettempo", SpriteBuiltins::puppetTempo);
    registry.registerBuiltin("puppetsprite", SpriteBuiltins::puppetSprite);
    registry.registerBuiltin("puppetpalette", SpriteBuiltins::puppetPalette);
    registry.registerBuiltin("cursor", SpriteBuiltins::cursor);
    registry.registerBuiltin("setcursor", SpriteBuiltins::cursor);
    registry.registerBuiltin("pauseupdate", [](BuiltinContext&, const std::vector<Datum>&) { return voidDatum(); });
    registry.registerBuiltin("updatestage", [](BuiltinContext&, const std::vector<Datum>&) { return voidDatum(); });
    registry.registerBuiltin("movetofront", [](BuiltinContext&, const std::vector<Datum>&) { return voidDatum(); });
    registry.registerBuiltin("movetoback", [](BuiltinContext&, const std::vector<Datum>&) { return voidDatum(); });
    registry.registerBuiltin("spritebox", SpriteBuiltins::spriteBox);
}

Datum SpriteBuiltins::puppetTempo(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    if (context.movieProperties != nullptr) {
        (void)context.movieProperties->setMovieProp("puppetTempo", Datum::of(args[0].intValue()));
    }
    return Datum::voidValue();
}

Datum SpriteBuiltins::puppetSprite(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.size() < 2) {
        return Datum::voidValue();
    }
    if (context.spriteProperties != nullptr) {
        (void)context.spriteProperties->setSpriteProp(args[0].intValue(),
                                                      "puppet",
                                                      Datum::of(args[1].boolValue() ? 1 : 0));
    }
    return Datum::voidValue();
}

Datum SpriteBuiltins::puppetPalette(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    if (context.puppetPaletteHandler) {
        if (isResetPaletteArg(args[0])) {
            context.puppetPaletteHandler(std::nullopt);
        } else {
            context.puppetPaletteHandler(args[0]);
        }
    }
    return Datum::voidValue();
}

Datum SpriteBuiltins::cursor(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::voidValue();
    }
    if (context.movieProperties != nullptr) {
        (void)context.movieProperties->setMovieProp("cursor", args[0]);
    }
    return Datum::voidValue();
}

Datum SpriteBuiltins::spriteBox(BuiltinContext& context, const std::vector<Datum>& args) {
    if (args.empty() || context.spriteProperties == nullptr) {
        return Datum::intRect(0, 0, 0, 0);
    }
    return context.spriteProperties->getSpriteProp(args[0].intValue(), "rect");
}

} // namespace libreshockwave::lingo::builtin
