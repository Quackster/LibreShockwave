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
    FLASH(8, "flash"),
    SHAPE(9, "shape"),
    DIGITAL_VIDEO(10, "digitalVideo"),
    SCRIPT(11, "script"),
    RTE(12, "rte"),
    TRANSITION(13, "transition"),
    XTRA(14, "xtra"),
    OLE(15, "ole"),
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
