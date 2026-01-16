package com.libreshockwave.net;

/**
 * Result of a network task.
 * Matches dirplayer-rs NetResult = Result<Vec<u8>, i32>.
 */
public sealed interface NetResult {
    record Success(byte[] data) implements NetResult {}
    record Error(int errorCode) implements NetResult {}

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default boolean isError() {
        return this instanceof Error;
    }

    default byte[] getData() {
        return this instanceof Success s ? s.data() : new byte[0];
    }

    default int getErrorCode() {
        return this instanceof Error e ? e.errorCode() : 0;
    }
}
