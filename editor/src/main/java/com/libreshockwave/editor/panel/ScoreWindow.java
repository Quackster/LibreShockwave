package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.EditorContext;

import javax.swing.*;
import java.awt.*;

/**
 * Score window - Director MX 2004 timeline with channels, frames, and markers.
 * Displays special channels (tempo, palette, transition, sound, script) at top,
 * followed by sprite channels with colored cells indicating member types.
 */
public class ScoreWindow extends EditorPanel {

    private final JLabel placeholder;

    public ScoreWindow(EditorContext context) {
        super("Score", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Placeholder until score components are implemented
        placeholder = new JLabel("Score - Timeline will be displayed here", SwingConstants.CENTER);
        placeholder.setFont(placeholder.getFont().deriveFont(14f));

        // Bottom status showing frame info
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusBar.add(new JLabel("Frame: 1"));
        statusBar.add(new JLabel(" | Channel: -"));

        panel.add(placeholder, BorderLayout.CENTER);
        panel.add(statusBar, BorderLayout.SOUTH);

        setContentPane(panel);
        setSize(700, 300);
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        placeholder.setText("Score - " + context.getPlayer().getFrameCount() + " frames");
    }

    @Override
    protected void onFileClosed() {
        placeholder.setText("Score - Timeline will be displayed here");
    }

    @Override
    protected void onFrameChanged(int frame) {
        // Will update playback head position
    }
}
