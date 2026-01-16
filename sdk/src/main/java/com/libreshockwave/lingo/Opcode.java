package com.libreshockwave.lingo;

import java.util.HashMap;
import java.util.Map;

/**
 * Lingo bytecode opcodes.
 * Single-byte opcodes (0x00-0x3F) take no arguments.
 * Multi-byte opcodes (0x40+) have variable-length arguments.
 */
public enum Opcode {

    // Single-byte opcodes (no arguments)
    INVALID(0x00, "invalid", 0),
    RET(0x01, "ret", 0),
    RET_FACTORY(0x02, "retFactory", 0),
    PUSH_ZERO(0x03, "pushZero", 0),
    MUL(0x04, "mul", 0),
    ADD(0x05, "add", 0),
    SUB(0x06, "sub", 0),
    DIV(0x07, "div", 0),
    MOD(0x08, "mod", 0),
    INV(0x09, "inv", 0),
    JOIN_STR(0x0A, "joinStr", 0),
    JOIN_PAD_STR(0x0B, "joinPadStr", 0),
    LT(0x0C, "lt", 0),
    LT_EQ(0x0D, "ltEq", 0),
    NT_EQ(0x0E, "ntEq", 0),
    EQ(0x0F, "eq", 0),
    GT(0x10, "gt", 0),
    GT_EQ(0x11, "gtEq", 0),
    AND(0x12, "and", 0),
    OR(0x13, "or", 0),
    NOT(0x14, "not", 0),
    CONTAINS_STR(0x15, "containsStr", 0),
    CONTAINS_0_STR(0x16, "contains0Str", 0),
    GET_CHUNK(0x17, "getChunk", 0),
    HILITE_CHUNK(0x18, "hiliteChunk", 0),
    ONTO_SPR(0x19, "ontoSpr", 0),
    INTO_SPR(0x1A, "intoSpr", 0),
    GET_FIELD(0x1B, "getField", 0),
    START_TELL(0x1C, "startTell", 0),
    END_TELL(0x1D, "endTell", 0),
    PUSH_LIST(0x1E, "pushList", 0),
    PUSH_PROP_LIST(0x1F, "pushPropList", 0),
    SWAP(0x21, "swap", 0),
    CALL_JAVASCRIPT(0x26, "callJavaScript", 0),

    // Multi-byte opcodes (with arguments)
    PUSH_INT8(0x41, "pushInt8", 1),
    PUSH_ARG_LIST_NO_RET(0x42, "pushArgListNoRet", 1),
    PUSH_ARG_LIST(0x43, "pushArgList", 1),
    PUSH_CONS(0x44, "pushCons", 1),
    PUSH_SYMB(0x45, "pushSymb", 1),
    PUSH_VAR_REF(0x46, "pushVarRef", 1),
    GET_GLOBAL2(0x48, "getGlobal2", 1),
    GET_GLOBAL(0x49, "getGlobal", 1),
    GET_PROP(0x4A, "getProp", 1),
    GET_PARAM(0x4B, "getParam", 1),
    GET_LOCAL(0x4C, "getLocal", 1),
    SET_GLOBAL2(0x4E, "setGlobal2", 1),
    SET_GLOBAL(0x4F, "setGlobal", 1),
    SET_PROP(0x50, "setProp", 1),
    SET_PARAM(0x51, "setParam", 1),
    SET_LOCAL(0x52, "setLocal", 1),
    JMP(0x53, "jmp", 1),
    END_REPEAT(0x54, "endRepeat", 1),
    JMP_IF_Z(0x55, "jmpIfZ", 1),
    LOCAL_CALL(0x56, "localCall", 1),
    EXT_CALL(0x57, "extCall", 1),
    OBJ_CALL_V4(0x58, "objCallV4", 1),
    PUT(0x59, "put", 1),
    PUT_CHUNK(0x5A, "putChunk", 1),
    DELETE_CHUNK(0x5B, "deleteChunk", 1),
    GET(0x5C, "get", 1),
    SET(0x5D, "set", 1),
    GET_MOVIE_PROP(0x5F, "getMovieProp", 1),
    SET_MOVIE_PROP(0x60, "setMovieProp", 1),
    GET_OBJ_PROP(0x61, "getObjProp", 1),
    SET_OBJ_PROP(0x62, "setObjProp", 1),
    TELL_CALL(0x63, "tellCall", 1),
    PEEK(0x64, "peek", 1),
    POP(0x65, "pop", 1),
    THE_BUILTIN(0x66, "theBuiltin", 1),
    OBJ_CALL(0x67, "objCall", 1),
    PUSH_CHUNK_VAR_REF(0x6D, "pushChunkVarRef", 1),
    PUSH_INT16(0x6E, "pushInt16", 2),
    PUSH_INT32(0x6F, "pushInt32", 4),
    GET_CHAINED_PROP(0x70, "getChainedProp", 1),
    PUSH_FLOAT32(0x71, "pushFloat32", 4),
    GET_TOP_LEVEL_PROP(0x72, "getTopLevelProp", 1),
    NEW_OBJ(0x73, "newObj", 1);

    private static final Map<Integer, Opcode> BY_CODE = new HashMap<>();

    static {
        for (Opcode op : values()) {
            BY_CODE.put(op.code, op);
        }
    }

    private final int code;
    private final String mnemonic;
    private final int argBytes;

    Opcode(int code, String mnemonic, int argBytes) {
        this.code = code;
        this.mnemonic = mnemonic;
        this.argBytes = argBytes;
    }

    public int getCode() {
        return code;
    }

    public String getMnemonic() {
        return mnemonic;
    }

    public int getArgBytes() {
        return argBytes;
    }

    public boolean isSingleByte() {
        return code < 0x40;
    }

    public boolean isMultiByte() {
        return code >= 0x40;
    }

    public boolean isJump() {
        return this == JMP || this == JMP_IF_Z || this == END_REPEAT;
    }

    public boolean isCall() {
        return this == LOCAL_CALL || this == EXT_CALL || this == OBJ_CALL || this == OBJ_CALL_V4 || this == TELL_CALL;
    }

    public boolean isReturn() {
        return this == RET || this == RET_FACTORY;
    }

    public boolean isPush() {
        return switch (this) {
            case PUSH_ZERO, PUSH_INT8, PUSH_INT16, PUSH_INT32, PUSH_FLOAT32,
                 PUSH_CONS, PUSH_SYMB, PUSH_VAR_REF, PUSH_CHUNK_VAR_REF,
                 PUSH_LIST, PUSH_PROP_LIST, PUSH_ARG_LIST, PUSH_ARG_LIST_NO_RET -> true;
            default -> false;
        };
    }

    public static Opcode fromCode(int code) {
        Opcode op = BY_CODE.get(code);
        if (op == null) {
            return INVALID;
        }
        return op;
    }

    @Override
    public String toString() {
        return mnemonic;
    }
}
