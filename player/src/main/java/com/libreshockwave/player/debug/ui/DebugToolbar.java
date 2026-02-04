package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.DebugController;

import javax.swing.*;
import java.awt.*;

/**
 * Toolbar with Step/Continue/Pause buttons for debugging.
 */
public class DebugToolbar extends JPanel {

    private final JButton stepIntoBtn;
    private final JButton stepOverBtn;
    private final JButton stepOutBtn;
    private final JButton continueBtn;
    private final JButton pauseBtn;
    private final JButton clearBpBtn;

    private DebugController controller;

    public DebugToolbar() {
        setLayout(new FlowLayout(FlowLayout.LEFT, 5, 2));
        setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        stepIntoBtn = new JButton("Step Into");
        stepIntoBtn.setToolTipText("Step Into (F11)");
        stepIntoBtn.setEnabled(false);
        stepIntoBtn.addActionListener(e -> {
            if (controller != null) controller.stepInto();
        });
        add(stepIntoBtn);

        stepOverBtn = new JButton("Step Over");
        stepOverBtn.setToolTipText("Step Over (F10)");
        stepOverBtn.setEnabled(false);
        stepOverBtn.addActionListener(e -> {
            if (controller != null) controller.stepOver();
        });
        add(stepOverBtn);

        stepOutBtn = new JButton("Step Out");
        stepOutBtn.setToolTipText("Step Out (Shift+F11)");
        stepOutBtn.setEnabled(false);
        stepOutBtn.addActionListener(e -> {
            if (controller != null) controller.stepOut();
        });
        add(stepOutBtn);

        add(Box.createHorizontalStrut(10));

        continueBtn = new JButton("Continue");
        continueBtn.setToolTipText("Continue (F5)");
        continueBtn.setEnabled(false);
        continueBtn.addActionListener(e -> {
            if (controller != null) controller.continueExecution();
        });
        add(continueBtn);

        pauseBtn = new JButton("Pause");
        pauseBtn.setToolTipText("Pause (F6)");
        pauseBtn.addActionListener(e -> {
            if (controller != null) controller.pause();
        });
        add(pauseBtn);

        add(Box.createHorizontalStrut(20));

        clearBpBtn = new JButton("Clear BPs");
        clearBpBtn.setToolTipText("Clear all breakpoints");
        clearBpBtn.addActionListener(e -> {
            if (controller != null) controller.clearAllBreakpoints();
        });
        add(clearBpBtn);
    }

    /**
     * Set the debug controller.
     */
    public void setController(DebugController controller) {
        this.controller = controller;
    }

    /**
     * Enable or disable the step/continue buttons.
     * Call with true when paused, false when running.
     */
    public void setStepButtonsEnabled(boolean enabled) {
        stepIntoBtn.setEnabled(enabled);
        stepOverBtn.setEnabled(enabled);
        stepOutBtn.setEnabled(enabled);
        continueBtn.setEnabled(enabled);
    }

    /**
     * Check if step buttons are currently enabled.
     */
    public boolean areStepButtonsEnabled() {
        return stepIntoBtn.isEnabled();
    }
}
