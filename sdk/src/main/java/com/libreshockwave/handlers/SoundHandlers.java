package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.SoundChannel;
import com.libreshockwave.player.SoundMember;
import com.libreshockwave.vm.LingoVM;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.libreshockwave.handlers.HandlerArgs.*;

/**
 * Built-in sound handlers for Lingo.
 * Refactored: Uses HandlerArgs for argument extraction, reducing boilerplate.
 */
public class SoundHandlers {

    private static final Map<Integer, SoundChannel> channels = new HashMap<>();
    private static final Map<Integer, SoundMember> soundMembers = new HashMap<>();

    static {
        // Initialize sound channels 1-8
        for (int i = 1; i <= SoundChannel.MAX_CHANNELS; i++) {
            channels.put(i, new SoundChannel(i));
        }
    }

    public static void register(LingoVM vm) {
        // Sound channel access
        vm.registerBuiltin("sound", SoundHandlers::sound);

        // Playback control
        vm.registerBuiltin("puppetSound", SoundHandlers::puppetSound);
        vm.registerBuiltin("playSound", SoundHandlers::playSound);
        vm.registerBuiltin("stopSound", SoundHandlers::stopSound);
        vm.registerBuiltin("soundBusy", SoundHandlers::soundBusy);

        // Sound properties
        vm.registerBuiltin("soundLevel", SoundHandlers::soundLevel);

        // Beep
        vm.registerBuiltin("beep", SoundHandlers::beep);
    }

    /**
     * Get a sound channel reference.
     * sound(channelNum)
     */
    private static Datum sound(LingoVM vm, List<Datum> args) {
        int channelNum = getInt(args, 0, 1);
        if (channelNum < 1 || channelNum > SoundChannel.MAX_CHANNELS) {
            channelNum = 1;
        }
        return new Datum.SoundChannel(channelNum);
    }

    /**
     * Play a sound in a channel with puppet control.
     * puppetSound channelNum, memberRef
     * puppetSound channelNum, 0 (to stop)
     */
    private static Datum puppetSound(LingoVM vm, List<Datum> args) {
        if (!hasAtLeast(args, 2)) return Datum.voidValue();

        int channelNum = getInt0(args);
        Datum memberArg = get1(args);

        SoundChannel channel = channels.get(channelNum);
        if (channel == null) return Datum.voidValue();

        if (memberArg.intValue() == 0) {
            channel.stop();
        } else if (memberArg instanceof Datum.CastMemberRef ref) {
            playCastMemberSound(channel, ref);
        }

        return Datum.voidValue();
    }

    /**
     * Play a sound.
     * playSound(sound) or playSound(channelNum, sound)
     */
    private static Datum playSound(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.voidValue();

        int channelNum;
        Datum soundArg;

        if (hasAtLeast(args, 2)) {
            channelNum = getInt0(args);
            soundArg = get1(args);
        } else {
            channelNum = 1;
            soundArg = get0(args);
        }

        SoundChannel channel = channels.get(channelNum);
        if (channel == null) return Datum.voidValue();

        if (soundArg instanceof Datum.CastMemberRef ref) {
            playCastMemberSound(channel, ref);
        }

        return Datum.voidValue();
    }

    /**
     * Helper to play a cast member sound on a channel.
     * Refactored: Extracts duplicated sound lookup/play logic.
     */
    private static void playCastMemberSound(SoundChannel channel, Datum.CastMemberRef ref) {
        int key = (ref.castLib() << 16) | ref.memberNum();
        SoundMember sound = soundMembers.get(key);
        if (sound != null) {
            channel.play(sound);
        }
    }

    /**
     * Stop sound in a channel.
     * stopSound(channelNum)
     */
    private static Datum stopSound(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) {
            // Stop all channels
            for (SoundChannel channel : channels.values()) {
                channel.stop();
            }
        } else {
            int channelNum = getInt0(args);
            SoundChannel channel = channels.get(channelNum);
            if (channel != null) {
                channel.stop();
            }
        }
        return Datum.voidValue();
    }

    /**
     * Check if a sound channel is busy.
     * soundBusy(channelNum)
     */
    private static Datum soundBusy(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.FALSE;
        int channelNum = getInt0(args);
        SoundChannel channel = channels.get(channelNum);
        return (channel != null && channel.isBusy()) ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * Get or set the sound level (master volume).
     * soundLevel() or soundLevel(volume)
     */
    private static Datum soundLevel(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) {
            // Get current sound level (0-7 in classic Lingo)
            return Datum.of(7);
        } else {
            // Set sound level (0-7)
            int level = getInt0(args);
            float volume = Math.max(0, Math.min(7, level)) / 7.0f;
            for (SoundChannel channel : channels.values()) {
                channel.setVolume((int)(volume * 255));
            }
            return Datum.voidValue();
        }
    }

    /**
     * System beep.
     * beep() or beep(count)
     */
    private static Datum beep(LingoVM vm, List<Datum> args) {
        // Beep functionality disabled for cross-platform compatibility
        return Datum.voidValue();
    }

    // Static methods for managing sounds

    public static void registerSound(int castLib, int memberNum, SoundMember sound) {
        int key = (castLib << 16) | memberNum;
        soundMembers.put(key, sound);
    }

    public static SoundChannel getChannel(int channelNum) {
        return channels.get(channelNum);
    }

    public static void stopAllSounds() {
        for (SoundChannel channel : channels.values()) {
            channel.stop();
        }
    }

    public static void dispose() {
        for (SoundChannel channel : channels.values()) {
            channel.dispose();
        }
        for (SoundMember sound : soundMembers.values()) {
            sound.dispose();
        }
        soundMembers.clear();
    }
}
