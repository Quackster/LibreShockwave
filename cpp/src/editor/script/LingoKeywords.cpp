#include "libreshockwave/editor/script/LingoKeywords.hpp"

namespace libreshockwave::editor::script {
namespace {

bool contains(const std::unordered_set<std::string>& values, std::string_view value) {
    return values.find(std::string(value)) != values.end();
}

} // namespace

const std::unordered_set<std::string>& LingoKeywords::keywords() {
    static const std::unordered_set<std::string> values{
        "on", "end", "if", "then", "else", "end if",
        "repeat", "while", "with", "in", "down to", "end repeat",
        "case", "of", "otherwise", "end case",
        "return", "exit", "exit repeat", "next repeat",
        "global", "property", "me",
        "new", "script", "ancestor",
        "TRUE", "FALSE", "VOID", "EMPTY",
        "the", "of", "to", "into", "after", "before",
        "and", "or", "not",
        "contains", "starts",
        "set", "put"
    };
    return values;
}

const std::unordered_set<std::string>& LingoKeywords::commands() {
    static const std::unordered_set<std::string> values{
        "put", "set", "go", "play", "halt", "abort", "pass",
        "alert", "beep", "cursor", "delay",
        "do", "nothing", "tell", "sound",
        "updateStage", "puppetSprite", "puppetTempo", "puppetTransition",
        "startTimer", "preLoad", "preLoadMember",
        "open", "close", "importFileInto"
    };
    return values;
}

const std::unordered_set<std::string>& LingoKeywords::functions() {
    static const std::unordered_set<std::string> values{
        "abs", "atan", "cos", "exp", "float", "integer", "log",
        "pi", "power", "random", "sin", "sqrt", "tan",
        "string", "value", "symbol", "chars", "char", "length",
        "offset", "charToNum", "numToChar",
        "count", "getAt", "setAt", "addAt", "deleteAt", "append",
        "getaProp", "setaProp", "addProp", "deleteProp",
        "point", "rect", "rgb", "color",
        "member", "sprite", "cast", "castLib",
        "the mouseH", "the mouseV", "the clickOn",
        "the frame", "the movie", "the stage"
    };
    return values;
}

const std::unordered_set<std::string>& LingoKeywords::events() {
    static const std::unordered_set<std::string> values{
        "mouseDown", "mouseUp", "mouseEnter", "mouseLeave", "mouseWithin",
        "mouseUpOutside", "rightMouseDown", "rightMouseUp",
        "keyDown", "keyUp",
        "prepareMovie", "startMovie", "stopMovie",
        "prepareFrame", "enterFrame", "exitFrame",
        "beginSprite", "endSprite",
        "stepFrame", "idle",
        "activateWindow", "deactivateWindow",
        "openWindow", "closeWindow",
        "timeout"
    };
    return values;
}

bool LingoKeywords::isKeyword(std::string_view value) {
    return contains(keywords(), value);
}

bool LingoKeywords::isCommand(std::string_view value) {
    return contains(commands(), value);
}

bool LingoKeywords::isFunction(std::string_view value) {
    return contains(functions(), value);
}

bool LingoKeywords::isEvent(std::string_view value) {
    return contains(events(), value);
}

} // namespace libreshockwave::editor::script
