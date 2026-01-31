package com.libreshockwave.player.score;

import com.libreshockwave.vm.Datum;

import java.util.List;

/**
 * Reference to a behavior script attached to a frame or sprite.
 * Contains cast member reference and saved behavior parameters.
 */
public record ScoreBehaviorRef(
    int castLib,
    int castMember,
    List<Datum> parameters
) {
    public ScoreBehaviorRef(int castLib, int castMember) {
        this(castLib, castMember, List.of());
    }

    public boolean hasParameters() {
        return !parameters.isEmpty();
    }

    @Override
    public String toString() {
        return "behavior(member " + castMember + ", castLib " + castLib + ")";
    }
}
