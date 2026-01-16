package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.SoundChannel;
import com.libreshockwave.player.SoundMember;
import com.libreshockwave.vm.LingoVM;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in sound handlers for Lingo.
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
        if (args.isEmpty()) return new Datum.SoundChannel(1);
        int channelNum = args.get(0).intValue();
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
        if (args.size() < 2) return Datum.voidValue();

        int channelNum = args.get(0).intValue();
        Datum memberArg = args.get(1);

        SoundChannel channel = channels.get(channelNum);
        if (channel == null) return Datum.voidValue();

        if (memberArg.intValue() == 0) {
            // Stop sound
            channel.stop();
        } else if (memberArg instanceof Datum.CastMemberRef ref) {
            // Play cast member sound
            int key = (ref.castLib() << 16) | ref.memberNum();
            SoundMember sound = soundMembers.get(key);
            if (sound != null) {
                channel.play(sound);
            }
        }

        return Datum.voidValue();
    }

    /**
     * Play a sound.
     * playSound(sound) or playSound(channelNum, sound)
     */
    private static Datum playSound(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.voidValue();

        int channelNum = 1;
        Datum soundArg;

        if (args.size() >= 2) {
            channelNum = args.get(0).intValue();
            soundArg = args.get(1);
        } else {
            soundArg = args.get(0);
        }

        SoundChannel channel = channels.get(channelNum);
        if (channel == null) return Datum.voidValue();

        if (soundArg instanceof Datum.CastMemberRef ref) {
            int key = (ref.castLib() << 16) | ref.memberNum();
            SoundMember sound = soundMembers.get(key);
            if (sound != null) {
                channel.play(sound);
            }
        }

        return Datum.voidValue();
    }

    /**
     * Stop sound in a channel.
     * stopSound(channelNum)
     */
    private static Datum stopSound(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            // Stop all channels
            for (SoundChannel channel : channels.values()) {
                channel.stop();
            }
        } else {
            int channelNum = args.get(0).intValue();
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
        if (args.isEmpty()) return Datum.FALSE;
        int channelNum = args.get(0).intValue();
        SoundChannel channel = channels.get(channelNum);
        if (channel != null && channel.isBusy()) {
            return Datum.TRUE;
        }
        return Datum.FALSE;
    }

    /**
     * Get or set the sound level (master volume).
     * soundLevel() or soundLevel(volume)
     */
    private static Datum soundLevel(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            // Get current sound level (0-7 in classic Lingo)
            return Datum.of(7);
        } else {
            // Set sound level (0-7)
            int level = args.get(0).intValue();
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
        int count = args.isEmpty() ? 1 : args.get(0).intValue();
        for (int i = 0; i < count; i++) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            if (i < count - 1) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
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
