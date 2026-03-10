package com.libreshockwave.player.score;

import com.libreshockwave.id.CastLibId;
import com.libreshockwave.id.MemberId;
import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Reference to a behavior script attached to a frame or sprite.
 * Contains cast member reference and saved behavior parameters.
 */
public record ScoreBehaviorRef(
    CastLibId castLibId,
    MemberId memberId,
    List<Datum> parameters
) {
    public ScoreBehaviorRef(int castLib, int castMember) {
        this(new CastLibId(castLib), new MemberId(castMember), List.of());
    }

    public ScoreBehaviorRef(int castLib, int castMember, List<Datum> parameters) {
        this(new CastLibId(castLib), new MemberId(castMember), parameters);
    }

    public int castLib() {
        return castLibId.value();
    }

    public int castMember() {
        return memberId.value();
    }

    public boolean hasParameters() {
        return !parameters.isEmpty();
    }

    @Override
    public String toString() {
        return "behavior(member " + memberId.value() + ", castLib " + castLibId.value() + ")";
    }
}
