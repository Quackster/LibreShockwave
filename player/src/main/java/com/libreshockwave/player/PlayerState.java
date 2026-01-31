package com.libreshockwave.player;

/**
 * Playback state of the Director player.
 */
public enum PlayerState {
    /** Player is stopped, no movie loaded */
    STOPPED,
    /** Player is paused, movie loaded but not advancing */
    PAUSED,
    /** Player is playing, advancing frames */
    PLAYING
}
