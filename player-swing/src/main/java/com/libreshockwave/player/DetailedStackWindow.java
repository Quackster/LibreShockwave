package com.libreshockwave.player;

import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.player.debug.DebugSnapshot;
import com.libreshockwave.player.debug.DebugStateListener;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Detailed stack inspection window for the debugger.
 * Shows expanded view of all stack values including arglist contents.
 */
public class DetailedStackWindow extends JFrame implements DebugStateListener {

    private JTextArea stackTextArea;
    private JTextArea callStackTextArea;
    private JTextArea argsTextArea;
    private JTextArea receiverTextArea;
    private JLabel statusLabel;
    private JTabbedPane tabbedPane;

    public DetailedStackWindow() {
        super("Detailed Stack View");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        initComponents();
        setSize(500, 600);
        setLocationByPlatform(true);
    }

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));

        // Status label at top
        statusLabel = new JLabel("Waiting for debugger pause...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        add(statusLabel, BorderLayout.NORTH);

        // Tabbed pane for different views
        tabbedPane = new JTabbedPane();

        // Tab 1: Call Stack
        callStackTextArea = new JTextArea();
        callStackTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        callStackTextArea.setEditable(false);
        callStackTextArea.setLineWrap(false);
        JScrollPane callStackScroll = new JScrollPane(callStackTextArea);
        tabbedPane.addTab("Call Stack", callStackScroll);

        // Tab 2: VM Stack (detailed)
        stackTextArea = new JTextArea();
        stackTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stackTextArea.setEditable(false);
        stackTextArea.setLineWrap(false);
        JScrollPane stackScroll = new JScrollPane(stackTextArea);
        tabbedPane.addTab("VM Stack", stackScroll);

        // Tab 3: Arguments
        argsTextArea = new JTextArea();
        argsTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        argsTextArea.setEditable(false);
        argsTextArea.setLineWrap(false);
        JScrollPane argsScroll = new JScrollPane(argsTextArea);
        tabbedPane.addTab("Arguments", argsScroll);

        // Tab 4: Receiver
        receiverTextArea = new JTextArea();
        receiverTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        receiverTextArea.setEditable(false);
        receiverTextArea.setLineWrap(true);
        receiverTextArea.setWrapStyleWord(true);
        JScrollPane receiverScroll = new JScrollPane(receiverTextArea);
        tabbedPane.addTab("Receiver (me)", receiverScroll);

        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * Update the display with current debug snapshot.
     */
    public void updateFromSnapshot(DebugSnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Paused at: " + snapshot.handlerName() + " (offset " + snapshot.instructionOffset() + ")");

            // Format call stack (most recent at top)
            callStackTextArea.setText(formatCallStack(snapshot.callStack()));

            // Format VM stack with detailed arglist expansion
            stackTextArea.setText(formatStackDetailed(snapshot.stack()));

            // Format arguments
            argsTextArea.setText(formatArguments(snapshot.arguments()));

            // Format receiver
            receiverTextArea.setText(formatReceiver(snapshot.receiver()));

            // Scroll to bottom of VM stack to show top-of-stack
            stackTextArea.setCaretPosition(stackTextArea.getDocument().getLength());
        });
    }

    /**
     * Format the call stack (most recent frame at top).
     */
    private String formatCallStack(List<DebugController.CallFrame> callStack) {
        if (callStack == null || callStack.isEmpty()) {
            return "(no call stack)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Call Stack (").append(callStack.size()).append(" frames):\n");
        sb.append("─".repeat(50)).append("\n\n");

        // Display in reverse order (most recent first)
        for (int i = callStack.size() - 1; i >= 0; i--) {
            DebugController.CallFrame frame = callStack.get(i);
            int depth = callStack.size() - 1 - i;

            // Frame header with arrow for current frame
            if (depth == 0) {
                sb.append("► ");
            } else {
                sb.append("  ");
            }
            sb.append("[").append(depth).append("] ");
            sb.append(frame.handlerName()).append("(");

            // Show arguments inline
            if (frame.arguments() != null && !frame.arguments().isEmpty()) {
                for (int j = 0; j < frame.arguments().size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(DatumFormatter.formatBrief(frame.arguments().get(j)));
                }
            }
            sb.append(")\n");

            // Script name
            sb.append("     in: ").append(frame.scriptName()).append("\n");

            // Receiver if present
            if (frame.receiver() != null) {
                sb.append("     me: ").append(DatumFormatter.formatBrief(frame.receiver())).append("\n");
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Format the VM stack with detailed arglist expansion.
     */
    private String formatStackDetailed(List<Datum> stack) {
        if (stack == null || stack.isEmpty()) {
            return "(empty stack)";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            Datum d = stack.get(i);
            sb.append(String.format("[%3d] ", i));
            sb.append(DatumFormatter.formatDetailed(d, 0));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Format the arguments list.
     */
    private String formatArguments(List<Datum> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "(no arguments)";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arguments.size(); i++) {
            sb.append("arg").append(i + 1).append(" = ");
            sb.append(DatumFormatter.formatDetailed(arguments.get(i), 0));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Format the receiver (me) value.
     */
    private String formatReceiver(Datum receiver) {
        if (receiver == null) {
            return "(no receiver)";
        }
        return DatumFormatter.formatDetailed(receiver, 0);
    }

    // DebugStateListener implementation

    @Override
    public void onPaused(DebugSnapshot snapshot) {
        updateFromSnapshot(snapshot);
    }

    @Override
    public void onResumed() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Running...");
        });
    }

    @Override
    public void onBreakpointsChanged() {
        // Not relevant for this window
    }
}
