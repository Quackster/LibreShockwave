package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Sound-related builtin functions for Lingo.
 *
 * Provides:
 * - sound(channelNum) - returns a SoundChannel reference
 * - soundEnabled() - returns whether sound is available
 */
public final class SoundBuiltins {

    /** Director supports 8 sound channels by default */
    public static final int MAX_SOUND_CHANNELS = 8;

    private SoundBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("sound", SoundBuiltins::sound);
        builtins.put("soundenabled", (vm, args) -> Datum.TRUE);
    }

    /**
     * sound(channelNum)
     * Returns a SoundChannel reference for the given channel number.
     * Director supports channels 1-8. Returns VOID for invalid channels.
     */
    private static Datum sound(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        int channelNum = args.get(0).toInt();
        if (channelNum < 1 || channelNum > MAX_SOUND_CHANNELS) {
            return Datum.VOID;
        }
        return new Datum.SoundChannel(channelNum);
    }
}
