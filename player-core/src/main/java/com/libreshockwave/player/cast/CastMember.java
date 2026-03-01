package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.TextChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.vm.Datum;

/**
 * Represents a loaded cast member with lazy loading of media data.
 * Similar to dirplayer-rs player/cast_member.rs.
 *
 * Cast members contain the member definition (CastMemberChunk) and
 * optionally load their media data (bitmap pixels, text, etc.) on demand.
 */
public class CastMember {

    public enum State {
        NONE,
        LOADING,
        LOADED
    }

    private final int castLibNumber;
    private final int memberNumber;
    private final CastMemberChunk chunk;
    private final DirectorFile sourceFile;

    private State state = State.NONE;

    // Loaded media data (lazy)
    private Bitmap bitmap;
    private ScriptChunk script;
    private String textContent;

    // Cached properties
    private String name;
    private MemberType memberType;
    private int regPointX;
    private int regPointY;

    // Dynamic text content (for dynamically created field/text members)
    private String dynamicText;

    public CastMember(int castLibNumber, int memberNumber, CastMemberChunk chunk, DirectorFile sourceFile) {
        this.castLibNumber = castLibNumber;
        this.memberNumber = memberNumber;
        this.chunk = chunk;
        this.sourceFile = sourceFile;

        // Copy basic properties from chunk
        this.name = chunk.name() != null ? chunk.name() : "";
        this.memberType = chunk.memberType();
        this.regPointX = chunk.regPointX();
        this.regPointY = chunk.regPointY();
    }

    /**
     * Constructor for dynamically created members (via new(#type, castLib)).
     */
    public CastMember(int castLibNumber, int memberNumber, MemberType memberType) {
        this.castLibNumber = castLibNumber;
        this.memberNumber = memberNumber;
        this.chunk = null;
        this.sourceFile = null;
        this.name = "";
        this.memberType = memberType;
        this.state = State.LOADED; // Dynamic members are immediately ready
    }

    /**
     * Load media data for this member.
     * For bitmaps, this loads the pixel data.
     * For scripts, this loads the script bytecode.
     */
    public void load() {
        if (state == State.LOADED) {
            return;
        }

        state = State.LOADING;

        if (chunk == null || sourceFile == null) {
            state = State.LOADED;
            return;
        }

        // Load type-specific data
        switch (memberType) {
            case BITMAP -> loadBitmap();
            case SCRIPT -> loadScript();
            case TEXT, BUTTON -> loadText();
            // Other types can be added as needed
            default -> {}
        }

        state = State.LOADED;
    }

    private void loadBitmap() {
        if (sourceFile == null || chunk == null) {
            return;
        }

        // Use DirectorFile's decodeBitmap method which handles all bitmap types
        try {
            sourceFile.decodeBitmap(chunk).ifPresent(b -> bitmap = b);
        } catch (Exception e) {
            System.err.println("[CastMember] Failed to decode bitmap: " + e.getMessage());
        }
    }

    private void loadScript() {
        if (sourceFile == null || chunk.scriptId() <= 0) {
            return;
        }

        script = sourceFile.getScriptByContextId(chunk.scriptId());
    }

    private void loadText() {
        if (sourceFile == null || chunk == null) {
            textContent = "";
            return;
        }

        // Text content is stored in an STXT chunk associated with this member.
        // Use the KeyTableChunk to find the associated STXT chunk.
        KeyTableChunk keyTable = sourceFile.getKeyTable();
        if (keyTable != null) {
            // The fourcc for STXT in the key table
            int stxtFourcc = ChunkType.STXT.getFourCC();
            var entry = keyTable.findEntry(chunk.id(), stxtFourcc);
            if (entry != null) {
                var textChunk = sourceFile.getChunk(entry.sectionId(), TextChunk.class);
                if (textChunk.isPresent()) {
                    textContent = textChunk.get().text();
                    return;
                }
            }
        }

        // Fallback: Try to find a text chunk with the same ID as the member
        var textChunk = sourceFile.getChunk(chunk.id(), TextChunk.class);
        if (textChunk.isPresent()) {
            textContent = textChunk.get().text();
        } else {
            textContent = "";
        }
    }

    /**
     * Get the text content for text/field members.
     * Returns dynamicText if set (via member.text = value), otherwise the loaded text content.
     */
    public String getTextContent() {
        if (dynamicText != null) {
            return dynamicText;
        }
        if (!isLoaded()) {
            load();

            // Normalize line endings to \n (convert \r\n and \r to \n)
            if (textContent != null && !textContent.isEmpty()) {
                textContent = textContent.replace("\r\n", "\r").replace("\n", "\r");
            }
        }
        return textContent != null ? textContent : "";
    }

    // Accessors

    public int getCastLibNumber() {
        return castLibNumber;
    }

    public int getMemberNumber() {
        return memberNumber;
    }

    public CastMemberChunk getChunk() {
        return chunk;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MemberType getMemberType() {
        return memberType;
    }

    public State getState() {
        return state;
    }

    public boolean isLoaded() {
        return state == State.LOADED;
    }

    public Bitmap getBitmap() {
        if (!isLoaded()) {
            load();
        }
        return bitmap;
    }

    public ScriptChunk getScript() {
        if (!isLoaded()) {
            load();
        }
        return script;
    }

    public int getRegPointX() {
        return regPointX;
    }

    public int getRegPointY() {
        return regPointY;
    }

    public void setRegPoint(int x, int y) {
        this.regPointX = x;
        this.regPointY = y;
    }

    /**
     * Get a property value for this member.
     */
    public Datum getProp(String propName) {
        String prop = propName.toLowerCase();

        // Common properties for all member types
        return switch (prop) {
            case "name" -> Datum.of(name);
            case "number" -> Datum.of(getSlotNumber());
            case "membernum" -> Datum.of(memberNumber);
            case "type" -> Datum.of(memberType.getName());
            case "castlibnum" -> Datum.of(castLibNumber);
            case "castlib" -> new Datum.CastLibRef(castLibNumber);
            case "mediaready" -> Datum.of(1); // Always ready for now
            default -> getTypeProp(prop);
        };
    }

    /**
     * Get a type-specific property.
     */
    private Datum getTypeProp(String prop) {
        return switch (memberType) {
            case BITMAP -> getBitmapProp(prop);
            case TEXT, BUTTON -> getTextProp(prop);
            case SCRIPT -> getScriptProp(prop);
            case SHAPE -> getShapeProp(prop);
            default -> Datum.VOID;
        };
    }

    private Datum getBitmapProp(String prop) {
        // Ensure bitmap is loaded
        Bitmap bmp = getBitmap();

        return switch (prop) {
            case "width" -> Datum.of(bmp != null ? bmp.getWidth() : 0);
            case "height" -> Datum.of(bmp != null ? bmp.getHeight() : 0);
            case "depth" -> Datum.of(bmp != null ? bmp.getBitDepth() : 0);
            case "regpoint" -> new Datum.Point(regPointX, regPointY);
            case "rect" -> {
                int w = bmp != null ? bmp.getWidth() : 0;
                int h = bmp != null ? bmp.getHeight() : 0;
                yield new Datum.Rect(0, 0, w, h);
            }
            default -> Datum.VOID;
        };
    }

    private Datum getTextProp(String prop) {
        return switch (prop) {
            case "text" -> Datum.of(getTextContent());
            case "width", "height" -> Datum.of(0); // TODO: compute from text
            default -> Datum.VOID;
        };
    }

    private Datum getScriptProp(String prop) {
        ScriptChunk s = getScript();
        return switch (prop) {
            case "text" -> Datum.EMPTY_STRING; // Script source not typically available
            case "scripttype" -> {
                if (s != null && s.getScriptType() != null) {
                    yield Datum.of(s.getScriptType().name().toLowerCase());
                }
                yield Datum.VOID;
            }
            default -> Datum.VOID;
        };
    }

    private Datum getShapeProp(String prop) {
        // Shape properties from specificData
        return switch (prop) {
            case "width", "height" -> Datum.of(0); // TODO: parse from specificData
            default -> Datum.VOID;
        };
    }

    /**
     * Set a property value for this member.
     */
    public boolean setProp(String propName, Datum value) {
        String prop = propName.toLowerCase();

        switch (prop) {
            case "name" -> {
                this.name = value.toStr();
                return true;
            }
            case "regpoint" -> {
                // TODO: parse Point datum
                return false;
            }
            default -> {
                return setTypeProp(prop, value);
            }
        }
    }

    private boolean setTypeProp(String prop, Datum value) {
        if ("text".equals(prop) && (memberType == MemberType.TEXT || memberType == MemberType.BUTTON)) {
            this.dynamicText = value.toStr();
            return true;
        }
        return false;
    }

    /**
     * Get the combined slot number (castLib << 16 | memberNum).
     */
    public int getSlotNumber() {
        return (castLibNumber << 16) | (memberNumber & 0xFFFF);
    }

    @Override
    public String toString() {
        return "CastMember{castLib=" + castLibNumber + ", member=" + memberNumber +
               ", name='" + name + "', type=" + memberType + "}";
    }
}
