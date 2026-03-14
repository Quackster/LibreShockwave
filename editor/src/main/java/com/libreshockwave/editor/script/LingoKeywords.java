package com.libreshockwave.editor.script;

import java.util.Set;

/**
 * Lingo language keyword, command, and function lists for syntax highlighting.
 */
public class LingoKeywords {

    public static final Set<String> KEYWORDS = Set.of(
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
    );

    public static final Set<String> COMMANDS = Set.of(
        "put", "set", "go", "play", "halt", "abort", "pass",
        "alert", "beep", "cursor", "delay",
        "do", "nothing", "tell", "sound",
        "updateStage", "puppetSprite", "puppetTempo", "puppetTransition",
        "startTimer", "preLoad", "preLoadMember",
        "open", "close", "importFileInto"
    );

    public static final Set<String> FUNCTIONS = Set.of(
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
    );

    public static final Set<String> EVENTS = Set.of(
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
    );
}
