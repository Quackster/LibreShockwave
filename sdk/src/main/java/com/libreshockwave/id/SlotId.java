package com.libreshockwave.id;

/**
 * Encoded slot number: {@code (castLib << 16) | memberNum}.
 * This is the format used by Director's member.number property.
 */
public record SlotId(int value) implements TypedId, Comparable<SlotId> {

    /**
     * Encode from raw cast library number and member number.
     */
    public static SlotId of(int castLib, int member) {
        return new SlotId((castLib << 16) | (member & 0xFFFF));
    }

    /**
     * Encode from typed IDs.
     */
    public static SlotId of(CastLibId castLib, MemberId member) {
        return of(castLib.value(), member.value());
    }

    /**
     * Decode the raw cast library number.
     */
    public int castLib() {
        return value >> 16;
    }

    /**
     * Decode the raw member number.
     */
    public int member() {
        return value & 0xFFFF;
    }

    /**
     * Decode to a typed CastLibId.
     */
    public CastLibId castLibId() {
        return new CastLibId(castLib());
    }

    /**
     * Decode to a typed MemberId.
     */
    public MemberId memberId() {
        return new MemberId(member());
    }

    @Override
    public int compareTo(SlotId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "SlotId(" + value + " = castLib " + castLib() + ", member " + member() + ")";
    }
}
