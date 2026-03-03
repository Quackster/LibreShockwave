package com.libreshockwave.id;

/**
 * Marker interface for type-safe ID wrappers.
 * All ID records implement this to provide a common {@code value()} accessor.
 */
public interface TypedId {
    int value();
}
