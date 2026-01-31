package com.libreshockwave.player.render;

import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.sprite.SpriteState;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of runtime sprite states.
 * Tracks sprite properties that can be modified by scripts (position, visibility, etc.).
 */
public class SpriteRegistry {

    private final Map<Integer, SpriteState> sprites = new HashMap<>();

    /**
     * Get or create a sprite state for a channel.
     * If the sprite doesn't exist, creates it from the channel data.
     */
    public SpriteState getOrCreate(int channel, ScoreChunk.ChannelData data) {
        SpriteState state = sprites.get(channel);
        if (state == null) {
            state = new SpriteState(channel, data);
            sprites.put(channel, state);
        }
        return state;
    }

    /**
     * Get a sprite state by channel, or null if not registered.
     */
    public SpriteState get(int channel) {
        return sprites.get(channel);
    }

    /**
     * Update a sprite's position from score data (for new frames).
     */
    public void updateFromScore(int channel, ScoreChunk.ChannelData data) {
        SpriteState state = sprites.get(channel);
        if (state != null) {
            // Update from score data while preserving script modifications
            // Note: In a full implementation, we'd track which properties were
            // modified by scripts vs. which should follow the score
            state.setLocH(data.posX());
            state.setLocV(data.posY());
        }
    }

    /**
     * Remove a sprite when it leaves the stage.
     */
    public void remove(int channel) {
        sprites.remove(channel);
    }

    /**
     * Clear all sprites (on movie stop/reset).
     */
    public void clear() {
        sprites.clear();
    }

    /**
     * Check if a channel has a registered sprite.
     */
    public boolean contains(int channel) {
        return sprites.containsKey(channel);
    }
}
