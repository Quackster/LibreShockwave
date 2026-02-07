package com.libreshockwave.vm.opcode;

import java.util.Map;

/**
 * Mappings from property IDs to property names for GET/SET opcodes.
 * Based on dirplayer-rs constants.rs
 */
public final class PropertyIdMappings {

    private PropertyIdMappings() {}

    /**
     * Movie property names (property type 0x00).
     * Used for "the floatPrecision", "the mouseDownScript", etc.
     */
    public static final Map<Integer, String> MOVIE_PROPS = Map.ofEntries(
        Map.entry(0x00, "floatPrecision"),
        Map.entry(0x01, "mouseDownScript"),
        Map.entry(0x02, "mouseUpScript"),
        Map.entry(0x03, "keyDownScript"),
        Map.entry(0x04, "keyUpScript"),
        Map.entry(0x05, "timeoutScript"),
        Map.entry(0x06, "short time"),
        Map.entry(0x07, "abbr time"),
        Map.entry(0x08, "long time"),
        Map.entry(0x09, "short date"),
        Map.entry(0x0a, "abbr date"),
        Map.entry(0x0b, "long date")
    );

    /**
     * Sprite property names (property type 0x06).
     */
    public static final Map<Integer, String> SPRITE_PROPS = Map.ofEntries(
        Map.entry(0x01, "type"),
        Map.entry(0x02, "backColor"),
        Map.entry(0x03, "bottom"),
        Map.entry(0x04, "castNum"),
        Map.entry(0x05, "constraint"),
        Map.entry(0x06, "cursor"),
        Map.entry(0x07, "foreColor"),
        Map.entry(0x08, "height"),
        Map.entry(0x09, "immediate"),
        Map.entry(0x0a, "ink"),
        Map.entry(0x0b, "left"),
        Map.entry(0x0c, "lineSize"),
        Map.entry(0x0d, "locH"),
        Map.entry(0x0e, "locV"),
        Map.entry(0x0f, "movieRate"),
        Map.entry(0x10, "movieTime"),
        Map.entry(0x11, "pattern"),
        Map.entry(0x12, "puppet"),
        Map.entry(0x13, "right"),
        Map.entry(0x14, "startTime"),
        Map.entry(0x15, "stopTime"),
        Map.entry(0x16, "stretch"),
        Map.entry(0x17, "top"),
        Map.entry(0x18, "trails"),
        Map.entry(0x19, "visible"),
        Map.entry(0x1a, "volume"),
        Map.entry(0x1b, "width"),
        Map.entry(0x1c, "blend"),
        Map.entry(0x1d, "scriptNum"),
        Map.entry(0x1e, "moveableSprite"),
        Map.entry(0x1f, "editableText"),
        Map.entry(0x20, "scoreColor"),
        Map.entry(0x21, "loc"),
        Map.entry(0x22, "rect"),
        Map.entry(0x23, "memberNum"),
        Map.entry(0x24, "castLibNum"),
        Map.entry(0x25, "member"),
        Map.entry(0x26, "scriptInstanceList"),
        Map.entry(0x27, "currentTime"),
        Map.entry(0x28, "mostRecentCuePoint"),
        Map.entry(0x29, "tweened"),
        Map.entry(0x2a, "name")
    );

    /**
     * Animation property names (property type 0x07).
     */
    public static final Map<Integer, String> ANIM_PROPS = Map.ofEntries(
        Map.entry(0x01, "beepOn"),
        Map.entry(0x02, "buttonStyle"),
        Map.entry(0x03, "centerStage"),
        Map.entry(0x04, "checkBoxAccess"),
        Map.entry(0x05, "checkboxType"),
        Map.entry(0x06, "colorDepth"),
        Map.entry(0x07, "colorQD"),
        Map.entry(0x08, "exitLock"),
        Map.entry(0x09, "fixStageSize"),
        Map.entry(0x0a, "fullColorPermit"),
        Map.entry(0x0b, "imageDirect"),
        Map.entry(0x0c, "doubleClick"),
        Map.entry(0x0d, "key"),
        Map.entry(0x0e, "lastClick"),
        Map.entry(0x0f, "lastEvent"),
        Map.entry(0x10, "keyCode"),
        Map.entry(0x11, "lastKey"),
        Map.entry(0x12, "lastRoll"),
        Map.entry(0x13, "timeoutLapsed"),
        Map.entry(0x14, "multiSound"),
        Map.entry(0x15, "pauseState"),
        Map.entry(0x16, "quickTimePresent"),
        Map.entry(0x17, "selEnd"),
        Map.entry(0x18, "selStart"),
        Map.entry(0x19, "soundEnabled"),
        Map.entry(0x1a, "soundLevel"),
        Map.entry(0x1b, "stageColor"),
        Map.entry(0x1d, "switchColorDepth"),
        Map.entry(0x1e, "timeoutKeyDown"),
        Map.entry(0x1f, "timeoutLength"),
        Map.entry(0x20, "timeoutMouse"),
        Map.entry(0x21, "timeoutPlay"),
        Map.entry(0x22, "timer"),
        Map.entry(0x23, "preLoadRAM"),
        Map.entry(0x24, "videoForWindowsPresent"),
        Map.entry(0x25, "netPresent"),
        Map.entry(0x26, "safePlayer"),
        Map.entry(0x27, "soundKeepDevice"),
        Map.entry(0x28, "soundMixMedia")
    );

    /**
     * Animation2 property names (property type 0x08).
     */
    public static final Map<Integer, String> ANIM2_PROPS = Map.of(
        0x01, "perFrameHook",
        0x02, "number of castMembers",
        0x03, "number of menus",
        0x04, "number of castLibs",
        0x05, "number of xtras"
    );

    /**
     * Sound channel property names (property type 0x04).
     */
    public static final Map<Integer, String> SOUND_PROPS = Map.of(
        0x01, "volume",
        0x02, "pan",
        0x03, "loopCount",
        0x04, "startTime",
        0x05, "endTime",
        0x06, "loopStartTime",
        0x07, "loopEndTime"
    );

    public static String getMoviePropName(int id) {
        return MOVIE_PROPS.get(id);
    }

    public static String getSpritePropName(int id) {
        return SPRITE_PROPS.get(id);
    }

    public static String getAnimPropName(int id) {
        return ANIM_PROPS.get(id);
    }

    public static String getAnim2PropName(int id) {
        return ANIM2_PROPS.get(id);
    }

    public static String getSoundPropName(int id) {
        return SOUND_PROPS.getOrDefault(id, "unknown_sound_prop_" + id);
    }
}
