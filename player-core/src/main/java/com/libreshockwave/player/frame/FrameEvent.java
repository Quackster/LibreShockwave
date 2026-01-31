package com.libreshockwave.player.frame;

import com.libreshockwave.player.PlayerEvent;

/**
 * Event notification for frame context changes.
 */
public record FrameEvent(PlayerEvent event, int frame) {}
