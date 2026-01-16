package com.libreshockwave.cast;

import com.libreshockwave.chunks.CastMemberChunk;

/**
 * Represents a fully-parsed cast member with type-specific data.
 */
public class CastMember {

    private final int id;
    private final int castLib;
    private final int memberNum;
    private final MemberType memberType;
    private final String name;
    private final int scriptId;
    private final CastMemberChunk rawChunk;

    // Type-specific data (only one will be non-null based on memberType)
    private BitmapInfo bitmapInfo;
    private ShapeInfo shapeInfo;
    private FilmLoopInfo filmLoopInfo;
    private ScriptType scriptType;

    public CastMember(int id, int castLib, int memberNum, CastMemberChunk chunk) {
        this.id = id;
        this.castLib = castLib;
        this.memberNum = memberNum;
        this.rawChunk = chunk;

        // Map chunk member type to our MemberType enum
        this.memberType = MemberType.fromCode(chunk.memberType().getCode());
        this.name = chunk.name();
        this.scriptId = chunk.scriptId();

        // Parse type-specific data
        parseSpecificData(chunk);
    }

    private void parseSpecificData(CastMemberChunk chunk) {
        byte[] specificData = chunk.specificData();

        switch (memberType) {
            case BITMAP -> bitmapInfo = BitmapInfo.parse(specificData);
            case SHAPE -> shapeInfo = ShapeInfo.parse(specificData);
            case FILM_LOOP -> filmLoopInfo = FilmLoopInfo.parse(specificData);
            case SCRIPT -> {
                if (specificData != null && specificData.length >= 2) {
                    int typeCode = ((specificData[0] & 0xFF) << 8) | (specificData[1] & 0xFF);
                    scriptType = ScriptType.fromCode(typeCode);
                }
            }
            default -> { /* No specific data to parse */ }
        }
    }

    // Getters

    public int getId() { return id; }
    public int getCastLib() { return castLib; }
    public int getMemberNum() { return memberNum; }
    public MemberType getMemberType() { return memberType; }
    public String getName() { return name; }
    public int getScriptId() { return scriptId; }
    public CastMemberChunk getRawChunk() { return rawChunk; }

    // Type-specific getters

    public BitmapInfo getBitmapInfo() { return bitmapInfo; }
    public ShapeInfo getShapeInfo() { return shapeInfo; }
    public FilmLoopInfo getFilmLoopInfo() { return filmLoopInfo; }
    public ScriptType getScriptType() { return scriptType; }

    // Type checks

    public boolean isBitmap() { return memberType == MemberType.BITMAP; }
    public boolean isText() { return memberType == MemberType.TEXT; }
    public boolean isSound() { return memberType == MemberType.SOUND; }
    public boolean isScript() { return memberType == MemberType.SCRIPT; }
    public boolean isShape() { return memberType == MemberType.SHAPE; }
    public boolean isFilmLoop() { return memberType == MemberType.FILM_LOOP; }
    public boolean isPalette() { return memberType == MemberType.PALETTE; }
    public boolean isFont() { return memberType == MemberType.FONT; }

    // Dimensions (for visual members)

    public int getWidth() {
        if (bitmapInfo != null) return bitmapInfo.width();
        if (shapeInfo != null) return shapeInfo.width();
        if (filmLoopInfo != null) return filmLoopInfo.width();
        return 0;
    }

    public int getHeight() {
        if (bitmapInfo != null) return bitmapInfo.height();
        if (shapeInfo != null) return shapeInfo.height();
        if (filmLoopInfo != null) return filmLoopInfo.height();
        return 0;
    }

    public int getRegX() {
        if (bitmapInfo != null) return bitmapInfo.regX();
        if (shapeInfo != null) return shapeInfo.regX();
        if (filmLoopInfo != null) return filmLoopInfo.regX();
        return 0;
    }

    public int getRegY() {
        if (bitmapInfo != null) return bitmapInfo.regY();
        if (shapeInfo != null) return shapeInfo.regY();
        if (filmLoopInfo != null) return filmLoopInfo.regY();
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CastMember[");
        sb.append("lib=").append(castLib);
        sb.append(", num=").append(memberNum);
        sb.append(", type=").append(memberType);
        if (!name.isEmpty()) {
            sb.append(", name=\"").append(name).append("\"");
        }
        if (bitmapInfo != null) {
            sb.append(", ").append(bitmapInfo.width()).append("x").append(bitmapInfo.height());
            sb.append("x").append(bitmapInfo.bitDepth()).append("bit");
        }
        if (scriptType != null) {
            sb.append(", scriptType=").append(scriptType);
        }
        sb.append("]");
        return sb.toString();
    }
}
