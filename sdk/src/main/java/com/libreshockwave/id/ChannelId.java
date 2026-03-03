package com.libreshockwave.id;

/**
 * Sprite channel number. 0 = frame script channel, 1+ = visible sprite channels.
 */
public record ChannelId(int value) implements TypedId, Comparable<ChannelId> {

    public ChannelId {
        if (value < 0) {
            throw new IllegalArgumentException("ChannelId must be >= 0, got " + value);
        }
    }

    @Override
    public int compareTo(ChannelId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "ChannelId(" + value + ")";
    }
}
