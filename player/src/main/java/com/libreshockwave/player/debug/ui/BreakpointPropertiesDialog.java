package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.Breakpoint;
import com.libreshockwave.player.debug.DebugController;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog for editing breakpoint properties.
 */
public class BreakpointPropertiesDialog extends JDialog {

    private final DebugController controller;
    private final int scriptId;
    private final int offset;
    private Breakpoint breakpoint;

    private JCheckBox enabledCheck;
    private JTextField conditionField;
    private JSpinner hitThresholdSpinner;
    private JLabel hitCountLabel;
    private JTextField logMessageField;

    public BreakpointPropertiesDialog(Window owner, DebugController controller, int scriptId, int offset) {
        super(owner, "Breakpoint Properties", ModalityType.APPLICATION_MODAL);
        this.controller = controller;
        this.scriptId = scriptId;
        this.offset = offset;

        // Get or create breakpoint
        this.breakpoint = controller.getBreakpoint(scriptId, offset);
        if (this.breakpoint == null) {
            this.breakpoint = Breakpoint.simple(scriptId, offset);
            controller.setBreakpoint(this.breakpoint);
        }

        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setSize(400, 300);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Enabled checkbox
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Enabled:"), gbc);
        enabledCheck = new JCheckBox();
        enabledCheck.setSelected(breakpoint.enabled());
        gbc.gridx = 1;
        formPanel.add(enabledCheck, gbc);

        // Condition field
        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("Condition:"), gbc);
        conditionField = new JTextField(breakpoint.condition() != null ? breakpoint.condition() : "", 25);
        conditionField.setToolTipText("Lingo expression, e.g., i > 5");
        gbc.gridx = 1;
        formPanel.add(conditionField, gbc);

        // Hit count threshold
        gbc.gridx = 0; gbc.gridy = 2;
        formPanel.add(new JLabel("Break after hit count:"), gbc);
        hitThresholdSpinner = new JSpinner(new SpinnerNumberModel(breakpoint.hitCountThreshold(), 0, 10000, 1));
        hitThresholdSpinner.setToolTipText("0 = always break, >0 = break after N hits");
        gbc.gridx = 1;
        formPanel.add(hitThresholdSpinner, gbc);

        // Current hit count (read-only)
        gbc.gridx = 0; gbc.gridy = 3;
        formPanel.add(new JLabel("Current hit count:"), gbc);
        hitCountLabel = new JLabel(String.valueOf(breakpoint.hitCount()));
        gbc.gridx = 1;
        formPanel.add(hitCountLabel, gbc);

        // Reset hit count button
        JButton resetHitBtn = new JButton("Reset");
        resetHitBtn.addActionListener(e -> {
            controller.resetBreakpointHitCount(scriptId, offset);
            hitCountLabel.setText("0");
        });
        gbc.gridx = 2;
        formPanel.add(resetHitBtn, gbc);

        // Log message field
        gbc.gridx = 0; gbc.gridy = 4;
        formPanel.add(new JLabel("Log message:"), gbc);
        logMessageField = new JTextField(breakpoint.logMessage() != null ? breakpoint.logMessage() : "", 25);
        logMessageField.setToolTipText("If set, logs message instead of pausing. Use {var} for interpolation.");
        gbc.gridx = 1; gbc.gridwidth = 2;
        formPanel.add(logMessageField, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 3;
        formPanel.add(new JLabel("<html><small>Log message converts breakpoint to log point (no pause).</small></html>"), gbc);

        add(formPanel, BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> applyAndClose());
        buttonPanel.add(okBtn);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        buttonPanel.add(cancelBtn);

        JButton removeBtn = new JButton("Remove Breakpoint");
        removeBtn.addActionListener(e -> {
            controller.removeBreakpoint(scriptId, offset);
            dispose();
        });
        buttonPanel.add(removeBtn);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void applyAndClose() {
        String condition = conditionField.getText().trim();
        String logMessage = logMessageField.getText().trim();
        int hitThreshold = (Integer) hitThresholdSpinner.getValue();

        Breakpoint updated = new Breakpoint(
            scriptId,
            offset,
            enabledCheck.isSelected(),
            condition.isEmpty() ? null : condition,
            logMessage.isEmpty() ? null : logMessage,
            breakpoint.hitCount(),
            hitThreshold
        );
        controller.setBreakpoint(updated);
        dispose();
    }

    /**
     * Show the breakpoint properties dialog.
     */
    public static void show(Component parent, DebugController controller, int scriptId, int offset) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        BreakpointPropertiesDialog dialog = new BreakpointPropertiesDialog(owner, controller, scriptId, offset);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /**
     * Show a simple dialog to add a log point.
     * @return true if a log point was added
     */
    public static boolean showAddLogPointDialog(Component parent, DebugController controller, int scriptId, int offset) {
        String message = JOptionPane.showInputDialog(
            parent,
            "Enter log message (use {variable} for interpolation):\nExample: Loop iteration {i}, value = {x}",
            "Add Log Point",
            JOptionPane.PLAIN_MESSAGE
        );

        if (message != null && !message.trim().isEmpty()) {
            Breakpoint bp = Breakpoint.logPoint(scriptId, offset, message.trim());
            controller.setBreakpoint(bp);
            return true;
        }
        return false;
    }
}
