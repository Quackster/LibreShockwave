#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/MovieProperties.hpp"
#include "libreshockwave/player/audio/SoundManager.hpp"
#include "libreshockwave/player/net/NetManager.hpp"
#include "libreshockwave/player/SpriteProperties.hpp"
#include "libreshockwave/player/timeout/TimeoutManager.hpp"

namespace libreshockwave::lingo::builtin {

struct BuiltinContext {
    using PuppetPaletteHandler = std::function<void(std::optional<Datum> paletteRef)>;
    using CastMemberCreator = std::function<Datum(int castLib, const std::string& memberType)>;
    using NamedCastMemberCreator = std::function<Datum(const std::string& memberName, const std::string& memberType)>;
    using CastLibNumberResolver = std::function<int(int castLib)>;
    using CastLibNameResolver = std::function<int(const std::string& name)>;
    using CastLibCountSupplier = std::function<int()>;
    using CastMemberResolver = std::function<Datum(int castLib, int memberNum)>;
    using CastMemberNameResolver = std::function<Datum(int castLib, const std::string& memberName)>;
    using CastMemberExistsResolver = std::function<bool(int castLib, int memberNum)>;
    using FieldResolver = std::function<Datum(const Datum& identifier, int castLib)>;
    using NewInstanceHandler = std::function<Datum(const Datum& target, const std::vector<Datum>& args)>;
    using ValueEvaluator = std::function<Datum(const Datum& value)>;
    using ScriptResolver = std::function<Datum(const Datum& identifier, const std::optional<Datum>& scope)>;
    using AncestorCallHandler = std::function<Datum(const std::vector<Datum>& args)>;
    using RandomIntHandler = std::function<int(int max)>;
    using GetPrefHandler = std::function<Datum(const std::string& name)>;
    using SetPrefHandler = std::function<Datum(const std::string& name, const Datum& value)>;
    using OutputHandler = std::function<void(std::string_view kind, const std::string& text)>;
    using AlertHandler = std::function<bool(const std::string& text)>;

    player::MovieProperties* movieProperties{nullptr};
    player::net::NetManager* netManager{nullptr};
    player::audio::SoundManager* soundManager{nullptr};
    player::SpriteProperties* spriteProperties{nullptr};
    player::timeout::TimeoutManager* timeoutManager{nullptr};
    std::vector<std::pair<std::string, std::string>> externalParams;
    bool tellStreamStatusEnabled{false};
    bool debugPlaybackEnabled{false};
    PuppetPaletteHandler puppetPaletteHandler;
    CastMemberCreator castMemberCreator;
    NamedCastMemberCreator namedCastMemberCreator;
    CastLibNumberResolver castLibNumberResolver;
    CastLibNameResolver castLibNameResolver;
    CastLibCountSupplier castLibCountSupplier;
    CastMemberResolver castMemberResolver;
    CastMemberNameResolver castMemberNameResolver;
    CastMemberExistsResolver castMemberExistsResolver;
    FieldResolver fieldResolver;
    NewInstanceHandler newInstanceHandler;
    ValueEvaluator valueEvaluator;
    ScriptResolver scriptResolver;
    AncestorCallHandler ancestorCallHandler;
    RandomIntHandler randomIntHandler;
    GetPrefHandler getPrefHandler;
    SetPrefHandler setPrefHandler;
    OutputHandler outputHandler;
    AlertHandler alertHandler;
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

class StringBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum string(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum length(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum chars(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum charToNum(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum numToChar(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum offset(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getPref(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum setPref(BuiltinContext& context, const std::vector<Datum>& args);
};

class OutputBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum put(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum alert(BuiltinContext& context, const std::vector<Datum>& args);
};

class ListBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum count(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getAt(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum setAt(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum addAt(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum deleteAt(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum append(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getaProp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum setaProp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum addProp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum deleteProp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getPropAt(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum findPos(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getOne(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum deleteOne(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum sort(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum listp(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum list(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getLast(BuiltinContext& context, const std::vector<Datum>& args);
};

class TimeoutBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum timeout(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum handleMethod(BuiltinContext& context,
                                            const Datum::TimeoutRef& ref,
                                            std::string_view methodName,
                                            const std::vector<Datum>& args);
    [[nodiscard]] static Datum getProperty(BuiltinContext& context,
                                           const Datum::TimeoutRef& ref,
                                           std::string_view propName);
    static bool setProperty(BuiltinContext& context,
                            const Datum::TimeoutRef& ref,
                            std::string_view propName,
                            Datum value);
};

class NetBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum preloadNetThing(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum postNetText(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum netDone(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum netTextResult(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum netError(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum getStreamStatus(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum tellStreamStatus(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum gotoNetPage(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum gotoNetMovie(BuiltinContext& context, const std::vector<Datum>& args);
};

class ExternalParamBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum externalParamValue(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum externalParamName(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum externalParamCount(BuiltinContext& context, const std::vector<Datum>& args);
};

class SoundBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum sound(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum soundEnabled(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum handleMethod(BuiltinContext& context,
                                            const Datum::SoundChannel& channel,
                                            std::string_view methodName,
                                            const std::vector<Datum>& args);
    [[nodiscard]] static Datum getProperty(BuiltinContext& context,
                                           const Datum::SoundChannel& channel,
                                           std::string_view propName);
    static bool setProperty(BuiltinContext& context,
                            const Datum::SoundChannel& channel,
                            std::string_view propName,
                            Datum value);
};

class CastLibBuiltins {
public:
    static void registerBuiltins(BuiltinRegistry& registry);
    [[nodiscard]] static Datum castLib(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum member(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum field(BuiltinContext& context, const std::vector<Datum>& args);
    [[nodiscard]] static Datum createMember(BuiltinContext& context, const std::vector<Datum>& args);
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
