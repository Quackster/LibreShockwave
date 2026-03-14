package com.libreshockwave.editor.script;

import javax.swing.text.*;
import java.awt.*;
import java.util.List;

/**
 * Applies syntax highlighting colors to a Lingo script document.
 */
public class LingoSyntaxHighlighter {

    // Director MX 2004 script editor colors
    private static final Color COLOR_KEYWORD = new Color(0, 0, 192);      // Blue
    private static final Color COLOR_COMMAND = new Color(0, 0, 192);      // Blue
    private static final Color COLOR_FUNCTION = new Color(0, 128, 0);     // Green
    private static final Color COLOR_EVENT = new Color(128, 0, 128);      // Purple
    private static final Color COLOR_STRING = new Color(128, 0, 0);       // Dark red
    private static final Color COLOR_COMMENT = new Color(128, 128, 128);  // Gray
    private static final Color COLOR_NUMBER = new Color(255, 0, 0);       // Red
    private static final Color COLOR_SYMBOL = new Color(0, 128, 128);     // Teal
    private static final Color COLOR_DEFAULT = Color.BLACK;

    /**
     * Apply syntax highlighting to the given styled document.
     */
    public static void highlight(StyledDocument doc) {
        try {
            String text = doc.getText(0, doc.getLength());
            List<LingoTokenizer.Token> tokens = LingoTokenizer.tokenize(text);

            // Reset all to default
            SimpleAttributeSet defaultAttr = createStyle(COLOR_DEFAULT, false);
            doc.setCharacterAttributes(0, text.length(), defaultAttr, true);

            // Apply token colors
            for (LingoTokenizer.Token token : tokens) {
                SimpleAttributeSet attr = switch (token.type()) {
                    case KEYWORD -> createStyle(COLOR_KEYWORD, true);
                    case COMMAND -> createStyle(COLOR_COMMAND, true);
                    case FUNCTION -> createStyle(COLOR_FUNCTION, false);
                    case EVENT -> createStyle(COLOR_EVENT, false);
                    case STRING -> createStyle(COLOR_STRING, false);
                    case COMMENT -> createStyle(COLOR_COMMENT, true);
                    case NUMBER -> createStyle(COLOR_NUMBER, false);
                    case SYMBOL -> createStyle(COLOR_SYMBOL, false);
                    default -> null;
                };

                if (attr != null) {
                    doc.setCharacterAttributes(token.start(), token.end() - token.start(), attr, true);
                }
            }
        } catch (BadLocationException e) {
            // Ignore highlighting errors
        }
    }

    private static SimpleAttributeSet createStyle(Color color, boolean bold) {
        SimpleAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setForeground(attr, color);
        if (bold) {
            StyleConstants.setBold(attr, true);
        }
        return attr;
    }
}
