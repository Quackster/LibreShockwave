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

import java.awt.*;
import java.awt.image.BufferedImage;

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

    // Text rendering properties (set by Lingo scripts via member.font, member.fontSize, etc.)
    private String textFont = "Arial";
    private int textFontSize = 12;
    private String textFontStyle = "plain";
    private String textAlignment = "left";
    private int textColor = 0xFF000000; // ARGB black
    private int textBgColor = 0xFFFFFFFF; // ARGB white
    private boolean textWordWrap = false;
    private boolean textAntialias = true;
    private int textBoxType = 0; // 0 = adjust to fit, 1 = fixed
    private int textRectLeft = 0;
    private int textRectTop = 0;
    private int textRectRight = 480;
    private int textRectBottom = 480;
    private int textFixedLineSpace = 0;
    private int textTopSpacing = 0;
    private boolean textImageDirty = true; // Re-render when properties change
    private Bitmap textRenderedImage; // Cached rendered text image

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
            case "image" -> {
                if (bmp == null && chunk == null) {
                    // Dynamic bitmap member - create a default 1x1 bitmap
                    bitmap = new Bitmap(1, 1, 32);
                    bitmap.fill(0xFFFFFFFF);
                    bmp = bitmap;
                }
                if (bmp != null) {
                    yield new Datum.ImageRef(bmp);
                }
                yield Datum.VOID;
            }
            default -> Datum.VOID;
        };
    }

    private Datum getTextProp(String prop) {
        return switch (prop) {
            case "text" -> Datum.of(getTextContent());
            case "width" -> Datum.of(textRectRight - textRectLeft);
            case "height" -> {
                // Return the height needed to display the text
                // If text has been rendered, use that height; otherwise use rect height
                if (textRenderedImage != null && !textImageDirty) {
                    yield Datum.of(textRenderedImage.getHeight());
                }
                yield Datum.of(textRectBottom - textRectTop);
            }
            case "rect" -> new Datum.Rect(textRectLeft, textRectTop, textRectRight, textRectBottom);
            case "image" -> {
                // Render text content to a bitmap image
                Bitmap img = renderTextToImage();
                if (img != null) {
                    yield new Datum.ImageRef(img);
                }
                yield Datum.VOID;
            }
            case "font" -> Datum.of(textFont);
            case "fontsize" -> Datum.of(textFontSize);
            case "fontstyle" -> Datum.of(textFontStyle);
            case "alignment" -> Datum.symbol(textAlignment);
            case "color" -> new Datum.Color(
                    (textColor >> 16) & 0xFF,
                    (textColor >> 8) & 0xFF,
                    textColor & 0xFF);
            case "bgcolor" -> new Datum.Color(
                    (textBgColor >> 16) & 0xFF,
                    (textBgColor >> 8) & 0xFF,
                    textBgColor & 0xFF);
            case "wordwrap" -> Datum.of(textWordWrap ? 1 : 0);
            case "antialias" -> Datum.of(textAntialias ? 1 : 0);
            case "boxtype" -> Datum.of(textBoxType);
            case "fixedlinespace" -> Datum.of(textFixedLineSpace);
            case "topspacing" -> Datum.of(textTopSpacing);
            case "charposttoloc" -> Datum.VOID; // handled as method, not property
            default -> Datum.VOID;
        };
    }

    /**
     * Render the text content of this member to a Bitmap.
     * Uses Java AWT Graphics2D for text layout and rendering.
     * This implements Director's text member .image property.
     */
    private Bitmap renderTextToImage() {
        // Return cached image if still valid
        if (textRenderedImage != null && !textImageDirty) {
            return textRenderedImage;
        }

        String text = getTextContent();
        if (text == null) text = "";

        int width = textRectRight - textRectLeft;
        int height = textRectBottom - textRectTop;
        if (width <= 0) width = 200;
        if (height <= 0) height = 20;

        // Create a temporary BufferedImage to render text with AWT
        BufferedImage bufImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = bufImg.createGraphics();

        // Set rendering hints
        if (textAntialias) {
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Fill background
        g2d.setColor(new java.awt.Color(textBgColor, true));
        g2d.fillRect(0, 0, width, height);

        // Set font (resolve Director font names to system fonts)
        int fontStyleAwt = Font.PLAIN;
        String style = textFontStyle.toLowerCase();
        if (style.contains("bold")) fontStyleAwt |= Font.BOLD;
        if (style.contains("italic")) fontStyleAwt |= Font.ITALIC;
        Font font = resolveDirectorFont(textFont, fontStyleAwt, textFontSize);
        // Apply underline via font attributes if needed
        if (style.contains("underline")) {
            java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
            attrs.put(java.awt.font.TextAttribute.UNDERLINE, java.awt.font.TextAttribute.UNDERLINE_ON);
            font = font.deriveFont(attrs);
        }
        g2d.setFont(font);

        // Set text color
        g2d.setColor(new java.awt.Color(textColor, true));

        FontMetrics fm = g2d.getFontMetrics();
        int lineHeight = textFixedLineSpace > 0 ? textFixedLineSpace : fm.getHeight();
        int ascent = fm.getAscent();

        // Split text into lines
        String[] rawLines = text.split("[\r\n]+");
        if (rawLines.length == 0) rawLines = new String[]{""};

        // Word wrap if enabled
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (textWordWrap) {
            for (String rawLine : rawLines) {
                wrapLine(rawLine, fm, width, lines);
            }
        } else {
            for (String rawLine : rawLines) {
                lines.add(rawLine);
            }
        }

        // Compute needed height
        int neededHeight = lines.size() * lineHeight + textTopSpacing;
        if (neededHeight > height) {
            // Expand the image to fit all text
            g2d.dispose();
            bufImg = new BufferedImage(width, neededHeight, BufferedImage.TYPE_INT_ARGB);
            g2d = bufImg.createGraphics();
            if (textAntialias) {
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            }
            g2d.setColor(new java.awt.Color(textBgColor, true));
            g2d.fillRect(0, 0, width, neededHeight);
            g2d.setFont(font);
            g2d.setColor(new java.awt.Color(textColor, true));
            height = neededHeight;
        }

        // Draw lines
        int y = ascent + textTopSpacing;
        for (String line : lines) {
            if (y > height) break;

            int x = 0;
            switch (textAlignment) {
                case "center" -> x = (width - fm.stringWidth(line)) / 2;
                case "right" -> x = width - fm.stringWidth(line);
                default -> x = 0; // left
            }

            g2d.drawString(line, x, y);
            y += lineHeight;
        }

        g2d.dispose();

        // Convert BufferedImage to our Bitmap format
        int[] pixels = bufImg.getRGB(0, 0, bufImg.getWidth(), bufImg.getHeight(),
                null, 0, bufImg.getWidth());
        textRenderedImage = new Bitmap(bufImg.getWidth(), bufImg.getHeight(), 32, pixels);
        textImageDirty = false;

        return textRenderedImage;
    }

    /**
     * Word-wrap a single line of text to fit within maxWidth pixels.
     */
    private static void wrapLine(String text, FontMetrics fm, int maxWidth,
                                 java.util.List<String> out) {
        if (text.isEmpty()) {
            out.add("");
            return;
        }
        if (fm.stringWidth(text) <= maxWidth) {
            out.add(text);
            return;
        }

        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else {
                String candidate = current + " " + word;
                if (fm.stringWidth(candidate) <= maxWidth) {
                    current.append(" ").append(word);
                } else {
                    out.add(current.toString());
                    current = new StringBuilder(word);
                }
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
    }

    /**
     * Resolve a Director font name to a Java AWT Font.
     * Director movies often use short font aliases (from fontmap.txt or embedded fonts).
     * This method maps common aliases to system fonts and handles bold-embedded names.
     */
    private static Font resolveDirectorFont(String directorName, int style, int size) {
        if (directorName == null || directorName.isEmpty()) {
            return new Font("SansSerif", style, size);
        }

        // Check if the requested font exists in the system
        String resolved = resolveDirectorFontName(directorName);

        // If the font name itself implies a style (e.g., "vb" = Verdana Bold),
        // extract the style and merge with the provided style
        int extraStyle = extractFontStyle(directorName);
        if (extraStyle != Font.PLAIN) {
            style |= extraStyle;
        }

        return new Font(resolved, style, size);
    }

    /** Cache of available system font names (lowercase → actual name) */
    private static volatile java.util.Map<String, String> systemFontCache;

    private static java.util.Map<String, String> getSystemFontCache() {
        if (systemFontCache == null) {
            synchronized (CastMember.class) {
                if (systemFontCache == null) {
                    java.util.Map<String, String> cache = new java.util.HashMap<>();
                    for (String fontName : GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames()) {
                        cache.put(fontName.toLowerCase(), fontName);
                    }
                    systemFontCache = cache;
                }
            }
        }
        return systemFontCache;
    }

    /**
     * Resolve a Director font name to an actual system font name.
     * Handles:
     * - Full system font names (pass through)
     * - Short Director aliases like "v" → "Verdana", "vb" → "Verdana"
     * - Common Director font conventions
     */
    private static String resolveDirectorFontName(String name) {
        var cache = getSystemFontCache();

        // 1. Exact match (case-insensitive)
        String exact = cache.get(name.toLowerCase());
        if (exact != null) return exact;

        // 2. Strip trailing style indicators (b=bold, i=italic, bi=bold-italic)
        String baseName = name.replaceAll("(?i)[bi]+$", "");
        if (!baseName.isEmpty() && !baseName.equals(name)) {
            exact = cache.get(baseName.toLowerCase());
            if (exact != null) return exact;
        }

        // 3. Try prefix matching for very short names (common Director fontmap aliases)
        // e.g., "v" → "Verdana", "a" → "Arial", "t" → "Tahoma"
        if (name.length() <= 3) {
            String prefix = baseName.isEmpty() ? name.toLowerCase() : baseName.toLowerCase();
            // Prioritized list of common Director fonts
            String[] commonFonts = {
                "Verdana", "Arial", "Tahoma", "Times New Roman", "Courier New",
                "Georgia", "Helvetica", "Trebuchet MS", "Comic Sans MS"
            };
            for (String candidate : commonFonts) {
                if (candidate.toLowerCase().startsWith(prefix)) {
                    String resolved = cache.get(candidate.toLowerCase());
                    if (resolved != null) return resolved;
                }
            }
            // Fallback: try any system font starting with this prefix
            for (var entry : cache.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    return entry.getValue();
                }
            }
        }

        // 4. Fall back to SansSerif (always available in Java)
        return "SansSerif";
    }

    /**
     * Extract font style from Director font name conventions.
     * e.g., "vb" → Bold, "vi" → Italic, "vbi" → Bold+Italic
     */
    private static int extractFontStyle(String name) {
        if (name.length() <= 1) return Font.PLAIN;

        // Check if the name ends with style indicators after a base name
        String baseName = name.replaceAll("(?i)[bi]+$", "");
        if (baseName.length() == name.length()) return Font.PLAIN;

        String suffix = name.substring(baseName.length()).toLowerCase();
        int style = Font.PLAIN;
        if (suffix.contains("b")) style |= Font.BOLD;
        if (suffix.contains("i")) style |= Font.ITALIC;
        return style;
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
                if (value instanceof Datum.Point p) {
                    this.regPointX = p.x();
                    this.regPointY = p.y();
                    return true;
                }
                return false;
            }
            default -> {
                return setTypeProp(prop, value);
            }
        }
    }

    private boolean setTypeProp(String prop, Datum value) {
        if (memberType == MemberType.TEXT || memberType == MemberType.BUTTON) {
            return setTextProp(prop, value);
        }
        if (memberType == MemberType.BITMAP) {
            return setBitmapProp(prop, value);
        }
        return false;
    }

    /**
     * Set a property on a text/field member.
     * Handles font, fontSize, alignment, color, rect, text, image, and more.
     */
    private boolean setTextProp(String prop, Datum value) {
        switch (prop) {
            case "text" -> {
                this.dynamicText = value.toStr();
                textImageDirty = true;
                return true;
            }
            case "html" -> {
                // Strip HTML tags for now (basic support)
                String html = value.toStr();
                this.dynamicText = html.replaceAll("<[^>]*>", "");
                textImageDirty = true;
                return true;
            }
            case "font" -> {
                this.textFont = value.toStr();
                textImageDirty = true;
                return true;
            }
            case "fontsize" -> {
                this.textFontSize = value.toInt();
                textImageDirty = true;
                return true;
            }
            case "fontstyle" -> {
                if (value instanceof Datum.List list) {
                    // Director fontStyle is a list like [#bold, #italic]
                    StringBuilder sb = new StringBuilder();
                    for (Datum item : list.items()) {
                        if (sb.length() > 0) sb.append(",");
                        sb.append(item.toStr());
                    }
                    this.textFontStyle = sb.toString();
                } else {
                    this.textFontStyle = value.toStr();
                }
                textImageDirty = true;
                return true;
            }
            case "alignment" -> {
                if (value instanceof Datum.Symbol s) {
                    this.textAlignment = s.name().toLowerCase();
                } else {
                    this.textAlignment = value.toStr().toLowerCase();
                }
                textImageDirty = true;
                return true;
            }
            case "color" -> {
                this.textColor = datumToArgb(value);
                textImageDirty = true;
                return true;
            }
            case "bgcolor" -> {
                this.textBgColor = datumToArgb(value);
                textImageDirty = true;
                return true;
            }
            case "wordwrap" -> {
                this.textWordWrap = value.toInt() != 0;
                textImageDirty = true;
                return true;
            }
            case "antialias" -> {
                this.textAntialias = value.toInt() != 0;
                textImageDirty = true;
                return true;
            }
            case "boxtype" -> {
                this.textBoxType = value.toInt();
                textImageDirty = true;
                return true;
            }
            case "rect" -> {
                if (value instanceof Datum.Rect r) {
                    this.textRectLeft = r.left();
                    this.textRectTop = r.top();
                    this.textRectRight = r.right();
                    this.textRectBottom = r.bottom();
                    textImageDirty = true;
                    return true;
                }
                return false;
            }
            case "width" -> {
                this.textRectRight = this.textRectLeft + value.toInt();
                textImageDirty = true;
                return true;
            }
            case "height" -> {
                this.textRectBottom = this.textRectTop + value.toInt();
                textImageDirty = true;
                return true;
            }
            case "fixedlinespace" -> {
                this.textFixedLineSpace = value.toInt();
                textImageDirty = true;
                return true;
            }
            case "topspacing" -> {
                this.textTopSpacing = value.toInt();
                textImageDirty = true;
                return true;
            }
            case "image" -> {
                // Allow setting the bitmap directly (used by some scripts)
                if (value instanceof Datum.ImageRef ir) {
                    this.bitmap = ir.bitmap();
                    this.textRenderedImage = ir.bitmap();
                    this.textImageDirty = false;
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Convert a Datum color to ARGB int.
     */
    private static int datumToArgb(Datum colorDatum) {
        if (colorDatum instanceof Datum.Color c) {
            return 0xFF000000 | (c.r() << 16) | (c.g() << 8) | c.b();
        } else if (colorDatum instanceof Datum.Int i) {
            int val = i.value();
            if (val > 255) {
                return 0xFF000000 | (val & 0xFFFFFF);
            } else {
                int gray = 255 - val;
                return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
        }
        return 0xFF000000;
    }

    private boolean setBitmapProp(String prop, Datum value) {
        return switch (prop) {
            case "image" -> {
                if (value instanceof Datum.ImageRef ir) {
                    this.bitmap = ir.bitmap();
                    yield true;
                }
                yield false;
            }
            case "width" -> {
                // Create a new bitmap with the given width (preserving height)
                int newW = value.toInt();
                int h = bitmap != null ? bitmap.getHeight() : 1;
                if (newW > 0) {
                    this.bitmap = new Bitmap(newW, h, bitmap != null ? bitmap.getBitDepth() : 32);
                    this.bitmap.fill(0xFFFFFFFF);
                }
                yield true;
            }
            case "height" -> {
                int w = bitmap != null ? bitmap.getWidth() : 1;
                int newH = value.toInt();
                if (newH > 0) {
                    this.bitmap = new Bitmap(w, newH, bitmap != null ? bitmap.getBitDepth() : 32);
                    this.bitmap.fill(0xFFFFFFFF);
                }
                yield true;
            }
            default -> false;
        };
    }

    /**
     * Get the combined slot number (castLib << 16 | memberNum).
     */
    public int getSlotNumber() {
        return (castLibNumber << 16) | (memberNumber & 0xFFFF);
    }

    /**
     * Call a method on this cast member.
     * Handles Director member methods like charPosToLoc.
     */
    public Datum callMethod(String methodName, java.util.List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "charposttoloc", "charpostoloc" -> {
                // charPosToLoc(charIndex) → point(x, y)
                // Returns the pixel position of the character at the given index
                if (args.isEmpty()) yield new Datum.Point(0, 0);
                int charIndex = args.get(0).toInt();

                // Use AWT to measure text position
                String text = getTextContent();
                if (text == null || text.isEmpty() || charIndex <= 0) {
                    yield new Datum.Point(0, 0);
                }

                int fontStyleAwt = Font.PLAIN;
                String style = textFontStyle.toLowerCase();
                if (style.contains("bold")) fontStyleAwt |= Font.BOLD;
                if (style.contains("italic")) fontStyleAwt |= Font.ITALIC;
                Font font = resolveDirectorFont(textFont, fontStyleAwt, textFontSize);

                // Use a temporary image to get FontMetrics
                BufferedImage tmpImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = tmpImg.createGraphics();
                g2d.setFont(font);
                FontMetrics fm = g2d.getFontMetrics();

                // Clamp charIndex to text length
                int idx = Math.min(charIndex, text.length());
                String substr = text.substring(0, idx);

                // Find which line the character is on
                String[] lines = text.split("[\r\n]");
                int lineNum = 0;
                int charsSoFar = 0;
                String lineText = lines.length > 0 ? lines[0] : "";
                for (int i = 0; i < lines.length; i++) {
                    int lineLen = lines[i].length() + 1; // +1 for line break
                    if (charsSoFar + lineLen >= idx) {
                        lineNum = i;
                        lineText = lines[i];
                        break;
                    }
                    charsSoFar += lineLen;
                }

                int charsOnLine = idx - charsSoFar;
                if (charsOnLine > lineText.length()) charsOnLine = lineText.length();
                String lineSubstr = lineText.substring(0, charsOnLine);
                int x = fm.stringWidth(lineSubstr);
                int lineHeight = textFixedLineSpace > 0 ? textFixedLineSpace : fm.getHeight();
                int y = lineNum * lineHeight + fm.getAscent();

                g2d.dispose();
                yield new Datum.Point(x, y);
            }
            default -> Datum.VOID;
        };
    }

    @Override
    public String toString() {
        return "CastMember{castLib=" + castLibNumber + ", member=" + memberNumber +
               ", name='" + name + "', type=" + memberType + "}";
    }
}
