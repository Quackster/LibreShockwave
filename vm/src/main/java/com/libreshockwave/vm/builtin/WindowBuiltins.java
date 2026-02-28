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
