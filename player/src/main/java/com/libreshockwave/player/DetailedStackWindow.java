package com.libreshockwave.player;

import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.player.debug.DebugSnapshot;
import com.libreshockwave.player.debug.DebugStateListener;
import com.libreshockwave.vm.Datum;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

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
                    sb.append(formatDatumBrief(frame.arguments().get(j)));
                }
            }
            sb.append(")\n");

            // Script name
            sb.append("     in: ").append(frame.scriptName()).append("\n");

            // Receiver if present
            if (frame.receiver() != null) {
                sb.append("     me: ").append(formatDatumBrief(frame.receiver())).append("\n");
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
            sb.append(formatDatumDetailed(d, 0));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Format a Datum with full details, expanding arglists and nested structures.
     */
    private String formatDatumDetailed(Datum d, int indent) {
        if (d == null) return "<null>";

        String indentStr = "      " + "  ".repeat(indent);

        return switch (d) {
            case Datum.Void v -> "<Void>";
            case Datum.Int i -> "Int: " + i.value();
            case Datum.Float f -> "Float: " + f.value();
            case Datum.Str s -> "Str: \"" + escapeString(s.value()) + "\"";
            case Datum.Symbol sym -> "Symbol: #" + sym.name();

            case Datum.ArgList argList -> {
                StringBuilder sb = new StringBuilder();
                sb.append("ArgList (expects return) [").append(argList.count()).append(" items]");
                if (!argList.items().isEmpty()) {
                    sb.append(" {");
                    for (int i = 0; i < argList.items().size(); i++) {
                        sb.append("\n").append(indentStr).append("  [").append(i).append("] ");
                        sb.append(formatDatumDetailed(argList.items().get(i), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.ArgListNoRet argList -> {
                StringBuilder sb = new StringBuilder();
                sb.append("ArgListNoRet (no return) [").append(argList.count()).append(" items]");
                if (!argList.items().isEmpty()) {
                    sb.append(" {");
                    for (int i = 0; i < argList.items().size(); i++) {
                        sb.append("\n").append(indentStr).append("  [").append(i).append("] ");
                        sb.append(formatDatumDetailed(argList.items().get(i), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                }
                yield sb.toString();
            }

            case Datum.List list -> {
                StringBuilder sb = new StringBuilder();
                sb.append("List [").append(list.items().size()).append(" items]");
                if (!list.items().isEmpty() && list.items().size() <= 10) {
                    sb.append(" {");
                    for (int i = 0; i < list.items().size(); i++) {
                        sb.append("\n").append(indentStr).append("  [").append(i).append("] ");
                        sb.append(formatDatumDetailed(list.items().get(i), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                } else if (!list.items().isEmpty()) {
                    sb.append(" (too large to expand)");
                }
                yield sb.toString();
            }

            case Datum.PropList propList -> {
                StringBuilder sb = new StringBuilder();
                sb.append("PropList [").append(propList.properties().size()).append(" props]");
                if (!propList.properties().isEmpty() && propList.properties().size() <= 10) {
                    sb.append(" {");
                    for (Map.Entry<String, Datum> entry : propList.properties().entrySet()) {
                        sb.append("\n").append(indentStr).append("  #").append(entry.getKey()).append(": ");
                        sb.append(formatDatumDetailed(entry.getValue(), indent + 1));
                    }
                    sb.append("\n").append(indentStr).append("}");
                } else if (!propList.properties().isEmpty()) {
                    sb.append(" (too large to expand)");
                }
                yield sb.toString();
            }

            case Datum.ScriptInstance si -> {
                StringBuilder sb = new StringBuilder();
                sb.append("ScriptInstance #").append(si.scriptId());
                if (!si.properties().isEmpty() && si.properties().size() <= 5) {
                    sb.append(" {");
                    for (Map.Entry<String, Datum> entry : si.properties().entrySet()) {
                        sb.append("\n").append(indentStr).append("  .").append(entry.getKey()).append(" = ");
                        sb.append(formatDatumBrief(entry.getValue()));
                    }
                    sb.append("\n").append(indentStr).append("}");
                } else if (!si.properties().isEmpty()) {
                    sb.append(" [").append(si.properties().size()).append(" properties]");
                }
                yield sb.toString();
            }

            case Datum.Point p -> "Point: (" + p.x() + ", " + p.y() + ")";
            case Datum.Rect r -> "Rect: (" + r.left() + ", " + r.top() + ", " + r.right() + ", " + r.bottom() + ")";
            case Datum.Color c -> "Color: rgb(" + c.r() + ", " + c.g() + ", " + c.b() + ")";
            case Datum.SpriteRef sr -> "SpriteRef: sprite(" + sr.channel() + ")";
            case Datum.CastMemberRef cm -> "CastMemberRef: member(" + cm.member() + ", " + cm.castLib() + ")";
            case Datum.CastLibRef cl -> "CastLibRef: castLib(" + cl.castLibNumber() + ")";
            case Datum.StageRef sr -> "StageRef: (the stage)";
            case Datum.WindowRef w -> "WindowRef: window(\"" + w.name() + "\")";
            case Datum.XtraRef xr -> "XtraRef: xtra(\"" + xr.xtraName() + "\")";
            case Datum.XtraInstance xi -> "XtraInstance: \"" + xi.xtraName() + "\" #" + xi.instanceId();
            case Datum.ScriptRef sr -> "ScriptRef: script(" + sr.member() + ", " + sr.castLib() + ")";
            default -> d.getClass().getSimpleName() + ": " + d.toString();
        };
    }

    /**
     * Format a Datum briefly (for nested display).
     */
    private String formatDatumBrief(Datum d) {
        if (d == null) return "<null>";

        return switch (d) {
            case Datum.Void v -> "<Void>";
            case Datum.Int i -> String.valueOf(i.value());
            case Datum.Float f -> String.valueOf(f.value());
            case Datum.Str s -> "\"" + truncate(escapeString(s.value()), 30) + "\"";
            case Datum.Symbol sym -> "#" + sym.name();
            case Datum.List list -> "[list:" + list.items().size() + "]";
            case Datum.PropList pl -> "[propList:" + pl.properties().size() + "]";
            case Datum.ArgList al -> "<arglist:" + al.count() + ">";
            case Datum.ArgListNoRet al -> "<arglist-noret:" + al.count() + ">";
            case Datum.ScriptInstance si -> "<script#" + si.scriptId() + ">";
            default -> d.toString();
        };
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
            sb.append(formatDatumDetailed(arguments.get(i), 0));
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
        return formatDatumDetailed(receiver, 0);
    }

    private String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
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
