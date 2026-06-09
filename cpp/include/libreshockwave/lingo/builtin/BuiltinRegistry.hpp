#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/MovieProperties.hpp"
#include "libreshockwave/player/SpriteProperties.hpp"

namespace libreshockwave::lingo::builtin {

struct BuiltinContext {
    using PuppetPaletteHandler = std::function<void(std::optional<Datum> paletteRef)>;
    using CastMemberCreator = std::function<Datum(int castLib, const std::string& memberType)>;
    using NewInstanceHandler = std::function<Datum(const Datum& target, const std::vector<Datum>& args)>;
    using ValueEvaluator = std::function<Datum(const Datum& value)>;
    using ScriptResolver = std::function<Datum(const Datum& identifier, const std::optional<Datum>& scope)>;
    using AncestorCallHandler = std::function<Datum(const std::vector<Datum>& args)>;
    using RandomIntHandler = std::function<int(int max)>;

    player::MovieProperties* movieProperties{nullptr};
    player::SpriteProperties* spriteProperties{nullptr};
    PuppetPaletteHandler puppetPaletteHandler;
    CastMemberCreator castMemberCreator;
    NewInstanceHandler newInstanceHandler;
    ValueEvaluator valueEvaluator;
    ScriptResolver scriptResolver;
    AncestorCallHandler ancestorCallHandler;
    RandomIntHandler randomIntHandler;
};

using BuiltinFunction = std::function<Datum(BuiltinContext& context, const std::vector<Datum>& args)>;

class BuiltinRegistry {
public:
    BuiltinRegistry();

    [[nodiscard]] bool contains(std::string_view name) const;
    [[nodiscard]] Datum invoke(std::string_view name,
                               BuiltinContext& context,
                               const std::vector<Datum>& args = {}) const;
    [[nodiscard]] std::optional<Datum> invokeIfPresent(std::string_view name,
                                                       BuiltinContext& context,
                                                       const std::vector<Datum>& args = {}) const;
    [[nodiscard]] const BuiltinFunction* get(std::string_view name) const;

    void registerBuiltin(std::string_view name, BuiltinFunction function);
    [[nodiscard]] const std::unordered_map<std::string, BuiltinFunction>& map() const;

    [[nodiscard]] static std::string normalizeName(std::string_view name);

private:
    std::unordered_map<std::string, BuiltinFunction> builtins_;
};

class MathBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum abs(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum sqrt(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum sin(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum cos(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum random(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum integer(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum toFloat(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum bitAnd(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum bitOr(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum bitXor(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum bitNot(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum power(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum min(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum max(BuiltinContext& context, const std::vector<Datum>& args);
};

class ConstructorBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum point(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum rect(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum unionRect(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum intersect(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum color(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum rgb(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum paletteIndex(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum sprite(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum newInstance(BuiltinContext& context, const std::vector<Datum>& args);
};

class TypeBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum objectp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum voidp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum value(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum script(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum ilk(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum listp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum stringp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum integerp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum floatp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum symbolp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum symbol(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum callAncestor(BuiltinContext& context, const std::vector<Datum>& args);
};

class MovieBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum label(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum marker(BuiltinContext& context, const std::vector<Datum>& args);
};

class SpriteBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum puppetTempo(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum puppetSprite(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum puppetPalette(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum cursor(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum spriteBox(BuiltinContext& context, const std::vector<Datum>& args);
};

} // namespace libreshockwave::lingo::builtin
