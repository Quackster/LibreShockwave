package com.libreshockwave.editor.selection;

/**
 * Describes a selection change in the editor.
 */
public record SelectionEvent(
    SelectionType type,
    int channel,
    int frame,
    int castLib,
    int memberNum
) {
    public enum SelectionType {
        NONE,
        SPRITE,
        CAST_MEMBER,
        FRAME,
        SCORE_CELL
    }

    public static SelectionEvent none() {
        return new SelectionEvent(SelectionType.NONE, 0, 0, 0, 0);
    }

    public static SelectionEvent sprite(int channel, int frame) {
        return new SelectionEvent(SelectionType.SPRITE, channel, frame, 0, 0);
    }

    public static SelectionEvent castMember(int castLib, int memberNum) {
        return new SelectionEvent(SelectionType.CAST_MEMBER, 0, 0, castLib, memberNum);
    }

    public static SelectionEvent frame(int frame) {
        return new SelectionEvent(SelectionType.FRAME, 0, frame, 0, 0);
    }
}
