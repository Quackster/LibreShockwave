package com.libreshockwave.player;

/**
 * Information about a player event for external listeners.
 */
public record PlayerEventInfo(PlayerEvent event, int frame, int data) {}
