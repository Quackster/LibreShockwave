package com.libreshockwave.cast;

/**
 * Types of cast members in Director.
 */
public enum MemberType {
    NULL(0, "null"),
    BITMAP(1, "bitmap"),
    FILM_LOOP(2, "filmLoop"),
    TEXT(3, "text"),
    PALETTE(4, "palette"),
    PICTURE(5, "picture"),
    SOUND(6, "sound"),
    BUTTON(7, "button"),
    SHAPE(8, "shape"),
    MOVIE(9, "movie"),
    DIGITAL_VIDEO(10, "digitalVideo"),
    SCRIPT(11, "script"),
    RICH_TEXT(12, "richText"),
    TRANSITION(14, "transition"),
    XTRA(15, "xtra"),
    FONT(16, "font"),
    SHOCKWAVE_3D(17, "shockwave3d"),
    UNKNOWN(255, "unknown");

    private final int code;
    private final String name;

    MemberType(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static MemberType fromCode(int code) {
        for (MemberType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return name;
    }
}
