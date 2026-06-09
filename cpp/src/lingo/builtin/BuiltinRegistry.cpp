#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"

#include <algorithm>
#include <cctype>
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

} // namespace

BuiltinRegistry::BuiltinRegistry() {
    SpriteBuiltins::registerBuiltins(*this);
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
