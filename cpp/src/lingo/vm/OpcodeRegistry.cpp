#include "libreshockwave/lingo/vm/OpcodeRegistry.hpp"

#include <algorithm>
#include <bit>
#include <charconv>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <optional>
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

} // namespace

OpcodeRegistry::OpcodeRegistry() {
    StackOpcodes::registerHandlers(*this);
    ArithmeticOpcodes::registerHandlers(*this);
    ComparisonOpcodes::registerHandlers(*this);
    LogicalOpcodes::registerHandlers(*this);
    ControlFlowOpcodes::registerHandlers(*this);
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

} // namespace libreshockwave::lingo::vm
