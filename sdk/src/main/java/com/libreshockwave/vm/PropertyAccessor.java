package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.TextChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Property access for various Datum types in the Lingo VM.
 * Handles getting and setting properties on objects, movie state, cast members, etc.
 */
public class PropertyAccessor {

    private final LingoVM vm;

    public PropertyAccessor(LingoVM vm) {
        this.vm = vm;
    }

    /**
     * Get a property from any datum type.
     */
    public Datum getProperty(Datum obj, String propName,
                             Supplier<List<Datum.ScriptInstanceRef>> activeInstances) {
        if (obj instanceof Datum.PropList propList) {
            return propList.get(Datum.symbol(propName));
        }
        if (obj instanceof Datum.ScriptInstanceRef instance) {
            return instance.getProperty(propName);
        }
        if (obj instanceof Datum.CastMemberRef ref) {
            return getCastMemberProperty(ref, propName);
        }
        if (obj instanceof Datum.CastLibRef ref) {
            return getCastLibProperty(ref, propName);
        }
        if (obj instanceof Datum.SpriteRef ref) {
            return getSpriteProperty(ref.channel(), propName);
        }
        if (obj instanceof Datum.Symbol symbol) {
            return getSymbolProperty(symbol, propName, activeInstances);
        }
        return Datum.voidValue();
    }

    /**
     * Set a property on any datum type.
     */
    public void setProperty(Datum obj, String propName, Datum value,
                            Supplier<List<Datum.ScriptInstanceRef>> activeInstances) {
        if (obj instanceof Datum.PropList propList) {
            propList.put(Datum.symbol(propName), value);
        } else if (obj instanceof Datum.ScriptInstanceRef instance) {
            instance.setProperty(propName, value);
        } else if (obj instanceof Datum.Symbol symbol) {
            setSymbolProperty(symbol, propName, value, activeInstances);
        }
    }

    private Datum getSymbolProperty(Datum.Symbol symbol, String propName,
                                    Supplier<List<Datum.ScriptInstanceRef>> activeInstances) {
        String scriptName = symbol.name();
        if (activeInstances != null) {
            for (Datum.ScriptInstanceRef ref : activeInstances.get()) {
                if (ref.scriptName().equalsIgnoreCase(scriptName)) {
                    return ref.getProperty(propName);
                }
            }
        }
        return Datum.voidValue();
    }

    private void setSymbolProperty(Datum.Symbol symbol, String propName, Datum value,
                                   Supplier<List<Datum.ScriptInstanceRef>> activeInstances) {
        String scriptName = symbol.name();
        if (activeInstances != null) {
            for (Datum.ScriptInstanceRef ref : activeInstances.get()) {
                if (ref.scriptName().equalsIgnoreCase(scriptName)) {
                    ref.setProperty(propName, value);
                    return;
                }
            }
        }
    }

    private Datum getCastMemberProperty(Datum.CastMemberRef ref, String propName) {
        return switch (propName.toLowerCase()) {
            case "number", "membernum" -> Datum.of(ref.memberNum());
            case "castlib", "castlibnum" -> Datum.of(ref.castLib());
            default -> Datum.voidValue();
        };
    }

    private Datum getCastLibProperty(Datum.CastLibRef ref, String propName) {
        int castNum = ref.castLib();
        CastManager castManager = vm.getCastManager();
        CastLib cast = castManager != null ? castManager.getCast(castNum) : null;

        return switch (propName.toLowerCase()) {
            case "number" -> Datum.of(castNum);
            case "name" -> Datum.of(cast != null ? cast.getName() : "");
            case "filename" -> Datum.of(cast != null ? cast.getFileName() : "");
            case "preloadmode" -> {
                if (cast != null && cast.isExternal()) {
                    yield Datum.of(cast.getState() == CastLib.State.LOADED ? 2 : 0);
                }
                yield Datum.of(0);
            }
            default -> Datum.voidValue();
        };
    }

    private Datum getSpriteProperty(int channel, String propName) {
        return switch (propName.toLowerCase()) {
            case "spritenum", "channel" -> Datum.of(channel);
            case "visible" -> Datum.TRUE;
            case "loc" -> new Datum.IntPoint(0, 0);
            case "rect" -> new Datum.IntRect(0, 0, 0, 0);
            case "loch", "left" -> Datum.of(0);
            case "locv", "top" -> Datum.of(0);
            case "width", "height" -> Datum.of(0);
            case "ink" -> Datum.of(0);
            case "blend" -> Datum.of(100);
            default -> Datum.voidValue();
        };
    }

    /**
     * Get a movie-level property.
     */
    public Datum getMovieProperty(String propName, MovieState state) {
        DirectorFile file = vm.getFile();

        return switch (propName.toLowerCase()) {
            // Stage properties
            case "stage" -> new Datum.StageRef();
            case "stagewidth", "stageright" -> Datum.of(file != null ? file.getStageWidth() : 640);
            case "stageheight", "stagebottom" -> Datum.of(file != null ? file.getStageHeight() : 480);
            case "stageleft", "stagetop" -> Datum.of(0);

            // Frame properties
            case "frame" -> Datum.of(state.currentFrame);
            case "lastframe" -> Datum.of(getLastFrame(file));
            case "framelabel" -> Datum.of(state.currentFrameLabel != null ? state.currentFrameLabel : "");
            case "frametempo", "tempo" -> Datum.of(file != null ? file.getTempo() : 15);
            case "lastchannel" -> Datum.of(getLastChannel(file));

            // Time properties
            case "time" -> formatTime(false);
            case "long time" -> formatTime(true);
            case "date" -> formatDate();
            case "milliseconds" -> Datum.of((int) (System.currentTimeMillis() - state.startTimeMillis));
            case "ticks" -> Datum.of((int) ((System.currentTimeMillis() - state.startTimeMillis) / 16));

            // Mouse properties
            case "mouseloc" -> new Datum.IntPoint(state.mouseX, state.mouseY);
            case "mouseh" -> Datum.of(state.mouseX);
            case "mousev" -> Datum.of(state.mouseY);
            case "rollover" -> Datum.of(state.rolloverSprite);
            case "clickon" -> Datum.of(state.clickOnSprite);
            case "doubleclick" -> state.isDoubleClick ? Datum.TRUE : Datum.FALSE;

            // Keyboard properties
            case "keycode" -> Datum.of(state.keyCode);
            case "key" -> Datum.of(state.lastKey != null ? state.lastKey : "");
            case "shiftdown" -> state.shiftDown ? Datum.TRUE : Datum.FALSE;
            case "controldown" -> state.controlDown ? Datum.TRUE : Datum.FALSE;
            case "commanddown" -> state.commandDown ? Datum.TRUE : Datum.FALSE;
            case "optiondown", "altdown" -> state.altDown ? Datum.TRUE : Datum.FALSE;

            // Focus and selection
            case "keyboardfocussprite" -> Datum.of(state.keyboardFocusSprite);
            case "selstart" -> Datum.of(state.selectionStart);
            case "selend" -> Datum.of(state.selectionEnd);

            // Movie info
            case "moviename" -> Datum.of(file != null && file.getBasePath() != null
                ? new java.io.File(file.getBasePath()).getName() : "");
            case "moviepath", "path" -> Datum.of(file != null ? file.getBasePath() : "");
            case "platform" -> Datum.of("Windows,32");
            case "runmode" -> Datum.of("Plugin");
            case "productversion" -> Datum.of("10.1");
            case "colordepth" -> Datum.of(32);

            // Settings
            case "floatprecision" -> Datum.of(state.floatPrecision);
            case "itemdelimiter" -> Datum.of(state.itemDelimiter);
            case "exitlock" -> state.exitLock ? Datum.TRUE : Datum.FALSE;
            case "updatelock" -> state.updateLock ? Datum.TRUE : Datum.FALSE;
            case "tracescript", "tracelogfile" -> Datum.of("");

            // actorList global
            case "actorlist" -> vm.getGlobal("actorList");
            case "currentspritenum" -> Datum.of(0);

            default -> Datum.voidValue();
        };
    }

    /**
     * Set a movie-level property.
     */
    public void setMovieProperty(String propName, Datum value, MovieState state) {
        switch (propName.toLowerCase()) {
            case "keyboardfocussprite" -> state.keyboardFocusSprite = value.intValue();
            case "selstart" -> state.selectionStart = value.intValue();
            case "selend" -> state.selectionEnd = value.intValue();
            case "floatprecision" -> state.floatPrecision = value.intValue();
            case "itemdelimiter" -> {
                String s = value.stringValue();
                if (!s.isEmpty()) state.itemDelimiter = s;
            }
            case "exitlock" -> state.exitLock = value.boolValue();
            case "updatelock" -> state.updateLock = value.boolValue();
            case "actorlist" -> vm.setGlobal("actorList", value);
        }
    }

    private int getLastFrame(DirectorFile file) {
        if (file != null && file.getScoreChunk() != null && file.getScoreChunk().frameData() != null) {
            return file.getScoreChunk().frameData().header().frameCount();
        }
        return 1;
    }

    private int getLastChannel(DirectorFile file) {
        if (file != null && file.getScoreChunk() != null && file.getScoreChunk().frameData() != null) {
            return file.getScoreChunk().frameData().header().numChannels();
        }
        return 48;
    }

    private Datum formatTime(boolean longFormat) {
        java.time.LocalTime now = java.time.LocalTime.now();
        int hour = now.getHour() % 12;
        if (hour == 0) hour = 12;
        String ampm = now.getHour() < 12 ? "AM" : "PM";
        if (longFormat) {
            return Datum.of(String.format("%02d:%02d:%02d %s", hour, now.getMinute(), now.getSecond(), ampm));
        }
        return Datum.of(String.format("%02d:%02d %s", hour, now.getMinute(), ampm));
    }

    private Datum formatDate() {
        java.time.LocalDate now = java.time.LocalDate.now();
        return Datum.of(String.format("%02d/%02d/%04d", now.getMonthValue(), now.getDayOfMonth(), now.getYear()));
    }

    /**
     * Get the text content of a field member.
     */
    public String getFieldText(Datum memberIdentifier, Datum castIdentifier, ScriptResolver resolver) {
        CastManager castManager = vm.getCastManager();
        DirectorFile file = vm.getFile();

        if (castManager == null) return "";

        int castNum = resolveCastNumber(castIdentifier, castManager);
        CastMemberChunk member = findMember(memberIdentifier, castNum, castManager);

        if (member == null || !member.isText()) return "";

        CastLib cast = castManager.getCast(castNum);
        DirectorFile dirFile = (cast != null && cast.getDirectorFile() != null)
            ? cast.getDirectorFile() : file;

        if (dirFile == null) return "";

        KeyTableChunk keyTable = dirFile.getKeyTable();
        if (keyTable == null) return "";

        int stxtFourccBE = com.libreshockwave.io.BinaryReader.fourCC("STXT");
        int stxtFourccLE = com.libreshockwave.io.BinaryReader.fourCC("TXTS");
        int memberId = member.id();

        KeyTableChunk.KeyTableEntry stxtEntry = keyTable.findEntry(memberId, stxtFourccBE);
        if (stxtEntry == null) {
            stxtEntry = keyTable.findEntry(memberId, stxtFourccLE);
        }
        if (stxtEntry == null) return "";

        Optional<TextChunk> textChunkOpt = dirFile.getChunk(stxtEntry.sectionId(), TextChunk.class);
        return textChunkOpt.map(TextChunk::text).orElse("");
    }

    private int resolveCastNumber(Datum castIdentifier, CastManager castManager) {
        if (castIdentifier != null && !castIdentifier.isVoid()) {
            if (castIdentifier.isInt()) {
                return castIdentifier.intValue();
            }
            if (castIdentifier.isString()) {
                CastLib cast = castManager.getCastByName(castIdentifier.stringValue());
                if (cast != null) return cast.getNumber();
            }
        }
        return 1;
    }

    private CastMemberChunk findMember(Datum memberIdentifier, int castNum, CastManager castManager) {
        CastLib cast = castManager.getCast(castNum);

        if (memberIdentifier.isInt()) {
            return cast != null ? cast.getMember(memberIdentifier.intValue()) : null;
        }

        if (memberIdentifier.isString()) {
            String memberName = memberIdentifier.stringValue();
            if (cast != null) {
                for (CastMemberChunk m : cast.getAllMembers()) {
                    if (memberName.equalsIgnoreCase(m.name())) {
                        return m;
                    }
                }
            }
            return castManager.findMemberByName(memberName);
        }

        return null;
    }

    /**
     * Mutable movie state container.
     */
    public static class MovieState {
        public int currentFrame = 1;
        public String currentFrameLabel = "";
        public long startTimeMillis = System.currentTimeMillis();
        public int mouseX = 0, mouseY = 0;
        public int rolloverSprite = 0, clickOnSprite = 0;
        public boolean isDoubleClick = false;
        public int keyCode = 0;
        public String lastKey = "";
        public boolean shiftDown = false, controlDown = false, commandDown = false, altDown = false;
        public int keyboardFocusSprite = 0, selectionStart = 0, selectionEnd = 0;
        public int floatPrecision = 4;
        public String itemDelimiter = ",";
        public boolean exitLock = false, updateLock = false;
    }
}
