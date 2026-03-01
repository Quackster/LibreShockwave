package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Window and stage builtin functions for Lingo.
 *
 * Provides:
 * - moveToFront(window) - bring window to front
 * - moveToBack(window) - send window to back
 * - puppetTempo(rate) - set the tempo programmatically
 */
public final class WindowBuiltins {

    private WindowBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("movetofront", WindowBuiltins::moveToFront);
        builtins.put("movetoback", WindowBuiltins::moveToBack);
        builtins.put("puppettempo", WindowBuiltins::puppetTempo);
        builtins.put("puppetsprite", WindowBuiltins::puppetSprite);
        builtins.put("cursor", (vm, args) -> Datum.VOID);  // No-op stub
        builtins.put("pauseupdate", (vm, args) -> Datum.VOID);  // No-op stub
        builtins.put("updatestage", (vm, args) -> Datum.VOID);  // No-op stub
    }

    /**
     * moveToFront(window)
     * Brings the specified window to the front of the window stack.
     * Commonly used with "the stage".
     */
    private static Datum moveToFront(LingoVM vm, List<Datum> args) {
        // No-op - windowing is handled externally
        return Datum.VOID;
    }

    /**
     * moveToBack(window)
     * Sends the specified window to the back of the window stack.
     */
    private static Datum moveToBack(LingoVM vm, List<Datum> args) {
        // No-op - windowing is handled externally
        return Datum.VOID;
    }

    /**
     * puppetSprite(spriteNum, enabled)
     * Takes programmatic control of a sprite channel.
     * When puppet is TRUE, the sprite is controlled by script instead of the score.
     */
    private static Datum puppetSprite(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) {
            return Datum.VOID;
        }

        int spriteNum = args.get(0).toInt();
        boolean enabled = args.get(1).isTruthy();

        SpritePropertyProvider provider = SpritePropertyProvider.getProvider();
        if (provider != null) {
            provider.setSpriteProp(spriteNum, "puppet", Datum.of(enabled ? 1 : 0));
        }

        return Datum.VOID;
    }

    /**
     * puppetTempo(rate)
     * Sets the tempo of the movie programmatically.
     * The tempo remains in effect until another puppetTempo call or until
     * the movie encounters a tempo setting in the score.
     * Set to 0 to return to score tempo.
     */
    private static Datum puppetTempo(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        int tempo = args.get(0).toInt();

        // Set the puppetTempo property which overrides the score tempo
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider != null) {
            provider.setMovieProp("puppetTempo", Datum.of(tempo));
        }

        return Datum.VOID;
    }
}
