package com.libreshockwave.cast;

/**
 * Interface for cast member info objects that have dimensions and registration points.
 *
 * Refactoring decision: Extracts common dimension methods from BitmapInfo, ShapeInfo,
 * and FilmLoopInfo to eliminate duplicate getWidth/getHeight/getRegX/getRegY patterns
 * in CastMember.java (4 methods x 3 checks each = 12 lines reduced to 4 lines).
 */
public interface Dimensioned {
    int width();
    int height();
    int regX();
    int regY();
}
