package com.libreshockwave.tools.preview;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.tools.model.MemberNodeData;

import javax.swing.*;

/**
 * Context for preview operations, providing access to common UI and data.
 */
public class PreviewContext {
    private final JLabel previewLabel;
    private final JTextArea detailsTextArea;
    private final JLabel statusLabel;
    private final DirectorFile dirFile;
    private final MemberNodeData memberData;

    public PreviewContext(JLabel previewLabel, JTextArea detailsTextArea, JLabel statusLabel,
                          DirectorFile dirFile, MemberNodeData memberData) {
        this.previewLabel = previewLabel;
        this.detailsTextArea = detailsTextArea;
        this.statusLabel = statusLabel;
        this.dirFile = dirFile;
        this.memberData = memberData;
    }

    public JLabel getPreviewLabel() {
        return previewLabel;
    }

    public JTextArea getDetailsTextArea() {
        return detailsTextArea;
    }

    public JLabel getStatusLabel() {
        return statusLabel;
    }

    public DirectorFile getDirFile() {
        return dirFile;
    }

    public MemberNodeData getMemberData() {
        return memberData;
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public void setDetailsText(String text) {
        detailsTextArea.setText(text);
        detailsTextArea.setCaretPosition(0);
    }
}
