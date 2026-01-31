package com.libreshockwave.tools.model;

/**
 * Data for a cell in the score table.
 */
public record ScoreCellData(
        int castLib,
        int castMember,
        int spriteType,
        int ink,
        int posX,
        int posY,
        int width,
        int height,
        String memberName
) {
    @Override
    public String toString() {
        return memberName;
    }
}
