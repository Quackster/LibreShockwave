#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

#include "libreshockwave/id/Ids.hpp"

namespace libreshockwave::lingo {

class LingoException : public std::runtime_error {
public:
    explicit LingoException(const std::string& message) : std::runtime_error(message) {}

    [[nodiscard]] static LingoException typeMismatch(const std::string& expected, const std::string& actual);
    [[nodiscard]] static LingoException undefinedHandler(const std::string& name);
    [[nodiscard]] static LingoException undefinedVariable(const std::string& name);
    [[nodiscard]] static LingoException indexOutOfBounds(int index, int size);
};

enum class DatumType {
    Null,
    Void,
    Int,
    Float,
    String,
    StringChunk,
    Symbol,
    VarRef,
    List,
    PropList,
    ArgList,
    ArgListNoRet,
    CastLibRef,
    CastMemberRef,
    ScriptRef,
    ScriptInstanceRef,
    SpriteRef,
    StageRef,
    PlayerRef,
    MovieRef,
    IntPoint,
    IntRect,
    Vector,
    ColorRef,
    BitmapRef,
    PaletteRef,
    Matte,
    SoundRef,
    SoundChannel,
    CursorRef,
    TimeoutRef,
    Xtra,
    XtraInstance,
    XmlRef,
    DateRef,
    MathRef
};

[[nodiscard]] std::string_view typeName(DatumType type);

enum class StringChunkType {
    Item = 0x01,
    Word = 0x02,
    Char = 0x03,
    Line = 0x04
};

[[nodiscard]] int code(StringChunkType type);
[[nodiscard]] std::string_view name(StringChunkType type);
[[nodiscard]] StringChunkType stringChunkTypeFromCode(int code);
[[nodiscard]] StringChunkType stringChunkTypeFromName(std::string_view name);

class Datum {
public:
    class List;
    class PropList;
    class ScriptInstanceRef;
    class ArgList;
    class ArgListNoRet;

    struct Null {
        friend bool operator==(const Null&, const Null&) = default;
    };

    struct Void {
        friend bool operator==(const Void&, const Void&) = default;
    };

    struct Int {
        int value;
        friend bool operator==(const Int&, const Int&) = default;
    };

    struct DFloat {
        float value;
        friend bool operator==(const DFloat&, const DFloat&) = default;
    };

    struct Str {
        std::string value;
        friend bool operator==(const Str&, const Str&) = default;
    };

    struct Symbol {
        std::string name;
        friend bool operator==(const Symbol&, const Symbol&) = default;
    };

    struct StringChunk {
        std::shared_ptr<Datum> source;
        StringChunkType chunkType;
        int start;
        int end;
        char itemDelimiter;
        std::string value;
    };

    struct CastMemberRef {
        int castLib;
        int castMember;

        [[nodiscard]] static CastMemberRef of(id::CastLibId castLib, id::MemberId member);
        [[nodiscard]] std::optional<id::CastLibId> castLibId() const;
        [[nodiscard]] std::optional<id::MemberId> memberId() const;
        [[nodiscard]] int memberNum() const;

        friend bool operator==(const CastMemberRef&, const CastMemberRef&) = default;
    };

    struct CastLibRef {
        int castLib;

        [[nodiscard]] static CastLibRef of(id::CastLibId castLib);
        [[nodiscard]] id::CastLibId castLibId() const;

        friend bool operator==(const CastLibRef&, const CastLibRef&) = default;
    };

    struct SpriteRef {
        int channel;

        [[nodiscard]] static SpriteRef of(id::ChannelId channel);
        [[nodiscard]] id::ChannelId channelId() const;
        [[nodiscard]] int spriteNum() const;

        friend bool operator==(const SpriteRef&, const SpriteRef&) = default;
    };

    struct StageRef {
        friend bool operator==(const StageRef&, const StageRef&) = default;
    };

    struct ScriptRef {
        CastMemberRef memberRef;
        friend bool operator==(const ScriptRef&, const ScriptRef&) = default;
    };

    struct Stage {
        friend bool operator==(const Stage&, const Stage&) = default;
    };

    struct PlayerRef {
        friend bool operator==(const PlayerRef&, const PlayerRef&) = default;
    };

    struct MovieRef {
        friend bool operator==(const MovieRef&, const MovieRef&) = default;
    };

    struct IntPoint {
        int x;
        int y;
        friend bool operator==(const IntPoint&, const IntPoint&) = default;
    };

    struct IntRect {
        int left;
        int top;
        int right;
        int bottom;

        [[nodiscard]] int width() const;
        [[nodiscard]] int height() const;

        friend bool operator==(const IntRect&, const IntRect&) = default;
    };

    struct Vector3 {
        float x;
        float y;
        float z;
        friend bool operator==(const Vector3&, const Vector3&) = default;
    };

    struct ColorRef {
        int r;
        int g;
        int b;

        [[nodiscard]] static ColorRef fromPaletteIndex(int index);
        [[nodiscard]] static ColorRef fromRgb(int r, int g, int b);

        friend bool operator==(const ColorRef&, const ColorRef&) = default;
    };

    struct BitmapRef {
        int bitmapId;
        friend bool operator==(const BitmapRef&, const BitmapRef&) = default;
    };

    struct PaletteRef {
        int paletteId;
        friend bool operator==(const PaletteRef&, const PaletteRef&) = default;
    };

    struct Matte {
        friend bool operator==(const Matte&, const Matte&) = default;
    };

    struct SoundRef {
        int soundNum;
        friend bool operator==(const SoundRef&, const SoundRef&) = default;
    };

    struct SoundChannel {
        int channel;
        friend bool operator==(const SoundChannel&, const SoundChannel&) = default;
    };

    struct CursorRef {
        int cursorId;
        friend bool operator==(const CursorRef&, const CursorRef&) = default;
    };

    struct TimeoutRef {
        std::string name;
        friend bool operator==(const TimeoutRef&, const TimeoutRef&) = default;
    };

    struct Xtra {
        std::string name;
        friend bool operator==(const Xtra&, const Xtra&) = default;
    };

    struct XtraInstance {
        std::string xtraName;
        int instanceId;
        friend bool operator==(const XtraInstance&, const XtraInstance&) = default;
    };

    struct XmlRef {
        int xmlId;
        friend bool operator==(const XmlRef&, const XmlRef&) = default;
    };

    struct DateRef {
        int dateId;
        friend bool operator==(const DateRef&, const DateRef&) = default;
    };

    struct MathRef {
        int mathId;
        friend bool operator==(const MathRef&, const MathRef&) = default;
    };

    struct VarRef {
        std::string varName;
        friend bool operator==(const VarRef&, const VarRef&) = default;
    };

    Datum();

    [[nodiscard]] static Datum nullValue();
    [[nodiscard]] static Datum voidValue();
    [[nodiscard]] static Datum of(int value);
    [[nodiscard]] static Datum of(float value);
    [[nodiscard]] static Datum of(double value);
    [[nodiscard]] static Datum of(std::string value);
    [[nodiscard]] static Datum symbol(std::string name);
    [[nodiscard]] static Datum list(std::vector<Datum> items = {}, bool sorted = false);
    [[nodiscard]] static Datum propList(bool sorted = false);
    [[nodiscard]] static Datum stringChunk(Datum source, StringChunkType chunkType, int start, int end, char itemDelimiter, std::string value);
    [[nodiscard]] static Datum castMemberRef(CastMemberRef ref);
    [[nodiscard]] static Datum castMemberRef(id::CastLibId castLib, id::MemberId member);
    [[nodiscard]] static Datum castLibRef(id::CastLibId castLib);
    [[nodiscard]] static Datum spriteRef(id::ChannelId channel);
    [[nodiscard]] static Datum stageRef();
    [[nodiscard]] static Datum scriptRef(CastMemberRef memberRef);
    [[nodiscard]] static Datum playerRef();
    [[nodiscard]] static Datum movieRef();
    [[nodiscard]] static Datum intPoint(int x, int y);
    [[nodiscard]] static Datum intRect(int left, int top, int right, int bottom);
    [[nodiscard]] static Datum vector3(float x, float y, float z);
    [[nodiscard]] static Datum colorRef(int r, int g, int b);
    [[nodiscard]] static Datum scriptInstance(std::string scriptName, std::optional<CastMemberRef> scriptRef = std::nullopt);
    [[nodiscard]] static Datum argList(std::vector<Datum> args);
    [[nodiscard]] static Datum argListNoRet(std::vector<Datum> args);
    [[nodiscard]] static Datum timeoutRef(std::string name);

    [[nodiscard]] DatumType type() const;
    [[nodiscard]] std::string typeString() const;

    [[nodiscard]] bool isNull() const;
    [[nodiscard]] bool isVoid() const;
    [[nodiscard]] bool isInt() const;
    [[nodiscard]] bool isFloat() const;
    [[nodiscard]] bool isNumber() const;
    [[nodiscard]] bool isString() const;
    [[nodiscard]] bool isSymbol() const;
    [[nodiscard]] bool isList() const;
    [[nodiscard]] bool isPropList() const;

    [[nodiscard]] int intValue() const;
    [[nodiscard]] float floatValue() const;
    [[nodiscard]] std::string stringValue() const;
    [[nodiscard]] bool boolValue() const;

    [[nodiscard]] const Int* asInt() const;
    [[nodiscard]] const DFloat* asFloat() const;
    [[nodiscard]] const Str* asString() const;
    [[nodiscard]] const Symbol* asSymbol() const;
    [[nodiscard]] const CastMemberRef* asCastMemberRef() const;
    [[nodiscard]] const ColorRef* asColorRef() const;
    [[nodiscard]] const TimeoutRef* asTimeoutRef() const;
    [[nodiscard]] const IntPoint* asIntPoint() const;
    [[nodiscard]] const IntRect* asIntRect() const;

    [[nodiscard]] List& listValue();
    [[nodiscard]] const List& listValue() const;
    [[nodiscard]] PropList& propListValue();
    [[nodiscard]] const PropList& propListValue() const;
    [[nodiscard]] ScriptInstanceRef& scriptInstanceValue();
    [[nodiscard]] const ScriptInstanceRef& scriptInstanceValue() const;
    [[nodiscard]] const ArgList& argListValue() const;
    [[nodiscard]] const ArgListNoRet& argListNoRetValue() const;

    friend bool operator==(const Datum& lhs, const Datum& rhs);
    friend bool operator!=(const Datum& lhs, const Datum& rhs) { return !(lhs == rhs); }

    static const Datum TRUE;
    static const Datum FALSE;

private:
    using ListPtr = std::shared_ptr<List>;
    using PropListPtr = std::shared_ptr<PropList>;
    using ScriptInstancePtr = std::shared_ptr<ScriptInstanceRef>;
    using ArgListPtr = std::shared_ptr<ArgList>;
    using ArgListNoRetPtr = std::shared_ptr<ArgListNoRet>;
    using Value = std::variant<
        Null,
        Void,
        Int,
        DFloat,
        Str,
        Symbol,
        ListPtr,
        PropListPtr,
        StringChunk,
        CastMemberRef,
        CastLibRef,
        SpriteRef,
        StageRef,
        ScriptRef,
        ScriptInstancePtr,
        Stage,
        PlayerRef,
        MovieRef,
        IntPoint,
        IntRect,
        Vector3,
        ColorRef,
        BitmapRef,
        PaletteRef,
        Matte,
        SoundRef,
        SoundChannel,
        CursorRef,
        TimeoutRef,
        Xtra,
        XtraInstance,
        XmlRef,
        DateRef,
        MathRef,
        VarRef,
        ArgListPtr,
        ArgListNoRetPtr>;

    explicit Datum(Value value);

    Value value_;
};

class Datum::List {
public:
    explicit List(std::vector<Datum> items = {}, bool sorted = false);

    void add(Datum item);
    [[nodiscard]] Datum getAt(int index) const;
    void setAt(int index, Datum value);
    [[nodiscard]] int count() const;
    [[nodiscard]] bool sorted() const;
    [[nodiscard]] const std::vector<Datum>& items() const;
    [[nodiscard]] std::vector<Datum>& items();

    friend bool operator==(const List& lhs, const List& rhs);

private:
    std::vector<Datum> items_;
    bool sorted_;
};

class Datum::PropList {
public:
    explicit PropList(bool sorted = false);

    void put(Datum key, Datum value);
    [[nodiscard]] Datum get(const Datum& key) const;
    [[nodiscard]] bool contains(const Datum& key) const;
    [[nodiscard]] int count() const;
    [[nodiscard]] bool sorted() const;
    [[nodiscard]] const std::vector<std::pair<Datum, Datum>>& properties() const;

    friend bool operator==(const PropList& lhs, const PropList& rhs);

private:
    std::vector<std::pair<Datum, Datum>> properties_;
    bool sorted_;
};

class Datum::ScriptInstanceRef {
public:
    ScriptInstanceRef(std::string scriptName, std::optional<CastMemberRef> scriptRef = std::nullopt);

    [[nodiscard]] const std::string& scriptName() const;
    [[nodiscard]] const std::optional<CastMemberRef>& scriptRef() const;
    [[nodiscard]] std::shared_ptr<ScriptInstanceRef> ancestor() const;
    void setAncestor(std::shared_ptr<ScriptInstanceRef> ancestor);

    [[nodiscard]] Datum getProperty(const std::string& name) const;
    void setProperty(const std::string& name, Datum value);
    [[nodiscard]] bool hasProperty(const std::string& name) const;
    [[nodiscard]] const std::vector<std::pair<std::string, Datum>>& properties() const;

private:
    std::string scriptName_;
    std::optional<CastMemberRef> scriptRef_;
    std::vector<std::pair<std::string, Datum>> properties_;
    std::shared_ptr<ScriptInstanceRef> ancestor_;
};

class Datum::ArgList {
public:
    explicit ArgList(std::vector<Datum> args);
    [[nodiscard]] const std::vector<Datum>& args() const;

    friend bool operator==(const ArgList& lhs, const ArgList& rhs);

private:
    std::vector<Datum> args_;
};

class Datum::ArgListNoRet {
public:
    explicit ArgListNoRet(std::vector<Datum> args);
    [[nodiscard]] const std::vector<Datum>& args() const;

    friend bool operator==(const ArgListNoRet& lhs, const ArgListNoRet& rhs);

private:
    std::vector<Datum> args_;
};

} // namespace libreshockwave::lingo
