package com.libreshockwave.id;

/**
 * Director ink modes for sprite blending/compositing.
 */
public enum InkMode {
    COPY(0),
    TRANSPARENT(1),
    REVERSE(2),
    GHOST(3),
    NOT_COPY(4),
    NOT_TRANSPARENT(5),
    NOT_REVERSE(6),
    NOT_GHOST(7),
    MATTE(8),
    MASK(9),
    BLEND(32),
    ADD_PIN(33),
    ADD(34),
    SUBTRACT_PIN(35),
    BACKGROUND_TRANSPARENT(36),
    LIGHTEST(37),
    SUBTRACT(38),
    DARKEST(39),
    LIGHTEN(40),
    DARKEN(41);

    private final int code;

    InkMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean usesBlend() {
        return this == BLEND || this == ADD_PIN || this == ADD ||
               this == SUBTRACT_PIN || this == SUBTRACT ||
               this == LIGHTEST || this == DARKEST ||
               this == LIGHTEN || this == DARKEN;
    }

    public static InkMode fromCode(int code) {
        for (InkMode mode : values()) {
            if (mode.code == code) return mode;
        }
        return COPY;
    }
}
