package com.libreshockwave.lingo;

/**
 * Enumeration of all Lingo datum types.
 */
public enum DatumType {
    NULL("null"),
    VOID("void"),
    INT("int"),
    FLOAT("float"),
    STRING("string"),
    STRING_CHUNK("string_chunk"),
    SYMBOL("symbol"),
    VAR_REF("var_ref"),
    LIST("list"),
    PROP_LIST("prop_list"),
    ARG_LIST("arg_list"),
    ARG_LIST_NO_RET("arg_list_no_ret"),

    // References
    CAST_LIB_REF("cast_lib"),
    CAST_MEMBER_REF("cast_member"),
    SCRIPT_REF("script_ref"),
    SCRIPT_INSTANCE_REF("script_instance"),
    SPRITE_REF("sprite_ref"),
    STAGE_REF("stage"),
    PLAYER_REF("player_ref"),
    MOVIE_REF("movie_ref"),

    // Geometry
    INT_POINT("point"),
    INT_RECT("rect"),
    VECTOR("vector"),

    // Graphics
    COLOR_REF("color_ref"),
    BITMAP_REF("bitmap_ref"),
    PALETTE_REF("palette_ref"),
    MATTE("matte"),

    // Sound
    SOUND_REF("sound"),
    SOUND_CHANNEL("sound_channel"),

    // Other
    CURSOR_REF("cursor_ref"),
    TIMEOUT_REF("timeout"),
    XTRA("xtra"),
    XTRA_INSTANCE("xtra_instance"),
    XML_REF("xml"),
    DATE_REF("date"),
    MATH_REF("math");

    private final String typeName;

    DatumType(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    @Override
    public String toString() {
        return typeName;
    }
}
