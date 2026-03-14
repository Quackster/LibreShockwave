package com.libreshockwave.editor.script;

import javax.swing.text.*;

/**
 * StyledDocument subclass for Lingo source code.
 * Automatically applies syntax highlighting on content changes.
 */
public class LingoDocument extends DefaultStyledDocument {

    private boolean highlighting = false;

    @Override
    public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
        super.insertString(offs, str, a);
        rehighlight();
    }

    @Override
    public void remove(int offs, int len) throws BadLocationException {
        super.remove(offs, len);
        rehighlight();
    }

    private void rehighlight() {
        if (highlighting) return;
        highlighting = true;
        try {
            LingoSyntaxHighlighter.highlight(this);
        } finally {
            highlighting = false;
        }
    }
}
