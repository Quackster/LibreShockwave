package com.libreshockwave.editor;

import javax.swing.*;
import java.awt.*;

/**
 * Main editor toolbar with playback controls and frame counter.
 */
public class EditorToolBar extends JToolBar {

    private final EditorContext context;
    private final JLabel frameLabel;

    public EditorToolBar(EditorContext context) {
        this.context = context;
        setFloatable(false);

        // Playback controls
        JButton rewindBtn = new JButton("\u23EE");
        rewindBtn.setToolTipText("Rewind");
        rewindBtn.addActionListener(e -> context.rewind());
        add(rewindBtn);

        JButton stopBtn = new JButton("\u23F9");
        stopBtn.setToolTipText("Stop");
        stopBtn.addActionListener(e -> context.stop());
        add(stopBtn);

        JButton playBtn = new JButton("\u25B6");
        playBtn.setToolTipText("Play");
        playBtn.addActionListener(e -> context.play());
        add(playBtn);

        addSeparator();

        JButton stepBackBtn = new JButton("\u23EA");
        stepBackBtn.setToolTipText("Step Backward");
        stepBackBtn.addActionListener(e -> context.stepBackward());
        add(stepBackBtn);

        JButton stepFwdBtn = new JButton("\u23E9");
        stepFwdBtn.setToolTipText("Step Forward");
        stepFwdBtn.addActionListener(e -> context.stepForward());
        add(stepFwdBtn);

        addSeparator();

        // Frame counter
        frameLabel = new JLabel("Frame: 1");
        frameLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        add(frameLabel);

        // Listen for frame changes
        context.addPropertyChangeListener(evt -> {
            if (EditorContext.PROP_FRAME.equals(evt.getPropertyName())) {
                updateFrameLabel((int) evt.getNewValue());
            }
        });
    }

    private void updateFrameLabel(int frame) {
        var player = context.getPlayer();
        if (player != null) {
            frameLabel.setText("Frame: " + frame + " / " + player.getFrameCount());
        } else {
            frameLabel.setText("Frame: 1");
        }
    }
}
