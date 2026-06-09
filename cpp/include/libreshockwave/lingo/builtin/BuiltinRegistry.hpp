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

    player::MovieProperties* movieProperties{nullptr};
    player::SpriteProperties* spriteProperties{nullptr};
    PuppetPaletteHandler puppetPaletteHandler;
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
